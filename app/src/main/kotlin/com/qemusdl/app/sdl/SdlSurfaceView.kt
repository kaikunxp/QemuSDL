package com.qemusdl.app.sdl

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.qemusdl.app.data.MouseEmulationMode
import kotlin.math.abs

/**
 * Native SDL2 / OpenGL ES 3.0 SurfaceView host for QEMU x86_64 guest display output.
 * Streams ANativeWindow directly to libqemusdl_bridge.so and translates Android touch,
 * mouse, trackpad, hardware keyboard, and IME soft keyboard events into SDL2 events.
 */
class SdlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var mouseEmulationMode: MouseEmulationMode = MouseEmulationMode.TRACKPAD_RELATIVE
    var trackpadSensitivity: Float = 1.4f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var downTimeMs = 0L
    private var maxPointersDuringGesture = 0
    private var movedBeyondTapSlop = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        NativeQemuBridge.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeQemuBridge.resizeSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeQemuBridge.detachSurface()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasFocus()) requestFocus()

        // Physical mouse/stylus buttons routed directly
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val nx = if (width > 0) (event.x / width).coerceIn(0f, 1f) else 0.5f
            val ny = if (height > 0) (event.y / height).coerceIn(0f, 1f) else 0.5f
            NativeQemuBridge.sendMouseMove(nx, ny, 0f, 0f, false)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> NativeQemuBridge.sendMouseButton(1, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeQemuBridge.sendMouseButton(1, false)
            }
            return true
        }

        when (mouseEmulationMode) {
            MouseEmulationMode.DIRECT_TOUCH -> handleDirectTouch(event)
            MouseEmulationMode.TRACKPAD_RELATIVE -> handleTrackpadTouch(event)
            MouseEmulationMode.DIRECT_HOVER_TAP -> handleDirectHoverTapTouch(event)
        }
        return true
    }

    private fun handleDirectTouch(event: MotionEvent) {
        val nx = if (width > 0) (event.x / width).coerceIn(0f, 1f) else 0.5f
        val ny = if (height > 0) (event.y / height).coerceIn(0f, 1f) else 0.5f
        NativeQemuBridge.sendMouseMove(nx, ny, 0f, 0f, false)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> NativeQemuBridge.sendMouseButton(1, true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeQemuBridge.sendMouseButton(1, false)
        }
    }

    private fun handleDirectHoverTapTouch(event: MotionEvent) {
        val nx = if (width > 0) (event.x / width).coerceIn(0f, 1f) else 0.5f
        val ny = if (height > 0) (event.y / height).coerceIn(0f, 1f) else 0.5f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTouchX = event.x
                downTouchY = event.y
                downTimeMs = event.eventTime
                maxPointersDuringGesture = 1
                movedBeyondTapSlop = false
                NativeQemuBridge.sendMouseMove(nx, ny, 0f, 0f, false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersDuringGesture = maxOf(maxPointersDuringGesture, event.pointerCount)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downTouchX) > 14f || abs(event.y - downTouchY) > 14f) {
                    movedBeyondTapSlop = true
                }
                NativeQemuBridge.sendMouseMove(nx, ny, 0f, 0f, false)
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTimeMs
                if (!movedBeyondTapSlop && duration < 280L) {
                    val button = if (maxPointersDuringGesture >= 2) 3 else 1
                    NativeQemuBridge.sendMouseButton(button, true)
                    postDelayed({ NativeQemuBridge.sendMouseButton(button, false) }, 45L)
                }
            }
        }
    }

    private fun handleTrackpadTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y
                downTimeMs = event.eventTime
                maxPointersDuringGesture = 1
                movedBeyondTapSlop = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersDuringGesture = maxOf(maxPointersDuringGesture, event.pointerCount)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - lastTouchX) * trackpadSensitivity
                val dy = (event.y - lastTouchY) * trackpadSensitivity
                lastTouchX = event.x
                lastTouchY = event.y

                if (abs(event.x - downTouchX) > 14f || abs(event.y - downTouchY) > 14f) {
                    movedBeyondTapSlop = true
                }

                if (event.pointerCount >= 2) {
                    // Two-finger scroll maps to SDL mouse wheel
                    NativeQemuBridge.sendMouseWheel(dx * 0.08f, -dy * 0.08f)
                } else {
                    NativeQemuBridge.sendMouseMove(0f, 0f, dx, dy, true)
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTimeMs
                if (!movedBeyondTapSlop && duration < 280L) {
                    val button = if (maxPointersDuringGesture >= 2) 3 else 1
                    NativeQemuBridge.sendMouseButton(button, true)
                    postDelayed({ NativeQemuBridge.sendMouseButton(button, false) }, 45L)
                }
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val nx = if (width > 0) (event.x / width).coerceIn(0f, 1f) else 0.5f
                    val ny = if (height > 0) (event.y / height).coerceIn(0f, 1f) else 0.5f
                    NativeQemuBridge.sendMouseMove(nx, ny, 0f, 0f, false)
                    return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    NativeQemuBridge.sendMouseWheel(hScroll, vScroll)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyDown(keyCode, event)
        }
        val scancode = SdlScancodeMapper.androidKeyCodeToSdlScancode(keyCode)
        val unicode = event.unicodeChar
        if (scancode != SdlScancodeMapper.SCANCODE_UNKNOWN || unicode != 0) {
            NativeQemuBridge.sendKeyEvent(scancode, keyCode, unicode, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyUp(keyCode, event)
        }
        val scancode = SdlScancodeMapper.androidKeyCodeToSdlScancode(keyCode)
        val unicode = event.unicodeChar
        if (scancode != SdlScancodeMapper.SCANCODE_UNKNOWN || unicode != 0) {
            NativeQemuBridge.sendKeyEvent(scancode, keyCode, unicode, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return SdlInputConnection(this)
    }

    private class SdlInputConnection(targetView: SdlSurfaceView) : BaseInputConnection(targetView, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) {
                for (ch in text) {
                    val scancode = SdlScancodeMapper.asciiCharToSdlScancode(ch)
                    NativeQemuBridge.sendKeyEvent(scancode, 0, ch.code, true)
                    NativeQemuBridge.sendKeyEvent(scancode, 0, ch.code, false)
                }
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtLeast(1)) {
                NativeQemuBridge.tapScancode(SdlScancodeMapper.SCANCODE_BACKSPACE)
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val scancode = SdlScancodeMapper.androidKeyCodeToSdlScancode(event.keyCode)
            if (scancode != SdlScancodeMapper.SCANCODE_UNKNOWN) {
                NativeQemuBridge.sendKeyEvent(
                    scancode,
                    event.keyCode,
                    event.unicodeChar,
                    event.action == KeyEvent.ACTION_DOWN
                )
                return true
            }
            return super.sendKeyEvent(event)
        }
    }
}
