package com.qemusdl.app.sdl

import android.view.KeyEvent

/**
 * Maps Android KeyEvent keycodes and ASCII characters to USB HID / SDL2 scancodes (SDL_Scancode).
 */
object SdlScancodeMapper {

    // Standard SDL_Scancode constants (USB HID Usage Page 0x07)
    const val SCANCODE_UNKNOWN = 0
    const val SCANCODE_A = 4
    const val SCANCODE_1 = 30
    const val SCANCODE_0 = 39
    const val SCANCODE_RETURN = 40
    const val SCANCODE_ESCAPE = 41
    const val SCANCODE_BACKSPACE = 42
    const val SCANCODE_TAB = 43
    const val SCANCODE_SPACE = 44
    const val SCANCODE_MINUS = 45
    const val SCANCODE_EQUALS = 46
    const val SCANCODE_LEFTBRACKET = 47
    const val SCANCODE_RIGHTBRACKET = 48
    const val SCANCODE_BACKSLASH = 49
    const val SCANCODE_SEMICOLON = 51
    const val SCANCODE_APOSTROPHE = 52
    const val SCANCODE_GRAVE = 53
    const val SCANCODE_COMMA = 54
    const val SCANCODE_PERIOD = 55
    const val SCANCODE_SLASH = 56
    const val SCANCODE_CAPSLOCK = 57
    const val SCANCODE_F1 = 58
    const val SCANCODE_F2 = 59
    const val SCANCODE_F3 = 60
    const val SCANCODE_F4 = 61
    const val SCANCODE_F5 = 62
    const val SCANCODE_F6 = 63
    const val SCANCODE_F7 = 64
    const val SCANCODE_F8 = 65
    const val SCANCODE_F9 = 66
    const val SCANCODE_F10 = 67
    const val SCANCODE_F11 = 68
    const val SCANCODE_F12 = 69
    const val SCANCODE_INSERT = 73
    const val SCANCODE_HOME = 74
    const val SCANCODE_PAGEUP = 75
    const val SCANCODE_DELETE = 76
    const val SCANCODE_END = 77
    const val SCANCODE_PAGEDOWN = 78
    const val SCANCODE_RIGHT = 79
    const val SCANCODE_LEFT = 80
    const val SCANCODE_DOWN = 81
    const val SCANCODE_UP = 82
    const val SCANCODE_LCTRL = 224
    const val SCANCODE_LSHIFT = 225
    const val SCANCODE_LALT = 226
    const val SCANCODE_LGUI = 227
    const val SCANCODE_RCTRL = 228
    const val SCANCODE_RSHIFT = 229
    const val SCANCODE_RALT = 230
    const val SCANCODE_RGUI = 231

    fun androidKeyCodeToSdlScancode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            SCANCODE_A + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 ->
            SCANCODE_1 + (keyCode - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> SCANCODE_0
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> SCANCODE_RETURN
        KeyEvent.KEYCODE_ESCAPE -> SCANCODE_ESCAPE
        KeyEvent.KEYCODE_DEL -> SCANCODE_BACKSPACE
        KeyEvent.KEYCODE_TAB -> SCANCODE_TAB
        KeyEvent.KEYCODE_SPACE -> SCANCODE_SPACE
        KeyEvent.KEYCODE_MINUS -> SCANCODE_MINUS
        KeyEvent.KEYCODE_EQUALS -> SCANCODE_EQUALS
        KeyEvent.KEYCODE_LEFT_BRACKET -> SCANCODE_LEFTBRACKET
        KeyEvent.KEYCODE_RIGHT_BRACKET -> SCANCODE_RIGHTBRACKET
        KeyEvent.KEYCODE_BACKSLASH -> SCANCODE_BACKSLASH
        KeyEvent.KEYCODE_SEMICOLON -> SCANCODE_SEMICOLON
        KeyEvent.KEYCODE_APOSTROPHE -> SCANCODE_APOSTROPHE
        KeyEvent.KEYCODE_GRAVE -> SCANCODE_GRAVE
        KeyEvent.KEYCODE_COMMA -> SCANCODE_COMMA
        KeyEvent.KEYCODE_PERIOD -> SCANCODE_PERIOD
        KeyEvent.KEYCODE_SLASH -> SCANCODE_SLASH
        KeyEvent.KEYCODE_CAPS_LOCK -> SCANCODE_CAPSLOCK
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            SCANCODE_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_INSERT -> SCANCODE_INSERT
        KeyEvent.KEYCODE_MOVE_HOME -> SCANCODE_HOME
        KeyEvent.KEYCODE_PAGE_UP -> SCANCODE_PAGEUP
        KeyEvent.KEYCODE_FORWARD_DEL -> SCANCODE_DELETE
        KeyEvent.KEYCODE_MOVE_END -> SCANCODE_END
        KeyEvent.KEYCODE_PAGE_DOWN -> SCANCODE_PAGEDOWN
        KeyEvent.KEYCODE_DPAD_RIGHT -> SCANCODE_RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> SCANCODE_LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> SCANCODE_DOWN
        KeyEvent.KEYCODE_DPAD_UP -> SCANCODE_UP
        KeyEvent.KEYCODE_CTRL_LEFT -> SCANCODE_LCTRL
        KeyEvent.KEYCODE_SHIFT_LEFT -> SCANCODE_LSHIFT
        KeyEvent.KEYCODE_ALT_LEFT -> SCANCODE_LALT
        KeyEvent.KEYCODE_META_LEFT -> SCANCODE_LGUI
        KeyEvent.KEYCODE_CTRL_RIGHT -> SCANCODE_RCTRL
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SCANCODE_RSHIFT
        KeyEvent.KEYCODE_ALT_RIGHT -> SCANCODE_RALT
        KeyEvent.KEYCODE_META_RIGHT -> SCANCODE_RGUI
        else -> SCANCODE_UNKNOWN
    }

    fun asciiCharToSdlScancode(ch: Char): Int = when (ch) {
        in 'a'..'z' -> SCANCODE_A + (ch - 'a')
        in 'A'..'Z' -> SCANCODE_A + (ch - 'A')
        in '1'..'9' -> SCANCODE_1 + (ch - '1')
        '0' -> SCANCODE_0
        '\n', '\r' -> SCANCODE_RETURN
        '\t' -> SCANCODE_TAB
        ' ' -> SCANCODE_SPACE
        '-', '_' -> SCANCODE_MINUS
        '=', '+' -> SCANCODE_EQUALS
        '[', '{' -> SCANCODE_LEFTBRACKET
        ']', '}' -> SCANCODE_RIGHTBRACKET
        '\\', '|' -> SCANCODE_BACKSLASH
        ';', ':' -> SCANCODE_SEMICOLON
        '\'', '"' -> SCANCODE_APOSTROPHE
        '`', '~' -> SCANCODE_GRAVE
        ',', '<' -> SCANCODE_COMMA
        '.', '>' -> SCANCODE_PERIOD
        '/', '?' -> SCANCODE_SLASH
        else -> SCANCODE_UNKNOWN
    }
}
