package org.stypox.dicio.gar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.model.GarDownloadProgress
import org.stypox.dicio.gar.model.GarTransferState
import org.stypox.dicio.gar.settings.GarModel
import org.stypox.dicio.gar.stt.SttTransfer

/** What the download service's notification says, and when it stops (null). */
class GarDownloadProgressTest : StringSpec({
    val model = GarModel.GAR_MODEL_QWEN3_0_6B

    "nothing running: no notification, the service stops" {
        GarDownloadProgress.of(GarTransferState.Idle, SttTransfer.Idle) shouldBe null
        GarDownloadProgress.of(
            GarTransferState.Failed(model, "Download failed"),
            SttTransfer.Failed(RuntimeException()),
        ) shouldBe null
    }

    "the language model alone" {
        val p = GarDownloadProgress.of(GarTransferState.Running(model, 250, 1000), SttTransfer.Idle)!!
        p.languageModel shouldBe true
        p.speechModel shouldBe false
        p.percent shouldBe 25
    }

    "the speech model alone" {
        val p = GarDownloadProgress.of(GarTransferState.Idle, SttTransfer.Running(50, 200))!!
        p.languageModel shouldBe false
        p.speechModel shouldBe true
        p.percent shouldBe 25
    }

    "both at once: one notification over the bytes of both" {
        val p = GarDownloadProgress.of(
            GarTransferState.Running(model, 900, 1000),
            SttTransfer.Running(100, 1000),
        )!!
        (p.languageModel to p.speechModel) shouldBe (true to true)
        p.percent shouldBe 50
    }

    "an unknown size shows an indeterminate bar; a size overshoot stays at 100" {
        GarDownloadProgress.of(GarTransferState.Running(model, 10, 0), SttTransfer.Idle)!!
            .percent shouldBe null
        GarDownloadProgress.of(GarTransferState.Running(model, 1200, 1000), SttTransfer.Idle)!!
            .percent shouldBe 100
    }
})
