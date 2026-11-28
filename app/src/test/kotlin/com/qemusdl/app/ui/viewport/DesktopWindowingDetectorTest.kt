package com.qemusdl.app.ui.viewport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWindowingDetectorTest {

    @Test
    fun regularPhoneFullscreenHidesFullscreenButton() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = false,
            isChromebook = false,
            hasPcFeature = false,
            isDeskUiMode = false,
            isSecondaryDisplay = false,
            isInMultiWindowMode = false,
            hasFreeformFeature = false,
            currentWidthPx = 2400,
            currentHeightPx = 1080,
            maxWidthPx = 2400,
            maxHeightPx = 1080
        )
        assertFalse(state.isDesktopPlatform)
        assertFalse(state.isWindowed)
        assertFalse(state.showFullscreenButton)
    }

    @Test
    fun samsungDexShowsFullscreenButton() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = true,
            isChromebook = false,
            hasPcFeature = false,
            isDeskUiMode = true,
            isSecondaryDisplay = true,
            isInMultiWindowMode = true,
            hasFreeformFeature = true,
            currentWidthPx = 1280,
            currentHeightPx = 800,
            maxWidthPx = 1920,
            maxHeightPx = 1080
        )
        assertTrue(state.isDesktopPlatform)
        assertTrue(state.isWindowed)
        assertTrue(state.showFullscreenButton)
    }

    @Test
    fun chromebookShowsFullscreenButtonEvenWhenMaximized() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = false,
            isChromebook = true,
            hasPcFeature = true,
            isDeskUiMode = false,
            isSecondaryDisplay = false,
            isInMultiWindowMode = false,
            hasFreeformFeature = true,
            currentWidthPx = 1920,
            currentHeightPx = 1080,
            maxWidthPx = 1920,
            maxHeightPx = 1080
        )
        assertTrue(state.isDesktopPlatform)
        assertTrue(state.showFullscreenButton)
    }

    @Test
    fun motoReadyForExternalDisplayFreeformShowsFullscreenButton() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = false,
            isChromebook = false,
            hasPcFeature = false,
            isDeskUiMode = false,
            isSecondaryDisplay = true,
            isInMultiWindowMode = true,
            hasFreeformFeature = true,
            currentWidthPx = 1400,
            currentHeightPx = 900,
            maxWidthPx = 2560,
            maxHeightPx = 1440
        )
        assertTrue(state.isDesktopPlatform)
        assertTrue(state.isWindowed)
        assertTrue(state.showFullscreenButton)
    }

    @Test
    fun genericFreeformWindowShowsFullscreenButton() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = false,
            isChromebook = false,
            hasPcFeature = false,
            isDeskUiMode = false,
            isSecondaryDisplay = false,
            isInMultiWindowMode = true,
            hasFreeformFeature = false,
            currentWidthPx = 1200,
            currentHeightPx = 800,
            maxWidthPx = 2560,
            maxHeightPx = 1600
        )
        assertFalse(state.isDesktopPlatform)
        assertTrue(state.isWindowed)
        assertTrue(state.showFullscreenButton)
    }

    @Test
    fun fullscreenButtonRemainsVisibleAfterEnteringFullscreenFromWindowedMode() {
        val state = DesktopWindowingDetector.evaluate(
            isDeXMode = false,
            isChromebook = false,
            hasPcFeature = false,
            isDeskUiMode = false,
            isSecondaryDisplay = false,
            isInMultiWindowMode = false,
            hasFreeformFeature = false,
            currentWidthPx = 1920,
            currentHeightPx = 1080,
            maxWidthPx = 1920,
            maxHeightPx = 1080,
            previouslyDetectedWindowed = true,
            isCurrentlyFullscreen = true
        )
        assertTrue(state.showFullscreenButton)
        assertTrue(state.isFullscreen)
    }
}
