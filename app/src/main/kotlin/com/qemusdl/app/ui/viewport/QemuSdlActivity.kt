package com.qemusdl.app.ui.viewport

import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import com.qemusdl.app.usb.ActiveUsbPassthroughSession
import com.qemusdl.app.usb.UsbPassthroughManager
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.qemusdl.app.MainActivity
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.qemu.GuestAgentSnapshot
import com.qemusdl.app.qemu.GuestSharedFolderServer
import com.qemusdl.app.qemu.IsoDownloader
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.qemu.QemuFirmwareManager
import com.qemusdl.app.qemu.QemuGuestAgentClient
import com.qemusdl.app.sdl.NativeQemuBridge
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import com.qemusdl.app.ui.theme.QemuSDLTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.libsdl.app.SDLActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class VirtualCursorUiState(
    val screenX: Float = 640f,
    val screenY: Float = 360f,
    val visible: Boolean = true,
    val leftDown: Boolean = false,
    val rightDown: Boolean = false
)

/**
 * Full native SDL2 Activity running in-process `libqemu-system-x86_64.so` directly onto
 * SDL2's Android `SDLSurface` (zero VNC), hosted in an isolated `:qemu_vm` process so
 * guest shutdown cleanly reclaims all memory without exiting the main app.
 */
class QemuSdlActivity : SDLActivity() {

    private val json = Json { ignoreUnknownKeys = true }
    private val openDescriptors = mutableListOf<ParcelFileDescriptor>()
    private val activeUsbSessions = mutableListOf<ActiveUsbPassthroughSession>()
    private var vmConfig: VmConfig = VmConfig(name = "QEMU x86_64")
    private var qemuFullArgs: List<String> = emptyList()
    private var firmwareDirPath: String? = null
    private var cursorUiState by mutableStateOf(VirtualCursorUiState())
    private var windowingState by mutableStateOf(WindowingEnvironmentState())
    private var viewportPosture by mutableStateOf(ViewportPostureState())
    private var viewportBounds by mutableStateOf(ViewportBoundsState())
    private var isInPipMode by mutableStateOf(false)
    private var lastWindowLayoutInfo: WindowLayoutInfo? = null
    private var appliedTabletopHeightPx: Int = -1
    private var pointerCaptured by mutableStateOf(false)
    private var hasEverDetectedWindowed = false
    private var resolvedVirtioIsoName by mutableStateOf<String?>(null)
    private var qgaSocketFile: File? = null
    private var guestAgentClient: QemuGuestAgentClient? = null
    private var guestAgentSnapshot by mutableStateOf(GuestAgentSnapshot())
    private var guestAgentActionMessage by mutableStateOf<String?>(null)
    private var hasAutoSyncedGuestClock = false
    private var sharedFolderServer: GuestSharedFolderServer? = null
    private var sharedFolderGuestUrl by mutableStateOf<String?>(null)
    private var sharedFolderHostPath by mutableStateOf<String?>(null)

    override fun getLibraries(): Array<String> {
        // Parse architecture early if getLibraries() is invoked during super.onCreate()
        val targetArch = resolveTargetConfig().architecture
        val libs = mutableListOf<String>()
        val hasLibusb = File(applicationInfo.nativeLibraryDir, "libusb-1.0.so").exists()
        if (hasLibusb) {
            libs += "usb-1.0"
        }
        val hasEpoxy = File(applicationInfo.nativeLibraryDir, "libepoxy.so").exists()
        if (hasEpoxy) {
            libs += "epoxy"
        }
        val hasVirgl = File(applicationInfo.nativeLibraryDir, "libvirglrenderer.so").exists()
        if (hasVirgl) {
            libs += "virglrenderer"
        }
        libs += "SDL2"
        // Dynamically load only the selected architecture's QEMU shared object so x86_64 and aarch64 symbols never collide
        libs += targetArch.soShortLibName
        libs += "qemusdl_bridge"
        return libs.toTypedArray()
    }

    override fun getMainSharedObject(): String {
        val targetArch = resolveTargetConfig().architecture
        return "${applicationInfo.nativeLibraryDir}/${targetArch.soFileName}"
    }

    override fun getMainFunction(): String = "SDL_main"

    override fun getArguments(): Array<String> =
        qemuFullArgs.drop(1).toTypedArray()

    private fun computeRecommendedTcgTbSizeMb(): Int {
        val totalRamBytes = runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            memInfo.totalMem
        }.getOrDefault(0L)
        val totalRamGb = totalRamBytes / (1024L * 1024L * 1024L)
        return when {
            totalRamGb >= 8L -> 256
            totalRamGb >= 6L -> 192
            else -> QemuCommandBuilder.DEFAULT_TCG_TB_SIZE_MB
        }
    }

    private fun resolveTargetConfig(): VmConfig {
        intent?.getStringExtra(EXTRA_VM_CONFIG_JSON)?.let { raw ->
            runCatching { json.decodeFromString<VmConfig>(raw) }.getOrNull()?.let { parsed ->
                return parsed
            }
        }
        return vmConfig
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Parse VM configuration and prepare firmware + SAF file descriptors BEFORE super.onCreate()
        // because SDLActivity starts loading libraries and preparing SDLMain during onCreate.
        vmConfig = resolveTargetConfig()

        firmwareDirPath = QemuFirmwareManager.ensureFirmwareExtracted(this)?.absolutePath
        val pflashFiles = if (vmConfig.architecture == com.qemusdl.app.data.GuestArchitecture.AARCH64) {
            QemuFirmwareManager.prepareAarch64PflashFiles(this, vmConfig.id)
        } else {
            null
        }
        val resolvedDisk = resolveMediaPath(vmConfig.diskPathOrUri, readWrite = true)
        val resolvedIso = resolveMediaPath(vmConfig.isoPathOrUri, readWrite = false)
        val resolvedVirtioIso = if (vmConfig.attachVirtioDriversIso && vmConfig.architecture != GuestArchitecture.PPC) {
            resolveMediaPath(vmConfig.virtioDriversIsoPath, readWrite = false)
                ?: IsoDownloader.findVirtioDriversIso(this)?.absolutePath
        } else {
            null
        }
        resolvedVirtioIsoName = resolvedVirtioIso?.let { File(it).name }

        // Prepare QEMU Guest Agent Unix socket (virtio-serial-pci -> org.qemu.guest_agent.0)
        if (vmConfig.guestAgentEnabled && vmConfig.architecture != GuestArchitecture.PPC) {
            val safeId = vmConfig.id.filter { it.isLetterOrDigit() }.take(12).ifEmpty { "vm" }
            val sockFile = File(cacheDir, "qga_$safeId.sock")
            if (sockFile.exists()) {
                sockFile.delete()
            }
            qgaSocketFile = sockFile
            guestAgentClient = QemuGuestAgentClient(sockFile.absolutePath)
            guestAgentSnapshot = GuestAgentSnapshot(enabled = true)
        } else {
            guestAgentSnapshot = GuestAgentSnapshot(enabled = false)
        }

        // Prepare shared host folder for WebDAV/HTTP and/or VirtIO-9p (hostshare)
        val virtio9pActive = vmConfig.virtio9pEnabled && vmConfig.architecture != GuestArchitecture.PPC
        val sharedDir = if (vmConfig.sharedFolderEnabled || virtio9pActive) {
            GuestSharedFolderServer.defaultSharedDirectory(this).also { dir ->
                dir.mkdirs()
                sharedFolderHostPath = dir.absolutePath
            }
        } else {
            null
        }
        if (vmConfig.sharedFolderEnabled && sharedDir != null) {
            val server = GuestSharedFolderServer(sharedDir, vmConfig.sharedFolderPort)
            runCatching { server.start() }
                .onSuccess {
                    sharedFolderServer = server
                    sharedFolderGuestUrl = server.guestUrl
                }
        }
        val virtio9pPath = if (virtio9pActive) sharedDir?.absolutePath else null

        // Open permitted USB passthrough devices via UsbManager and extract file descriptors
        val usbManager = UsbPassthroughManager(this)
        val activeUsbFds = mutableMapOf<String, Int>()
        for (devConfig in vmConfig.usbPassthroughDevices.filter { it.enabled }) {
            val dev = usbManager.findDevice(devConfig.vendorId, devConfig.productId)
            if (dev != null && usbManager.hasPermission(dev)) {
                val session = usbManager.openSession(dev)
                if (session != null) {
                    activeUsbSessions += session
                    activeUsbFds["${devConfig.vendorIdHex}:${devConfig.productIdHex}"] = session.fileDescriptor
                    activeUsbFds[devConfig.deviceName] = session.fileDescriptor
                }
            }
        }

        qemuFullArgs = QemuCommandBuilder.buildArgs(
            config = vmConfig,
            resolvedDiskPath = resolvedDisk,
            resolvedIsoPath = resolvedIso,
            resolvedVirtioIsoPath = resolvedVirtioIso,
            guestAgentSocketPath = qgaSocketFile?.absolutePath,
            virtio9pHostPath = virtio9pPath,
            firmwareDir = firmwareDirPath,
            edk2CodePath = pflashFiles?.first?.absolutePath,
            edk2VarsPath = pflashFiles?.second?.absolutePath,
            activeUsbDeviceFds = activeUsbFds,
            tcgTbSizeMb = computeRecommendedTcgTbSizeMb()
        )

        // Configure SDL2 video/render/audio hints, display auto-resize, and Android upstream SLIRP DNS servers
        // prior to libSDL2.so + libqemu-system-x86_64.so initialization
        val activeDnsCsv = resolveAndroidDnsServersCsv()
        val autoResizeEnabled = vmConfig.dynamicDisplayResize && vmConfig.architecture != GuestArchitecture.PPC
        runCatching {
            Os.setenv("SDL_RENDER_DRIVER", if (vmConfig.sdlGlAcceleration) "opengles2" else "software", true)
            Os.setenv("SDL_AUDIODRIVER", "openslES", true)
            Os.setenv("SDL_HINT_ANDROID_TRAP_BACK_BUTTON", "1", true)
            Os.setenv("SDL_HINT_TOUCH_MOUSE_EVENTS", "0", true)
            // Allow all orientations so SDL2 never locks compact phone screens to a single orientation
            Os.setenv("SDL_IOS_ORIENTATIONS", "LandscapeLeft LandscapeRight Portrait PortraitUpsideDown", true)
            // Safely block QEMU's SDLThread when the Activity actually stops / loses its Surface
            // (transient focus loss from HUD sheets/menus/PiP/split-screen does NOT pause SDL) so
            // GLES never renders against a destroyed EGLSurface and cleanly restores upon return.
            Os.setenv("SDL_ANDROID_BLOCK_ON_PAUSE", "1", true)
            Os.setenv("QEMU_SLIRP_DNS", activeDnsCsv, true)
            Os.setenv("QEMU_SDL_AUTO_RESIZE", if (autoResizeEnabled) "1" else "0", true)
            Os.setenv("QEMU_SDL_MAX_RESIZE_DIM", "1600", true)
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isSustainedPerformanceModeSupported == true) {
                window.setSustainedPerformanceMode(true)
            }
        }

        val initialWindowing = DesktopWindowingHelper.inspect(this)
        hasEverDetectedWindowed = initialWindowing.showFullscreenButton
        val startFullscreen = !initialWindowing.showFullscreenButton
        applyFullscreenState(startFullscreen)

        // Apply initial viewport scaling, mouse emulation mode, pointer capture, and live cursor listener on SDLSurface
        val isRelativeGuest = !QemuCommandBuilder.usesAbsolutePointer(vmConfig)
        mSurface?.setGuestResolution(vmConfig.resolution.width, vmConfig.resolution.height)
        mSurface?.setScalingMode(vmConfig.scalingMode.nativeCode)
        mSurface?.setRelativeGuestPointer(isRelativeGuest)
        mSurface?.setMouseEmulationMode(vmConfig.mouseEmulationMode.ordinal)
        mSurface?.setPointerCaptureListener { captured ->
            runOnUiThread {
                pointerCaptured = captured
            }
        }
        mSurface?.setCursorListener { sx, sy, visible, leftDown, rightDown ->
            runOnUiThread {
                cursorUiState = VirtualCursorUiState(
                    screenX = sx,
                    screenY = sy,
                    visible = visible,
                    leftDown = leftDown,
                    rightDown = rightDown
                )
            }
        }
        mSurface?.setViewportBoundsListener { left, top, right, bottom, surfW, surfH, guestW, guestH ->
            runOnUiThread {
                val prevGuestW = viewportBounds.guestWidth
                val prevGuestH = viewportBounds.guestHeight
                viewportBounds = ViewportBoundsState(
                    leftPx = left,
                    topPx = top,
                    rightPx = right,
                    bottomPx = bottom,
                    surfaceWidthPx = surfW,
                    surfaceHeightPx = surfH,
                    guestWidth = guestW,
                    guestHeight = guestH
                )
                if (guestW != prevGuestW || guestH != prevGuestH) {
                    updatePipParams(guestW, guestH)
                }
            }
        }

        updatePipParams(vmConfig.resolution.width, vmConfig.resolution.height)

        // Observe foldable posture (Tabletop half-folded horizontal hinge) via Jetpack WindowManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@QemuSdlActivity)
                    .windowLayoutInfo(this@QemuSdlActivity)
                    .collect { layoutInfo ->
                        lastWindowLayoutInfo = layoutInfo
                        val windowHeightPx = mLayout?.height?.takeIf { it > 0 }
                            ?: resources.displayMetrics.heightPixels
                        val resolved = FoldableViewportHelper.resolveTabletopPosture(
                            layoutInfo = layoutInfo,
                            windowHeightPx = windowHeightPx
                        )
                        if (resolved != viewportPosture) {
                            viewportPosture = resolved
                            updateSurfaceLayoutForPosture()
                        }
                    }
            }
        }

        // Poll QEMU Guest Agent in the background for live OS/IP telemetry and automatic clock sync
        guestAgentClient?.let { client ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        val prevSyncMs = guestAgentSnapshot.lastTimeSyncEpochMs
                        val updated: GuestAgentSnapshot = withContext(Dispatchers.IO) {
                            var snap = client.querySnapshot(prevSyncMs)
                            if (snap.connected && !hasAutoSyncedGuestClock) {
                                if (client.syncGuestTime()) {
                                    hasAutoSyncedGuestClock = true
                                    snap = snap.copy(lastTimeSyncEpochMs = System.currentTimeMillis())
                                }
                            }
                            snap
                        }
                        guestAgentSnapshot = updated
                        delay(if (updated.connected) 10_000L else 4_000L)
                    }
                }
            }
        }

        val repository = VmRepository.getInstance(this)

        // Overlay our Material 3 Expressive Compose HUD directly on top of SDLActivity.mLayout
        if (!mBrokenLibraries && mLayout != null) {
            mLayout.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val newW = right - left
                val newH = bottom - top
                val oldW = oldRight - oldLeft
                val oldH = oldBottom - oldTop
                if (newW > 0 && newH > 0 && (newW != oldW || newH != oldH)) {
                    refreshWindowingState()
                    lastWindowLayoutInfo?.let { info ->
                        val resolved = FoldableViewportHelper.resolveTabletopPosture(info, newH)
                        if (resolved != viewportPosture) {
                            viewportPosture = resolved
                            updateSurfaceLayoutForPosture()
                        }
                    }
                    mSurface?.post { mSurface?.requestViewportRescale() }
                }
            }
            val hudOverlay = ComposeView(this).apply {
                setContent {
                    QemuSDLTheme(darkTheme = true) {
                        QemuSdlOverlayHud(
                            vmConfig = vmConfig,
                            cursorState = cursorUiState,
                            viewportPosture = viewportPosture,
                            viewportBounds = viewportBounds,
                            isInPipMode = isInPipMode,
                            qemuCommandLine = qemuFullArgs.joinToString(" "),
                            firmwarePath = firmwareDirPath ?: "Not bundled",
                            showFullscreenButton = windowingState.showFullscreenButton,
                            isFullscreen = windowingState.isFullscreen,
                            isRelativeGuestPointer = isRelativeGuest,
                            isPointerCaptured = pointerCaptured,
                            onToggleFullscreen = { toggleFullscreen() },
                            onTogglePointerCapture = { mSurface?.togglePointerCapture() },
                            onEnterPip = { enterVmPictureInPicture() },
                            onScalingModeChanged = { newScale ->
                                vmConfig = vmConfig.copy(scalingMode = newScale)
                                mSurface?.setScalingMode(newScale.nativeCode)
                                lifecycleScope.launch { repository.upsertVm(vmConfig) }
                            },
                            onMouseEmulationModeChanged = { newMouseMode ->
                                vmConfig = vmConfig.copy(mouseEmulationMode = newMouseMode)
                                mSurface?.setMouseEmulationMode(newMouseMode.ordinal)
                                lifecycleScope.launch { repository.upsertVm(vmConfig) }
                            },
                            onTrackpadSensitivityChanged = { sensitivity ->
                                mSurface?.setTrackpadSensitivity(sensitivity)
                            },
                            onTrackpadDelta = { dx, dy ->
                                mSurface?.dispatchTrackpadDelta(dx, dy)
                            },
                            onTrackpadScroll = { dx, dy ->
                                mSurface?.dispatchTrackpadScroll(dx, dy)
                            },
                            onPerformClick = { rightClick ->
                                mSurface?.performVirtualClick(rightClick)
                            },
                            onVirtualMouseButton = { rightClick, pressed ->
                                mSurface?.setVirtualMouseButton(rightClick, pressed)
                            },
                            onShowKeyboard = {
                                SDLActivity.showTextInput(0, 0, 1, 1)
                            },
                            onKeyDown = { androidKeyCode ->
                                SDLActivity.onNativeKeyDown(androidKeyCode)
                            },
                            onKeyUp = { androidKeyCode ->
                                SDLActivity.onNativeKeyUp(androidKeyCode)
                            },
                            onSendShellCommand = { cmd ->
                                sendAsciiCommandToGuest(cmd)
                            },
                            activeDnsServers = activeDnsCsv,
                            guestAgentSnapshot = guestAgentSnapshot,
                            guestAgentActionMessage = guestAgentActionMessage,
                            virtioDriversIsoMountedName = resolvedVirtioIsoName,
                            sharedFolderGuestUrl = sharedFolderGuestUrl,
                            sharedFolderHostPath = sharedFolderHostPath,
                            onSyncGuestClock = {
                                val client = guestAgentClient ?: return@QemuSdlOverlayHud
                                lifecycleScope.launch {
                                    guestAgentActionMessage = "Syncing guest clock..."
                                    val ok = withContext(Dispatchers.IO) { client.syncGuestTime() }
                                    if (ok) {
                                        hasAutoSyncedGuestClock = true
                                        guestAgentSnapshot = guestAgentSnapshot.copy(
                                            lastTimeSyncEpochMs = System.currentTimeMillis()
                                        )
                                        guestAgentActionMessage = "Guest clock synced to host UTC"
                                    } else {
                                        guestAgentActionMessage = "Clock sync failed — is qemu-ga running?"
                                    }
                                }
                            },
                            onGracefulGuestShutdown = {
                                val client = guestAgentClient ?: return@QemuSdlOverlayHud
                                lifecycleScope.launch {
                                    guestAgentActionMessage = "Sending ACPI/QGA graceful shutdown..."
                                    val ok = withContext(Dispatchers.IO) { client.requestGracefulShutdown() }
                                    guestAgentActionMessage = if (ok) {
                                        "Shutdown requested — waiting for guest OS to halt cleanly"
                                    } else {
                                        "Guest agent unreachable — use Force Power Off if needed"
                                    }
                                }
                            },
                            onPowerOff = {
                                powerOffVmProcess()
                            }
                        )
                    }
                }
            }
            mLayout.addView(
                hudOverlay,
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            // F11 or Alt+Enter toggles true desktop fullscreen on GooglebookOS / ChromeOS / DeX
            if (event.keyCode == KeyEvent.KEYCODE_F11 ||
                (windowingState.showFullscreenButton && event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                toggleFullscreen()
                return true
            }
            // Ctrl+Alt+G toggles Android Pointer Capture (matches desktop QEMU mouse grab shortcut)
            if (event.isCtrlPressed && event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_G) {
                mSurface?.togglePointerCapture()
                return true
            }
            // Ctrl+Alt+P enters Picture-in-Picture mode for the running VM
            if (event.isCtrlPressed && event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_P) {
                enterVmPictureInPicture()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val vmViewportShortcuts = KeyboardShortcutGroup(
            "VM Viewport",
            listOf(
                KeyboardShortcutInfo("Toggle Full Screen", KeyEvent.KEYCODE_F11, 0),
                KeyboardShortcutInfo(
                    "Toggle Mouse Pointer Capture",
                    KeyEvent.KEYCODE_G,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                ),
                KeyboardShortcutInfo(
                    "Enter Picture-in-Picture",
                    KeyEvent.KEYCODE_P,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
                )
            )
        )
        data?.add(vmViewportShortcuts)
    }

    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        // QEMU creates an SDL_WINDOW_FULLSCREEN_DESKTOP window without SDL_WINDOW_RESIZABLE,
        // which causes SDLActivity's default setOrientationBis() to force SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // on compact phone screens (< 600dp). Keep orientation unspecified so phones, foldables,
        // and desktops honor Android's system auto-rotate freely in both portrait and landscape.
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedConfig = intent.getStringExtra(EXTRA_VM_CONFIG_JSON)?.let { raw ->
            runCatching { json.decodeFromString<VmConfig>(raw) }.getOrNull()
        }
        if (requestedConfig != null && requestedConfig.id != vmConfig.id) {
            // Switching to a different VM while another is still alive: ask MainActivity to
            // launch the target VM cleanly once this isolated :qemu_vm process exits.
            val launchAdjacent = intent.getBooleanExtra(EXTRA_LAUNCH_ADJACENT, false)
            startActivity(
                MainActivity.createRelaunchVmIntent(
                    context = this,
                    vmId = requestedConfig.id,
                    launchAdjacent = launchAdjacent
                )
            )
            powerOffVmProcess()
            return
        }
        // Returning to the already-running VM (from MainActivity, PiP, Split-Screen, or shortcut)
        refreshWindowingState()
        if (!isInPipMode && !windowingState.showFullscreenButton) {
            applyFullscreenState(true)
        }
        mSurface?.post {
            mSurface?.requestViewportRescale()
            NativeQemuBridge.notifySdlExposed()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWindowingState()
        if (windowingState.isFullscreen && !isInPipMode) {
            applyFullscreenState(true)
        }
        mSurface?.post {
            mSurface?.requestViewportRescale()
            NativeQemuBridge.notifySdlExposed()
        }
        // Re-sync guest clock whenever returning from Android background/sleep
        val client = guestAgentClient
        if (client != null && guestAgentSnapshot.connected) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { client.syncGuestTime() }
                if (ok) {
                    guestAgentSnapshot = guestAgentSnapshot.copy(
                        lastTimeSyncEpochMs = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !windowingState.showFullscreenButton) {
            applyFullscreenState(true)
        }
        updateSurfaceLayoutForPosture()
        mSurface?.post {
            mSurface?.requestViewportRescale()
            NativeQemuBridge.notifySdlExposed()
        }
    }

    private fun buildPipParams(guestWidth: Int, guestHeight: Int): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(FoldableViewportHelper.computePipAspectRatio(guestWidth, guestHeight))
            .setSeamlessResizeEnabled(true)
            .build()

    private fun updatePipParams(guestWidth: Int, guestHeight: Int) {
        runCatching {
            setPictureInPictureParams(buildPipParams(guestWidth, guestHeight))
        }
    }

    private fun enterVmPictureInPicture() {
        if (mSurface?.isPointerCaptureActive == true) {
            mSurface?.setPointerCapture(false)
        }
        val gw = viewportBounds.guestWidth.takeIf { it > 0 } ?: vmConfig.resolution.width
        val gh = viewportBounds.guestHeight.takeIf { it > 0 } ?: vmConfig.resolution.height
        runCatching {
            enterPictureInPictureMode(buildPipParams(gw, gh))
        }
    }

    private fun updateSurfaceLayoutForPosture() {
        val surface = mSurface ?: return
        val targetHeight = if (viewportPosture.isTabletop && !isInPipMode) {
            viewportPosture.hingeTopPx.coerceAtLeast(FoldableViewportHelper.MIN_TABLETOP_TOP_PANE_PX)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (targetHeight == appliedTabletopHeightPx) {
            return
        }
        appliedTabletopHeightPx = targetHeight
        val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)
        if (targetHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
            lp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
        } else {
            lp.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
        }
        surface.layoutParams = lp
        surface.post { surface.requestViewportRescale() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && mSurface?.isPointerCaptureActive == true) {
            mSurface?.setPointerCapture(false)
        }
        if (hasFocus && windowingState.isFullscreen && !isInPipMode) {
            applyFullscreenState(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!mBrokenLibraries) {
            val newOrientation = SDLActivity.getCurrentOrientation()
            if (newOrientation != SDLActivity.mCurrentOrientation) {
                SDLActivity.mCurrentOrientation = newOrientation
                SDLActivity.onNativeOrientationChanged(newOrientation)
            }
        }
        refreshWindowingState()
        lastWindowLayoutInfo?.let { info ->
            val windowHeightPx = mLayout?.height?.takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val resolved = FoldableViewportHelper.resolveTabletopPosture(info, windowHeightPx)
            if (resolved != viewportPosture) {
                viewportPosture = resolved
                updateSurfaceLayoutForPosture()
            }
        }
        mSurface?.post {
            mSurface?.requestViewportRescale()
            NativeQemuBridge.notifySdlExposed()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshWindowingState()
        mSurface?.post {
            mSurface?.requestViewportRescale()
            NativeQemuBridge.notifySdlExposed()
        }
    }

    override fun onDestroy() {
        releaseExternalHandles()
        super.onDestroy()
        // Exit the isolated :qemu_vm process cleanly so QEMU globals & guest RAM are freed
        Process.killProcess(Process.myPid())
    }

    private fun releaseExternalHandles() {
        runCatching { sharedFolderServer?.stop() }
        sharedFolderServer = null
        qgaSocketFile?.let { file -> runCatching { file.delete() } }
        qgaSocketFile = null
        activeUsbSessions.forEach { session -> runCatching { session.close() } }
        activeUsbSessions.clear()
        openDescriptors.forEach { pfd -> runCatching { pfd.close() } }
        openDescriptors.clear()
    }

    private fun powerOffVmProcess() {
        releaseExternalHandles()
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun toggleFullscreen() {
        val target = !windowingState.isFullscreen
        applyFullscreenState(target)
        mSurface?.post { mSurface?.requestViewportRescale() }
    }

    private fun applyFullscreenState(fullscreen: Boolean) {
        DesktopWindowingHelper.applyFullscreen(this, fullscreen)
        SDLActivity.mFullscreenModeActive = fullscreen
        refreshWindowingState(explicitFullscreen = fullscreen)
    }

    private fun refreshWindowingState(explicitFullscreen: Boolean = windowingState.isFullscreen) {
        val inspected = DesktopWindowingHelper.inspect(
            activity = this,
            previouslyDetectedWindowed = hasEverDetectedWindowed,
            isCurrentlyFullscreen = explicitFullscreen
        )
        if (inspected.showFullscreenButton) {
            hasEverDetectedWindowed = true
        }
        windowingState = inspected
    }

    private fun resolveMediaPath(pathOrUri: String, readWrite: Boolean): String? {
        val trimmed = pathOrUri.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("content://")) return trimmed
        return runCatching {
            val mode = if (readWrite) "rw" else "r"
            val pfd = contentResolver.openFileDescriptor(Uri.parse(trimmed), mode)
                ?: contentResolver.openFileDescriptor(Uri.parse(trimmed), "r")
            if (pfd != null) {
                openDescriptors += pfd
                "/proc/self/fd/${pfd.fd}"
            } else {
                null
            }
        }.getOrNull()
    }

    private fun resolveAndroidDnsServersCsv(): String {
        val discovered = runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val linkProps = cm?.getLinkProperties(cm.activeNetwork)
            linkProps?.dnsServers
                .orEmpty()
                .mapNotNull { it.hostAddress?.substringBefore('%')?.trim() }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        return (discovered + listOf("1.1.1.1", "8.8.8.8"))
            .distinct()
            .joinToString(",")
    }

    private fun sendAsciiCommandToGuest(command: String) {
        lifecycleScope.launch {
            for (ch in command) {
                sendAsciiCharKey(ch)
                kotlinx.coroutines.delay(18L)
            }
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER)
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun sendAsciiCharKey(ch: Char) {
        var needShift = false
        val keyCode = when (ch) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            in 'A'..'Z' -> {
                needShift = true
                KeyEvent.KEYCODE_A + (ch - 'A')
            }
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '-' -> KeyEvent.KEYCODE_MINUS
            '_' -> {
                needShift = true
                KeyEvent.KEYCODE_MINUS
            }
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            '|' -> {
                needShift = true
                KeyEvent.KEYCODE_BACKSLASH
            }
            '&' -> {
                needShift = true
                KeyEvent.KEYCODE_7
            }
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            else -> return
        }
        if (needShift) SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        SDLActivity.onNativeKeyDown(keyCode)
        SDLActivity.onNativeKeyUp(keyCode)
        if (needShift) SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    companion object {
        private const val EXTRA_VM_CONFIG_JSON = "extra_vm_config_json"
        private const val EXTRA_LAUNCH_ADJACENT = "extra_launch_adjacent"
        private val codec = Json { encodeDefaults = true }

        fun createIntent(
            context: Context,
            config: VmConfig,
            launchAdjacent: Boolean = false
        ): Intent =
            Intent(context, QemuSdlActivity::class.java)
                .putExtra(EXTRA_VM_CONFIG_JSON, codec.encodeToString(config))
                .putExtra(EXTRA_LAUNCH_ADJACENT, launchAdjacent)
                .apply {
                    if (launchAdjacent) {
                        addFlags(
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                }
    }
}

@Composable
private fun VirtualMouseCursorOverlay(
    cursorState: VirtualCursorUiState,
    mouseMode: MouseEmulationMode
) {
    if (!cursorState.visible) return
    val cursorPath = remember { Path() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val x = cursorState.screenX.coerceIn(0f, size.width)
        val y = cursorState.screenY.coerceIn(0f, size.height)
        val dpPx = 1.dp.toPx()

        // Active click ring around the pointer tip
        if (cursorState.leftDown || cursorState.rightDown) {
            val ringColor = if (cursorState.rightDown) Color(0xFFFFB74D) else Color(0xFF4CE0A0)
            drawCircle(
                color = ringColor.copy(alpha = 0.35f),
                radius = 16f * dpPx,
                center = Offset(x, y)
            )
            drawCircle(
                color = ringColor,
                radius = 16f * dpPx,
                center = Offset(x, y),
                style = Stroke(width = 2f * dpPx)
            )
        }

        if (mouseMode == MouseEmulationMode.DIRECT_TOUCH) {
            // Subtle precision crosshair / dot for Direct Touch mode (helps when guest uses hardware cursor)
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = 5.5f * dpPx,
                center = Offset(x, y)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 3.5f * dpPx,
                center = Offset(x, y)
            )
        } else {
            // Classic high-contrast desktop arrow cursor for Trackpad (Relative) and Hover & Tap (Absolute)
            cursorPath.reset()
            cursorPath.moveTo(x, y)
            cursorPath.lineTo(x, y + 20f * dpPx)
            cursorPath.lineTo(x + 5.2f * dpPx, y + 15.2f * dpPx)
            cursorPath.lineTo(x + 9.2f * dpPx, y + 23.2f * dpPx)
            cursorPath.lineTo(x + 12.6f * dpPx, y + 21.6f * dpPx)
            cursorPath.lineTo(x + 8.6f * dpPx, y + 13.6f * dpPx)
            cursorPath.lineTo(x + 15.0f * dpPx, y + 13.6f * dpPx)
            cursorPath.close()

            drawPath(
                path = cursorPath,
                color = Color(0xFF0A0E17),
                style = Stroke(width = 2.6f * dpPx, join = StrokeJoin.Round)
            )
            drawPath(
                path = cursorPath,
                color = if (cursorState.leftDown) Color(0xFF4CE0A0)
                else if (cursorState.rightDown) Color(0xFFFFB74D)
                else Color.White,
                style = Fill
            )
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
private fun QemuSdlOverlayHud(
    vmConfig: VmConfig,
    cursorState: VirtualCursorUiState,
    viewportPosture: ViewportPostureState,
    viewportBounds: ViewportBoundsState,
    isInPipMode: Boolean,
    qemuCommandLine: String,
    firmwarePath: String,
    showFullscreenButton: Boolean,
    isFullscreen: Boolean,
    isRelativeGuestPointer: Boolean,
    isPointerCaptured: Boolean,
    onToggleFullscreen: () -> Unit,
    onTogglePointerCapture: () -> Unit,
    onEnterPip: () -> Unit,
    onScalingModeChanged: (SdlScalingMode) -> Unit,
    onMouseEmulationModeChanged: (MouseEmulationMode) -> Unit,
    onTrackpadSensitivityChanged: (Float) -> Unit,
    onTrackpadDelta: (Float, Float) -> Unit,
    onTrackpadScroll: (Float, Float) -> Unit,
    onPerformClick: (Boolean) -> Unit,
    onVirtualMouseButton: (rightClick: Boolean, pressed: Boolean) -> Unit,
    onShowKeyboard: () -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onSendShellCommand: (String) -> Unit,
    activeDnsServers: String,
    guestAgentSnapshot: GuestAgentSnapshot,
    guestAgentActionMessage: String?,
    virtioDriversIsoMountedName: String?,
    sharedFolderGuestUrl: String?,
    sharedFolderHostPath: String?,
    onSyncGuestClock: () -> Unit,
    onGracefulGuestShutdown: () -> Unit,
    onPowerOff: () -> Unit
) {
    // Keep Picture-in-Picture thumbnail completely clean with zero overlaid controls
    if (isInPipMode) {
        return
    }

    var hudVisible by remember { mutableStateOf(true) }
    var showSpecialKeys by remember { mutableStateOf(false) }
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showMouseMenu by remember { mutableStateOf(false) }
    var showScalingMenu by remember { mutableStateOf(false) }

    var scalingMode by remember(vmConfig.scalingMode) { mutableStateOf(vmConfig.scalingMode) }
    var mouseEmulationMode by remember(vmConfig.mouseEmulationMode) { mutableStateOf(vmConfig.mouseEmulationMode) }
    var trackpadSensitivity by remember { mutableFloatStateOf(1.4f) }

    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }

    fun selectScaling(mode: SdlScalingMode) {
        scalingMode = mode
        onScalingModeChanged(mode)
    }

    fun selectMouseMode(mode: MouseEmulationMode) {
        mouseEmulationMode = mode
        onMouseEmulationModeChanged(mode)
    }

    val density = LocalDensity.current
    val bottomLetterboxDp = with(density) { viewportBounds.bottomLetterboxPx.toDp().value }
    val useLetterboxDock = FoldableViewportHelper.shouldUseLetterboxDock(
        isTabletop = viewportPosture.isTabletop,
        isInPip = isInPipMode,
        bottomLetterboxDp = bottomLetterboxDp
    )

    val specialKeysStripContent: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(100),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = stickyCtrl,
                    onClick = {
                        stickyCtrl = !stickyCtrl
                        if (stickyCtrl) onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
                        else onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
                    },
                    label = { Text("Ctrl") }
                )
                FilterChip(
                    selected = stickyAlt,
                    onClick = {
                        stickyAlt = !stickyAlt
                        if (stickyAlt) onKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
                        else onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
                    },
                    label = { Text("Alt") }
                )
                val quickKeys = listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE,
                    "Tab" to KeyEvent.KEYCODE_TAB,
                    "Enter" to KeyEvent.KEYCODE_ENTER,
                    "↑" to KeyEvent.KEYCODE_DPAD_UP,
                    "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                    "←" to KeyEvent.KEYCODE_DPAD_LEFT,
                    "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
                    "F1" to KeyEvent.KEYCODE_F1,
                    "F2" to KeyEvent.KEYCODE_F2,
                    "F8" to KeyEvent.KEYCODE_F8,
                    "F12" to KeyEvent.KEYCODE_F12
                )
                quickKeys.forEach { (label, keyCode) ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            onKeyDown(keyCode)
                            onKeyUp(keyCode)
                            if (stickyCtrl) {
                                stickyCtrl = false
                                onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
                            }
                            if (stickyAlt) {
                                stickyAlt = false
                                onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
                            }
                        },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = {
                        onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)
                        onKeyDown(KeyEvent.KEYCODE_ALT_LEFT)
                        onKeyDown(KeyEvent.KEYCODE_FORWARD_DEL)
                        onKeyUp(KeyEvent.KEYCODE_FORWARD_DEL)
                        onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
                        onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
                    },
                    label = { Text("Ctrl+Alt+Del") }
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Touch-transparent virtual mouse cursor overlay rendered directly above SDLSurface
        VirtualMouseCursorOverlay(
            cursorState = cursorState,
            mouseMode = mouseEmulationMode
        )

        if (viewportPosture.isTabletop) {
            // Tabletop "Mini-Laptop" Mode: VM runs cleanly above the crease, full control deck below
            TabletopLaptopDeck(
                vmConfig = vmConfig,
                hingeBottomPx = viewportPosture.hingeBottomPx,
                scalingMode = scalingMode,
                leftButtonDown = cursorState.leftDown,
                rightButtonDown = cursorState.rightDown,
                onSelectScaling = ::selectScaling,
                onTrackpadDelta = onTrackpadDelta,
                onTrackpadScroll = onTrackpadScroll,
                onPerformClick = onPerformClick,
                onVirtualMouseButton = onVirtualMouseButton,
                onShowKeyboard = onShowKeyboard,
                onKeyDown = onKeyDown,
                onKeyUp = onKeyUp,
                onEnterPip = onEnterPip,
                onOpenMonitor = { showMonitorSheet = true },
                onPowerOff = onPowerOff
            )
        } else {
            // Top-left live native SDL2 pill
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(100))
                    .desktopHandCursor()
                    .clickable { hudVisible = !hudVisible },
                shape = RoundedCornerShape(100),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CE0A0))
                    )
                    Text(
                        text = "${vmConfig.name} · ${scalingMode.label} · ${mouseEmulationMode.shortLabel}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        imageVector = if (hudVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = "Toggle HUD",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Top-right Desktop controls (Pointer Capture for relative guests + Full Screen F11)
            AnimatedVisibility(
                visible = showFullscreenButton && hudVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRelativeGuestPointer) {
                        AppTooltipBox(
                            tooltip = if (isPointerCaptured) {
                                "Release captured mouse (Esc or Ctrl+Alt+G)"
                            } else {
                                "Capture physical mouse for relative guest (Ctrl+Alt+G)"
                            }
                        ) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(100))
                                    .desktopHandCursor()
                                    .clickable { onTogglePointerCapture() },
                                shape = RoundedCornerShape(100),
                                color = if (isPointerCaptured) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
                                },
                                contentColor = if (isPointerCaptured) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mouse,
                                        contentDescription = if (isPointerCaptured) "Release mouse" else "Capture mouse",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isPointerCaptured) "Mouse Locked (Esc)" else "Capture Mouse",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    AppTooltipBox(
                        tooltip = if (isFullscreen) "Exit Full Screen (F11)" else "Full Screen (F11)"
                    ) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100))
                                .desktopHandCursor()
                                .clickable { onToggleFullscreen() },
                            shape = RoundedCornerShape(100),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    contentDescription = if (isFullscreen) "Exit full screen" else "Full screen",
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isFullscreen) "Exit Full Screen" else "Full Screen",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            if (useLetterboxDock) {
                // Unfolded nearly-square inner display with ample bottom black letterbox margin:
                // seat controls strictly below the guest framebuffer so zero guest pixels are covered.
                AnimatedVisibility(
                    visible = hudVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    LetterboxDockedControls(
                        viewportBottomPx = viewportBounds.bottomPx,
                        mouseEmulationMode = mouseEmulationMode,
                        scalingMode = scalingMode,
                        isPointerCaptured = isPointerCaptured,
                        leftButtonDown = cursorState.leftDown,
                        rightButtonDown = cursorState.rightDown,
                        showFullscreenButton = showFullscreenButton,
                        isFullscreen = isFullscreen,
                        showSpecialKeys = showSpecialKeys,
                        onToggleSpecialKeys = { showSpecialKeys = !showSpecialKeys },
                        onSelectMouseMode = ::selectMouseMode,
                        onSelectScaling = ::selectScaling,
                        onPerformClick = onPerformClick,
                        onShowKeyboard = onShowKeyboard,
                        onToggleFullscreen = onToggleFullscreen,
                        onEnterPip = onEnterPip,
                        onOpenMonitor = { showMonitorSheet = true },
                        onPowerOff = onPowerOff,
                        specialKeysStrip = specialKeysStripContent
                    )
                }
            } else {
                // Quick Left / Right click buttons on bottom-start when using touch Trackpad / Hover & Tap
                AnimatedVisibility(
                    visible = hudVisible && !isPointerCaptured && mouseEmulationMode != MouseEmulationMode.DIRECT_TOUCH,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 14.dp, bottom = 18.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = cursorState.leftDown,
                                onClick = { onPerformClick(false) },
                                label = { Text("L Click") }
                            )
                            FilterChip(
                                selected = cursorState.rightDown,
                                onClick = { onPerformClick(true) },
                                label = { Text("R Click") }
                            )
                        }
                    }
                }

                // Bottom Material 3 Expressive Floating Toolbar
                AnimatedVisibility(
                    visible = hudVisible,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AnimatedVisibility(visible = showSpecialKeys) {
                            specialKeysStripContent()
                        }

                        HorizontalFloatingToolbar(
                            expanded = true,
                            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                            floatingActionButton = {
                                AppTooltipBox(tooltip = "VM Settings & Monitor") {
                                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                                        onClick = { showMonitorSheet = true },
                                        modifier = Modifier.desktopHandCursor()
                                    ) {
                                        Icon(Icons.Rounded.Terminal, contentDescription = "VM Settings & Power")
                                    }
                                }
                            }
                        ) {
                            Box {
                                AppTooltipBox(tooltip = "Mouse emulation mode") {
                                    IconButton(
                                        onClick = { showMouseMenu = true },
                                        modifier = Modifier.desktopHandCursor()
                                    ) {
                                        Icon(
                                            imageVector = when (mouseEmulationMode) {
                                                MouseEmulationMode.TRACKPAD_RELATIVE -> Icons.Rounded.Mouse
                                                MouseEmulationMode.DIRECT_TOUCH -> Icons.Rounded.TouchApp
                                                MouseEmulationMode.DIRECT_HOVER_TAP -> Icons.Rounded.Tune
                                            },
                                            contentDescription = "Select mouse emulation mode"
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showMouseMenu,
                                    onDismissRequest = { showMouseMenu = false }
                                ) {
                                    MouseEmulationMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = if (mouseEmulationMode == mode) "✓ ${mode.label}" else mode.label,
                                                        style = MaterialTheme.typography.labelLarge
                                                    )
                                                    Text(
                                                        text = mode.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showMouseMenu = false
                                                selectMouseMode(mode)
                                            }
                                        )
                                    }
                                }
                            }

                            Box {
                                AppTooltipBox(tooltip = "Viewport scaling") {
                                    IconButton(
                                        onClick = { showScalingMenu = true },
                                        modifier = Modifier.desktopHandCursor()
                                    ) {
                                        Icon(Icons.Rounded.AspectRatio, contentDescription = "Select viewport scaling")
                                    }
                                }
                                DropdownMenu(
                                    expanded = showScalingMenu,
                                    onDismissRequest = { showScalingMenu = false }
                                ) {
                                    SdlScalingMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = if (scalingMode == mode) "✓ ${mode.label}" else mode.label,
                                                        style = MaterialTheme.typography.labelLarge
                                                    )
                                                    Text(
                                                        text = mode.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showScalingMenu = false
                                                selectScaling(mode)
                                            }
                                        )
                                    }
                                }
                            }

                            AppTooltipBox(tooltip = "Toggle soft keyboard") {
                                IconButton(onClick = onShowKeyboard, modifier = Modifier.desktopHandCursor()) {
                                    Icon(Icons.Rounded.Keyboard, contentDescription = "Toggle soft keyboard")
                                }
                            }
                            AppTooltipBox(tooltip = "Special keys bar") {
                                IconButton(
                                    onClick = { showSpecialKeys = !showSpecialKeys },
                                    modifier = Modifier.desktopHandCursor()
                                ) {
                                    Icon(Icons.Rounded.KeyboardCommandKey, contentDescription = "Special keys bar")
                                }
                            }
                            AppTooltipBox(tooltip = "Picture-in-Picture (Ctrl+Alt+P)") {
                                IconButton(onClick = onEnterPip, modifier = Modifier.desktopHandCursor()) {
                                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Picture-in-Picture")
                                }
                            }
                            if (showFullscreenButton) {
                                AppTooltipBox(tooltip = if (isFullscreen) "Exit Full Screen (F11)" else "Full Screen (F11)") {
                                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.desktopHandCursor()) {
                                        Icon(
                                            imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                            contentDescription = if (isFullscreen) "Exit full screen" else "Full screen"
                                        )
                                    }
                                }
                            }
                            AppTooltipBox(tooltip = "Power off VM") {
                                IconButton(onClick = onPowerOff, modifier = Modifier.desktopHandCursor()) {
                                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Power off VM")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMonitorSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMonitorSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${vmConfig.name} · In-Process QEMU SDL2",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Live Viewport Scaling selector inside running VM UI
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Viewport Scaling Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SdlScalingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = scalingMode == mode,
                                    onClick = { selectScaling(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        Text(
                            text = scalingMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live Mouse Emulation Mode selector inside running VM UI
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Mouse Emulation Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MouseEmulationMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = mouseEmulationMode == mode,
                                    onClick = { selectMouseMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        Text(
                            text = mouseEmulationMode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (mouseEmulationMode == MouseEmulationMode.TRACKPAD_RELATIVE) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = String.format(Locale.US, "Trackpad Sensitivity: %.1fx", trackpadSensitivity),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = trackpadSensitivity,
                                onValueChange = {
                                    trackpadSensitivity = it
                                    onTrackpadSensitivityChanged(it)
                                },
                                valueRange = 0.5f..3.0f
                            )
                        }
                    }
                }

                // Guest Networking (SLIRP) status & 1-tap DHCP helper
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (vmConfig.networkEnabled) {
                                "Guest Networking · SLIRP Active (${vmConfig.networkAdapter.label})"
                            } else {
                                "Guest Networking · Disabled (-nic none)"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (vmConfig.networkEnabled) {
                            Text(
                                text = "DHCP IP: 10.0.2.15/24 · Gateway: 10.0.2.2 · DNS Proxy: 10.0.2.3 -> $activeDnsServers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "TinyCore & Debian configure eth0 automatically. On Alpine live ISO, log in as root and tap below to run DHCP:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = false,
                                onClick = {
                                    showMonitorSheet = false
                                    onSendShellCommand("ifconfig eth0 up && udhcpc -i eth0")
                                },
                                label = { Text("Type: ifconfig eth0 up && udhcpc -i eth0") }
                            )
                        }
                    }
                }

                // QEMU Guest Agent (org.qemu.guest_agent.0) live status, clock sync & graceful shutdown
                if (guestAgentSnapshot.enabled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (guestAgentSnapshot.connected) Color(0xFF4CE0A0)
                                            else MaterialTheme.colorScheme.outline
                                        )
                                )
                                Text(
                                    text = if (guestAgentSnapshot.connected) {
                                        "QEMU Guest Agent · Connected" +
                                            (guestAgentSnapshot.agentVersion?.let { " (v$it)" } ?: "")
                                    } else {
                                        "QEMU Guest Agent · Waiting for qemu-ga"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            if (guestAgentSnapshot.connected) {
                                val osLabel = listOfNotNull(
                                    guestAgentSnapshot.osName,
                                    guestAgentSnapshot.osVersion
                                ).joinToString(" ").ifBlank { "Guest OS detected" }
                                Text(
                                    text = "OS: $osLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (guestAgentSnapshot.ipv4Addresses.isNotEmpty()) {
                                    Text(
                                        text = "IPv4: ${guestAgentSnapshot.ipv4Addresses.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                guestAgentSnapshot.lastTimeSyncEpochMs?.let { epochMs ->
                                    val formatted = remember(epochMs) {
                                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
                                    }
                                    Text(
                                        text = "Last clock sync: $formatted",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = false,
                                        onClick = onSyncGuestClock,
                                        label = { Text("Sync Clock Now") }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = onGracefulGuestShutdown,
                                        label = { Text("Graceful Shutdown") }
                                    )
                                }
                            } else {
                                Text(
                                    text = "Install qemu-guest-agent in Linux (apt/apk add qemu-guest-agent) or run virtio-win-guest-tools.exe in Windows to enable graceful shutdown, auto clock sync, and live telemetry.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            guestAgentActionMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // SPICE Tools & Display Integration status card
                val spiceClipboardActive = vmConfig.spiceClipboardEnabled && vmConfig.architecture != GuestArchitecture.PPC
                val autoResizeActive = vmConfig.dynamicDisplayResize && vmConfig.architecture != GuestArchitecture.PPC
                if (spiceClipboardActive || autoResizeActive) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "SPICE Tools & Display Integration",
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (spiceClipboardActive) {
                                Text(
                                    text = "Clipboard Sync: Active (qemu-vdagent -> com.redhat.spice.0)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Install spice-vdagent in Linux or virtio-win-guest-tools.exe in Windows for automatic host ↔ guest clipboard sharing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (autoResizeActive) {
                                Text(
                                    text = "Dynamic Display Auto-Resize: Active (aspect-preserving, max 1600px)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }

                // Shared Folder (WebDAV / HTTP & VirtIO-9p) status card
                val virtio9pActive = vmConfig.virtio9pEnabled && vmConfig.architecture != GuestArchitecture.PPC
                if (sharedFolderGuestUrl != null || virtio9pActive) {
                    val activeModes = buildList {
                        if (sharedFolderGuestUrl != null) add("WebDAV / HTTP")
                        if (virtio9pActive) add("VirtIO-9p")
                    }.joinToString(" + ")
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Host ↔ Guest Shared Folder · Active ($activeModes)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            sharedFolderGuestUrl?.let { url ->
                                Text(
                                    text = "WebDAV / HTTP: $url",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (virtio9pActive) {
                                Text(
                                    text = "VirtIO-9p tag: hostshare (mount -t 9p -o trans=virtio,version=9p2000.L hostshare /mnt/host)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        showMonitorSheet = false
                                        onSendShellCommand("mkdir -p /mnt/host && mount -t 9p -o trans=virtio,version=9p2000.L hostshare /mnt/host")
                                    },
                                    label = { Text("Type 9p Mount Command") }
                                )
                            }
                            sharedFolderHostPath?.let { hostPath ->
                                Text(
                                    text = "Android folder: $hostPath",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (sharedFolderGuestUrl != null) {
                                Text(
                                    text = "Open the URL in any guest browser for drag-and-drop download/upload, or map as a WebDAV network drive.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Secondary CD-ROM: VirtIO Drivers ISO indicator
                if (virtioDriversIsoMountedName != null) {
                    Text(
                        text = "VirtIO Drivers ISO (CD-ROM #2): $virtioDriversIsoMountedName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Text(
                    text = "Firmware (-L): $firmwarePath",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = qemuCommandLine,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Button(
                    onClick = {
                        showMonitorSheet = false
                        onPowerOff()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Power Off & Terminate :qemu_vm Process")
                }
            }
        }
    }
}
