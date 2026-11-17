package org.stypox.dicio.gar.sheet

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val HIDDEN = 1000f
private const val EXPANDED = -1500f
private const val FAST = 500f

private fun settle(offset: Float, velocity: Float = 0f) =
    garSheetSettleTarget(offset, velocity, HIDDEN, EXPANDED, velocityThreshold = 300f)

class GarSheetSettleTest : StringSpec({
    "at rest stays shown" {
        settle(0f) shouldBe GarSheetAnchor.Shown
    }
    "a short drag down springs back, a long one hides" {
        settle(100f) shouldBe GarSheetAnchor.Shown
        settle(600f) shouldBe GarSheetAnchor.Hidden
    }
    "a short drag up springs back, a long one opens the app" {
        settle(-200f) shouldBe GarSheetAnchor.Shown
        settle(-700f) shouldBe GarSheetAnchor.Expanded
    }
    "a fling up opens the app, a fling down from up returns to shown" {
        settle(-50f, velocity = -FAST) shouldBe GarSheetAnchor.Expanded
        settle(-1200f, velocity = FAST) shouldBe GarSheetAnchor.Shown
    }
    "a fling down hides, a fling up from down returns to shown" {
        settle(50f, velocity = FAST) shouldBe GarSheetAnchor.Hidden
        settle(900f, velocity = -FAST) shouldBe GarSheetAnchor.Shown
    }
})
