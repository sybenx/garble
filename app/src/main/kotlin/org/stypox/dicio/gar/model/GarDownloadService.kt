package org.stypox.dicio.gar.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.stypox.dicio.MainActivity
import org.stypox.dicio.R
import org.stypox.dicio.gar.stt.GarSttModelManager
import org.stypox.dicio.gar.stt.SttTransfer
import org.stypox.dicio.gar.ui.setGarSmallIcon
import javax.inject.Inject

/**
 * What the download notification shows, from the language model's and the speech model's
 * transfers together; null when neither is downloading.
 */
data class GarDownloadProgress(
    val languageModel: Boolean,
    val speechModel: Boolean,
    val currentBytes: Long,
    val totalBytes: Long,
) {
    /** 0 to 100, or null while the size isn't known. */
    val percent: Int?
        get() = if (totalBytes > 0) {
            (currentBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }

    companion object {
        fun of(languageModel: GarTransferState, speechModel: SttTransfer): GarDownloadProgress? {
            val llm = languageModel as? GarTransferState.Running
            val stt = speechModel as? SttTransfer.Running
            if (llm == null && stt == null) {
                return null
            }
            return GarDownloadProgress(
                languageModel = llm != null,
                speechModel = stt != null,
                currentBytes = (llm?.currentBytes ?: 0) + (stt?.currentBytes ?: 0),
                totalBytes = (llm?.totalBytes ?: 0) + (stt?.totalBytes ?: 0),
            )
        }
    }
}

/**
 * Keeps the process alive while a model downloads (gar/DESIGN.md, "Model lifecycle and
 * settings"): a foreground service of type dataSync with a progress notification and a Cancel
 * button. The downloads themselves stay in [GarModelManager] and [GarSttModelManager]; without
 * this service Android could kill or freeze the process once the app is in the background, and
 * the download stopped until the next tap (the `.part` file still resumes then). It stops itself
 * as soon as neither manager is downloading.
 */
@AndroidEntryPoint
class GarDownloadService : Service() {

    @Inject
    lateinit var modelManager: GarModelManager

    @Inject
    lateinit var sttModelManager: GarSttModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            modelManager.cancelDownload()
            sttModelManager.cancelDownload()
        }
        // always within the few seconds Android allows after startForegroundService
        val progress = GarDownloadProgress.of(
            modelManager.downloadState.value, sttModelManager.transfer.value
        )
        if (!goForeground(progress ?: STARTING)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) {
            watching = scope.launch {
                combine(modelManager.downloadState, sttModelManager.transfer) { llm, stt ->
                    GarDownloadProgress.of(llm, stt)
                }
                    // one update per percent, not per read
                    .distinctUntilChanged { a, b ->
                        a?.copy(currentBytes = 0) == b?.copy(currentBytes = 0) &&
                            a?.percent == b?.percent
                    }
                    .collect { now ->
                        if (now == null) {
                            stop()
                        } else {
                            notificationManager().notify(NOTIFICATION_ID, notification(now))
                        }
                    }
            }
        }
        // after a kill, the next tap resumes from the .part file: nothing to restart here
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services get a few hours a day; the download goes on unprotected. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(progress: GarDownloadProgress): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(progress),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        true
    } catch (e: RuntimeException) {
        // e.g. ForegroundServiceStartNotAllowedException: the download goes on without it
        Log.w(TAG, "Could not keep the download in the foreground", e)
        false
    }

    private fun notificationManager(): NotificationManager =
        ContextCompat.getSystemService(this, NotificationManager::class.java)!!

    private fun notification(progress: GarDownloadProgress): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.gar_download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val title = getString(
            when {
                progress.languageModel && progress.speechModel -> R.string.gar_download_both
                progress.speechModel -> R.string.gar_download_speech_model
                else -> R.string.gar_download_language_model
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, GarDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = progress.percent
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setGarSmallIcon(this)
            .setContentTitle(title)
            .setContentText(percent?.let { "$it%" })
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(0, getString(R.string.gar_download_cancel), cancel)
            .build()
    }

    companion object {
        private const val TAG = "GarDownloadService"
        private const val CHANNEL_ID = "gar_downloads"
        private const val NOTIFICATION_ID = 0x6a7d
        private const val ACTION_CANCEL = "cc.aaro.gar.action.CANCEL_DOWNLOAD"
        private val STARTING = GarDownloadProgress(true, false, 0, 0)

        /**
         * Call once a download is running (its state already says so). The user just tapped
         * download, so the app is in the foreground and may start a foreground service; if
         * Android refuses anyway, the download goes on unprotected.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, GarDownloadService::class.java)
                )
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start the download service", e)
            }
        }
    }
}
