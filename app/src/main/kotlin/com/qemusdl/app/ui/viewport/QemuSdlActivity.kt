package com.qemusdl.app.ui.viewport

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.view.KeyEvent
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
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Mouse
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.qemu.QemuFirmwareManager
import com.qemusdl.app.ui.theme.QemuSDLTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.libsdl.app.SDLActivity
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
            firmwareDir = firmwareDirPath,
            edk2CodePath = pflashFiles?.first?.absolutePath,
            edk2VarsPath = pflashFiles?.second?.absolutePath,
            activeUsbDeviceFds = activeUsbFds
        )

        // Configure SDL2 video/render/audio hints and Android upstream SLIRP DNS servers
        // prior to libSDL2.so + libqemu-system-x86_64.so initialization
        val activeDnsCsv = resolveAndroidDnsServersCsv()
        runCatching {
            Os.setenv("SDL_RENDER_DRIVER", if (vmConfig.sdlGlAcceleration) "opengles2" else "software", true)
            Os.setenv("SDL_AUDIODRIVER", "openslES", true)
            Os.setenv("SDL_HINT_ANDROID_TRAP_BACK_BUTTON", "1", true)
            Os.setenv("SDL_HINT_TOUCH_MOUSE_EVENTS", "0", true)
            // Keep the QEMU emulation & EGL context alive across brief Android window focus changes
            Os.setenv("SDL_ANDROID_BLOCK_ON_PAUSE", "0", true)
            Os.setenv("QEMU_SLIRP_DNS", activeDnsCsv, true)
        }

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Apply initial viewport scaling, mouse emulation mode, and live cursor listener on SDLSurface
        mSurface?.setGuestResolution(vmConfig.resolution.width, vmConfig.resolution.height)
        mSurface?.setScalingMode(vmConfig.scalingMode.nativeCode)
        mSurface?.setMouseEmulationMode(vmConfig.mouseEmulationMode.ordinal)
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

        val repository = VmRepository.getInstance(this)

        // Overlay our Material 3 Expressive Compose HUD directly on top of SDLActivity.mLayout
        if (!mBrokenLibraries && mLayout != null) {
            val hudOverlay = ComposeView(this).apply {
                setContent {
                    QemuSDLTheme(darkTheme = true) {
                        QemuSdlOverlayHud(
                            vmConfig = vmConfig,
                            cursorState = cursorUiState,
                            qemuCommandLine = qemuFullArgs.joinToString(" "),
                            firmwarePath = firmwareDirPath ?: "Not bundled",
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
                            onPerformClick = { rightClick ->
                                mSurface?.performVirtualClick(rightClick)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        activeUsbSessions.forEach { session -> runCatching { session.close() } }
        activeUsbSessions.clear()
        openDescriptors.forEach { pfd -> runCatching { pfd.close() } }
        openDescriptors.clear()
        super.onDestroy()
        // Exit the isolated :qemu_vm process cleanly so QEMU globals & guest RAM are freed
        Process.killProcess(Process.myPid())
    }

    private fun powerOffVmProcess() {
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
        private val codec = Json { encodeDefaults = true }

        fun createIntent(context: Context, config: VmConfig): Intent =
            Intent(context, QemuSdlActivity::class.java)
                .putExtra(EXTRA_VM_CONFIG_JSON, codec.encodeToString(config))
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
    qemuCommandLine: String,
    firmwarePath: String,
    onScalingModeChanged: (SdlScalingMode) -> Unit,
    onMouseEmulationModeChanged: (MouseEmulationMode) -> Unit,
    onTrackpadSensitivityChanged: (Float) -> Unit,
    onPerformClick: (Boolean) -> Unit,
    onShowKeyboard: () -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onSendShellCommand: (String) -> Unit,
    activeDnsServers: String,
    onPowerOff: () -> Unit
) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        // Touch-transparent virtual mouse cursor overlay rendered directly above SDLSurface
        VirtualMouseCursorOverlay(
            cursorState = cursorState,
            mouseMode = mouseEmulationMode
        )

        // Top-left live native SDL2 pill
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(100))
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

        // Quick Left / Right click buttons on bottom-start when in Trackpad or Hover & Tap mode
        AnimatedVisibility(
            visible = hudVisible && mouseEmulationMode != MouseEmulationMode.DIRECT_TOUCH,
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

                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(
                            onClick = { showMonitorSheet = true }
                        ) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "VM Settings & Power")
                        }
                    }
                ) {
                    Box {
                        IconButton(onClick = { showMouseMenu = true }) {
                            Icon(
                                imageVector = when (mouseEmulationMode) {
                                    MouseEmulationMode.TRACKPAD_RELATIVE -> Icons.Rounded.Mouse
                                    MouseEmulationMode.DIRECT_TOUCH -> Icons.Rounded.TouchApp
                                    MouseEmulationMode.DIRECT_HOVER_TAP -> Icons.Rounded.Tune
                                },
                                contentDescription = "Select mouse emulation mode"
                            )
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
                        IconButton(onClick = { showScalingMenu = true }) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Select viewport scaling")
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

                    IconButton(onClick = onShowKeyboard) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Toggle soft keyboard")
                    }
                    IconButton(onClick = { showSpecialKeys = !showSpecialKeys }) {
                        Icon(Icons.Rounded.KeyboardCommandKey, contentDescription = "Special keys bar")
                    }
                    IconButton(onClick = onPowerOff) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Power off VM")
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
