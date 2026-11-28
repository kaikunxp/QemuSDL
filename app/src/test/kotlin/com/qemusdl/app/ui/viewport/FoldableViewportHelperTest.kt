package com.qemusdl.app.ui.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldableViewportHelperTest {

    @Test
    fun `half-opened horizontal fold activates Tabletop Mini-Laptop Mode`() {
        val posture = FoldableViewportHelper.resolveTabletopFromHingeBounds(
            isHalfOpened = true,
            isHorizontal = true,
            hingeTopPx = 1104,
            hingeBottomPx = 1128,
            windowHeightPx = 2232
        )
        assertTrue(posture.isTabletop)
        assertEquals(1104, posture.hingeTopPx)
        assertEquals(1128, posture.hingeBottomPx)
    }

    @Test
    fun `flat or vertical book-mode fold does not activate Tabletop Mode`() {
        val flat = FoldableViewportHelper.resolveTabletopFromHingeBounds(
            isHalfOpened = false,
            isHorizontal = true,
            hingeTopPx = 1104,
            hingeBottomPx = 1128,
            windowHeightPx = 2232
        )
        val bookMode = FoldableViewportHelper.resolveTabletopFromHingeBounds(
            isHalfOpened = true,
            isHorizontal = false,
            hingeTopPx = 0,
            hingeBottomPx = 2232,
            windowHeightPx = 2232
        )
        assertFalse(flat.isTabletop)
        assertFalse(bookMode.isTabletop)
    }

    @Test
    fun `too-small top or bottom half rejects Tabletop Mode`() {
        val tinyTop = FoldableViewportHelper.resolveTabletopFromHingeBounds(
            isHalfOpened = true,
            isHorizontal = true,
            hingeTopPx = 120,
            hingeBottomPx = 140,
            windowHeightPx = 2232
        )
        val tinyBottom = FoldableViewportHelper.resolveTabletopFromHingeBounds(
            isHalfOpened = true,
            isHorizontal = true,
            hingeTopPx = 2050,
            hingeBottomPx = 2070,
            windowHeightPx = 2232
        )
        assertFalse(tinyTop.isTabletop)
        assertFalse(tinyBottom.isTabletop)
    }

    @Test
    fun `letterbox dock activates only when ample bottom letterbox exists outside Tabletop and PiP`() {
        assertTrue(
            FoldableViewportHelper.shouldUseLetterboxDock(
                isTabletop = false,
                isInPip = false,
                bottomLetterboxDp = 158f
            )
        )
        assertFalse(
            FoldableViewportHelper.shouldUseLetterboxDock(
                isTabletop = true,
                isInPip = false,
                bottomLetterboxDp = 158f
            )
        )
        assertFalse(
            FoldableViewportHelper.shouldUseLetterboxDock(
                isTabletop = false,
                isInPip = true,
                bottomLetterboxDp = 158f
            )
        )
        assertFalse(
            FoldableViewportHelper.shouldUseLetterboxDock(
                isTabletop = false,
                isInPip = false,
                bottomLetterboxDp = 24f
            )
        )
    }

    @Test
    fun `PiP aspect ratio preserves standard VM sizes and clamps extreme aspect ratios`() {
        assertEquals(1280 to 720, FoldableViewportHelper.computePipAspectRatioPair(1280, 720))
        assertEquals(1024 to 768, FoldableViewportHelper.computePipAspectRatioPair(1024, 768))

        val ultrawide = FoldableViewportHelper.computePipAspectRatioPair(3440, 1000)
        val ultrawideRatio = ultrawide.first.toFloat() / ultrawide.second.toFloat()
        assertTrue(ultrawideRatio <= 2.39f)
        assertTrue(ultrawideRatio >= 2.35f)

        val superTall = FoldableViewportHelper.computePipAspectRatioPair(400, 2000)
        val superTallRatio = superTall.first.toFloat() / superTall.second.toFloat()
        assertTrue(superTallRatio >= 0.419f)
        assertTrue(superTallRatio <= 0.43f)
    }
}
