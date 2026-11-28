package com.qemusdl.app.sdl

import android.content.Context
import android.view.Surface
import com.qemusdl.app.data.GuestArchitecture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RuntimeProbeStatus(
    val abi: String = "unknown",
    val bridgeLoaded: Boolean = false,
    val sdl2Loaded: Boolean = false,
    val qemuLoaded: Boolean = false,
    val qemuAarch64Loaded: Boolean = false,
    val qemuPpcLoaded: Boolean = false,
    val hasQemuEntry: Boolean = false,
    val hasLibusb: Boolean = false,
    val hasLibusbWrap: Boolean = false,
    val sdl2Path: String = "",
    val qemuPath: String = "",
    val qemuAarch64Path: String = "",
    val qemuPpcPath: String = "",
    val detail: String = "Native bridge not initialized"
) {
    val isFullQemuReady: Boolean
        get() = bridgeLoaded && sdl2Loaded && (qemuLoaded || qemuAarch64Loaded || qemuPpcLoaded) && hasQemuEntry

    val isBothArchsReady: Boolean
        get() = bridgeLoaded && sdl2Loaded && qemuLoaded && qemuAarch64Loaded && hasQemuEntry

    val isAllArchsReady: Boolean
        get() = bridgeLoaded && sdl2Loaded && qemuLoaded && qemuAarch64Loaded && qemuPpcLoaded && hasQemuEntry

    fun isReadyForArch(arch: GuestArchitecture): Boolean {
        if (!bridgeLoaded || !sdl2Loaded || !hasQemuEntry) return false
        return when (arch) {
            GuestArchitecture.X86_64 -> qemuLoaded
            GuestArchitecture.AARCH64 -> qemuAarch64Loaded
            GuestArchitecture.PPC -> qemuPpcLoaded
        }
    }

    val readyArchLabels: List<String>
        get() = buildList {
            if (qemuLoaded) add("x86_64")
            if (qemuAarch64Loaded) add("AArch64")
            if (qemuPpcLoaded) add("PPC")
        }
}

@Serializable
data class ViewportTelemetry(
    val fps: Double = 0.0,
    val frameTimeMs: Double = 0.0,
    val frameCount: Long = 0L,
    val guestWidth: Int = 800,
    val guestHeight: Int = 600,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val glRenderer: String = "OpenGL ES 3.0",
    val engineMode: String = "GLES3_SDL2_FALLBACK",
    val logs: List<String> = emptyList()
)

/**
 * JNI bridge to libqemusdl_bridge.so, orchestrating the native OpenGL ES 3.0 / SDL2
 * viewport and in-process libqemu-system-x86_64.so execution.
 */
object NativeQemuBridge {

    private val json = Json { ignoreUnknownKeys = true }

    val isBridgeLoaded: Boolean

    init {
        isBridgeLoaded = try {
            System.loadLibrary("qemusdl_bridge")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun customLibDirectory(context: Context): File =
        File(context.filesDir, "native_libs").apply { mkdirs() }

    fun probeRuntime(context: Context): RuntimeProbeStatus {
        if (!isBridgeLoaded) {
            return RuntimeProbeStatus(
                bridgeLoaded = false,
                detail = "libqemusdl_bridge.so could not be loaded on this ABI."
            )
        }
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        val customLibDir = customLibDirectory(context).absolutePath
        return runCatching {
            val raw = nativeProbeRuntime(nativeLibDir, customLibDir)
            json.decodeFromString<RuntimeProbeStatus>(raw)
        }.getOrElse { err ->
            RuntimeProbeStatus(
                bridgeLoaded = true,
                detail = "Probe error: ${err.message}"
            )
        }
    }

    fun startSession(
        context: Context,
        vmName: String,
        guestWidth: Int,
        guestHeight: Int,
        qemuArgs: List<String>
    ): Boolean {
        if (!isBridgeLoaded) return false
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        val customLibDir = customLibDirectory(context).absolutePath
        return nativeStartSession(
            vmName = vmName,
            guestWidth = guestWidth,
            guestHeight = guestHeight,
            qemuArgs = qemuArgs.toTypedArray(),
            nativeLibDir = nativeLibDir,
            customLibDir = customLibDir
        )
    }

    fun attachSurface(surface: Surface) {
        if (isBridgeLoaded) nativeAttachSurface(surface)
    }

    fun resizeSurface(width: Int, height: Int) {
        if (isBridgeLoaded) nativeResizeSurface(width, height)
    }

    fun detachSurface() {
        if (isBridgeLoaded) nativeDetachSurface()
    }

    fun sendKeyEvent(sdlScancode: Int, androidKeycode: Int, unicodeChar: Int, isDown: Boolean) {
        if (isBridgeLoaded) nativeSendKeyEvent(sdlScancode, androidKeycode, unicodeChar, isDown)
    }

    fun tapScancode(sdlScancode: Int, unicodeChar: Int = 0) {
        sendKeyEvent(sdlScancode, 0, unicodeChar, true)
        sendKeyEvent(sdlScancode, 0, unicodeChar, false)
    }

    fun sendKeyCombo(vararg scancodes: Int) {
        scancodes.forEach { sendKeyEvent(it, 0, 0, true) }
        scancodes.reversed().forEach { sendKeyEvent(it, 0, 0, false) }
    }

    fun sendMouseMove(normX: Float, normY: Float, dx: Float, dy: Float, isRelative: Boolean) {
        if (isBridgeLoaded) nativeSendMouseMove(normX, normY, dx, dy, isRelative)
    }

    fun sendMouseButton(button: Int, isDown: Boolean) {
        if (isBridgeLoaded) nativeSendMouseButton(button, isDown)
    }

    fun sendMouseWheel(dx: Float, dy: Float) {
        if (isBridgeLoaded) nativeSendMouseWheel(dx, dy)
    }

    fun setScalingMode(mode: Int) {
        if (isBridgeLoaded) nativeSetScalingMode(mode)
    }

    fun setPaused(paused: Boolean) {
        if (isBridgeLoaded) nativeSetPaused(paused)
    }

    fun resetVm() {
        if (isBridgeLoaded) nativeResetVm()
    }

    fun stopSession() {
        if (isBridgeLoaded) nativeStopSession()
    }

    fun getTelemetry(): ViewportTelemetry {
        if (!isBridgeLoaded) return ViewportTelemetry()
        return runCatching {
            json.decodeFromString<ViewportTelemetry>(nativeGetTelemetryJson())
        }.getOrDefault(ViewportTelemetry())
    }

    /**
     * Queries the active in-process SDL2 window & renderer to ensure SDL2's window size matches
     * [surfaceWidth] x [surfaceHeight] and returns the live guest framebuffer logical size packed
     * as `(logicalWidth.toLong() shl 32) or (logicalHeight.toLong() and 0xFFFFFFFFL)`, or `0L`
     * if the SDL2 renderer is not yet initialized.
     */
    @JvmStatic
    fun syncSdlViewport(surfaceWidth: Int, surfaceHeight: Int): Long {
        if (!isBridgeLoaded) return 0L
        return runCatching {
            nativeSyncSdlViewport(surfaceWidth, surfaceHeight)
        }.getOrDefault(0L)
    }

    /**
     * Pushes an `SDL_WINDOWEVENT_EXPOSED` event to the active in-process SDL2 window so QEMU
     * immediately redraws the guest framebuffer after returning from background / PiP / rotation.
     */
    @JvmStatic
    fun notifySdlExposed() {
        if (!isBridgeLoaded) return
        runCatching {
            nativeNotifySdlExposed()
        }
    }

    @JvmStatic
    fun enableStdioLogcat() {
        if (!isBridgeLoaded) return
        runCatching {
            nativeEnableStdioLogcat()
        }
    }

    private external fun nativeProbeRuntime(nativeLibDir: String, customLibDir: String): String

    private external fun nativeStartSession(
        vmName: String,
        guestWidth: Int,
        guestHeight: Int,
        qemuArgs: Array<String>,
        nativeLibDir: String,
        customLibDir: String
    ): Boolean

    private external fun nativeAttachSurface(surface: Surface)
    private external fun nativeResizeSurface(width: Int, height: Int)
    private external fun nativeDetachSurface()

    private external fun nativeSendKeyEvent(
        sdlScancode: Int,
        androidKeycode: Int,
        unicodeChar: Int,
        isDown: Boolean
    )

    private external fun nativeSendMouseMove(
        normX: Float,
        normY: Float,
        dx: Float,
        dy: Float,
        isRelative: Boolean
    )

    private external fun nativeSendMouseButton(button: Int, isDown: Boolean)
    private external fun nativeSendMouseWheel(dx: Float, dy: Float)
    private external fun nativeSetScalingMode(mode: Int)
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeResetVm()
    private external fun nativeStopSession()
    private external fun nativeGetTelemetryJson(): String
    private external fun nativeSyncSdlViewport(surfaceWidth: Int, surfaceHeight: Int): Long
    private external fun nativeNotifySdlExposed()
    private external fun nativeEnableStdioLogcat()
}
