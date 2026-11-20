package com.qemusdl.app.sdl

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SdlScancodeMapperTest {

    @Test
    fun mapsCommonAndroidKeycodesToUsbHidSdlScancodes() {
        assertEquals(SdlScancodeMapper.SCANCODE_A, SdlScancodeMapper.androidKeyCodeToSdlScancode(KeyEvent.KEYCODE_A))
        assertEquals(SdlScancodeMapper.SCANCODE_RETURN, SdlScancodeMapper.androidKeyCodeToSdlScancode(KeyEvent.KEYCODE_ENTER))
        assertEquals(SdlScancodeMapper.SCANCODE_ESCAPE, SdlScancodeMapper.androidKeyCodeToSdlScancode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(SdlScancodeMapper.SCANCODE_LCTRL, SdlScancodeMapper.androidKeyCodeToSdlScancode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(SdlScancodeMapper.SCANCODE_F12, SdlScancodeMapper.androidKeyCodeToSdlScancode(KeyEvent.KEYCODE_F12))
    }

    @Test
    fun mapsAsciiCharactersToSdlScancodes() {
        assertEquals(SdlScancodeMapper.SCANCODE_A, SdlScancodeMapper.asciiCharToSdlScancode('a'))
        assertEquals(SdlScancodeMapper.SCANCODE_A, SdlScancodeMapper.asciiCharToSdlScancode('A'))
        assertEquals(SdlScancodeMapper.SCANCODE_SPACE, SdlScancodeMapper.asciiCharToSdlScancode(' '))
        assertEquals(SdlScancodeMapper.SCANCODE_RETURN, SdlScancodeMapper.asciiCharToSdlScancode('\n'))
    }
}
