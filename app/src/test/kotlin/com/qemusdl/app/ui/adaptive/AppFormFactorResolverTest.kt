package com.qemusdl.app.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFormFactorResolverTest {

    @Test
    fun resolve_portraitPhone_returnsPhone() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 412,
            isDesktopOs = false,
            hasPhysicalKeyboard = false,
            hasFinePointer = false
        )
        assertEquals(AppFormFactor.PHONE, result)
    }

    @Test
    fun resolve_narrowSnappedDesktopWindow_returnsPhone() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 480,
            isDesktopOs = true,
            hasPhysicalKeyboard = true,
            hasFinePointer = true
        )
        assertEquals(AppFormFactor.PHONE, result)
    }

    @Test
    fun resolve_unfoldedFoldable_returnsFoldableTablet() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 720,
            isDesktopOs = false,
            hasPhysicalKeyboard = false,
            hasFinePointer = false
        )
        assertEquals(AppFormFactor.FOLDABLE_TABLET, result)
    }

    @Test
    fun resolve_largeTouchTablet_returnsFoldableTablet() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 1024,
            isDesktopOs = false,
            hasPhysicalKeyboard = false,
            hasFinePointer = false
        )
        assertEquals(AppFormFactor.FOLDABLE_TABLET, result)
    }

    @Test
    fun resolve_googlebookOsLaptop_returnsDesktop() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 1280,
            isDesktopOs = true,
            hasPhysicalKeyboard = true,
            hasFinePointer = true
        )
        assertEquals(AppFormFactor.DESKTOP, result)
    }

    @Test
    fun resolve_googlebookOsMediumWindow_returnsDesktop() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 700,
            isDesktopOs = true,
            hasPhysicalKeyboard = true,
            hasFinePointer = true
        )
        assertEquals(AppFormFactor.DESKTOP, result)
    }

    @Test
    fun resolve_dockedTabletWithKeyboardAndMouse_returnsDesktop() {
        val result = AppFormFactorResolver.resolve(
            windowWidthDp = 1100,
            isDesktopOs = false,
            hasPhysicalKeyboard = true,
            hasFinePointer = true
        )
        assertEquals(AppFormFactor.DESKTOP, result)
    }
}
