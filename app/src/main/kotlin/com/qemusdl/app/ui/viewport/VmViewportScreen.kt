package com.qemusdl.app.ui.viewport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.sdl.NativeQemuBridge
import com.qemusdl.app.sdl.SdlScancodeMapper
import com.qemusdl.app.sdl.SdlSurfaceView
import com.qemusdl.app.sdl.ViewportTelemetry
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun VmViewportScreen(
    vmConfig: VmConfig,
    qemuCommandLine: String,
    showFullscreenButton: Boolean = false,
    isFullscreen: Boolean = true,
    onToggleFullscreen: () -> Unit = {},
    onSurfaceViewCreated: (SdlSurfaceView) -> Unit,
    onToggleSoftKeyboard: () -> Unit,
    onUpdateVmConfig: (VmConfig) -> Unit = {},
    onPowerOff: () -> Unit
) {
    var surfaceViewRef by remember { mutableStateOf<SdlSurfaceView?>(null) }
    var telemetry by remember { mutableStateOf(ViewportTelemetry()) }

    var hudVisible by remember { mutableStateOf(true) }
    var showSpecialKeys by remember { mutableStateOf(false) }
    var showMonitorSheet by remember { mutableStateOf(false) }
    var showMouseMenu by remember { mutableStateOf(false) }
    var showScalingMenu by remember { mutableStateOf(false) }

    var mouseEmulationMode by remember(vmConfig.mouseEmulationMode) {
        mutableStateOf(vmConfig.mouseEmulationMode)
    }
    var trackpadSensitivity by remember { mutableFloatStateOf(1.4f) }
    var scalingMode by remember(vmConfig.scalingMode) {
        mutableStateOf(vmConfig.scalingMode)
    }
    var isPaused by remember { mutableStateOf(false) }

    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }

    fun updateScalingMode(mode: SdlScalingMode) {
        scalingMode = mode
        NativeQemuBridge.setScalingMode(mode.nativeCode)
        onUpdateVmConfig(vmConfig.copy(scalingMode = mode, mouseEmulationMode = mouseEmulationMode))
    }

    fun updateMouseMode(mode: MouseEmulationMode) {
        mouseEmulationMode = mode
        surfaceViewRef?.mouseEmulationMode = mode
        onUpdateVmConfig(vmConfig.copy(scalingMode = scalingMode, mouseEmulationMode = mode))
    }

    // Poll native viewport telemetry at ~2 Hz for HUD display
    LaunchedEffect(Unit) {
        while (true) {
            telemetry = NativeQemuBridge.getTelemetry()
            delay(500L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A12))
    ) {
        // 100% Native SDL2 / OpenGL ES 3.0 SurfaceView Viewport
        AndroidView(
            factory = { ctx ->
                SdlSurfaceView(ctx).apply {
                    this.mouseEmulationMode = mouseEmulationMode
                    this.trackpadSensitivity = trackpadSensitivity
                    surfaceViewRef = this
                    onSurfaceViewCreated(this)
                }
            },
            update = { view ->
                view.mouseEmulationMode = mouseEmulationMode
                view.trackpadSensitivity = trackpadSensitivity
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top-left live telemetry & HUD toggle capsule
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .clip(RoundedCornerShape(100))
                .clickable { hudVisible = !hudVisible },
            shape = RoundedCornerShape(100),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f),
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
                        .background(
                            if (isPaused) Color(0xFFFFB74D) else Color(0xFF4CE0A0)
                        )
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "%s · %.0f FPS · %s · %s",
                        vmConfig.name,
                        telemetry.fps,
                        scalingMode.label,
                        mouseEmulationMode.shortLabel
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = if (hudVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = "Toggle HUD",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Top-right Full Screen button on desktop / windowed platforms (Samsung DeX, Moto Ready For, ChromeOS, etc.)
        AnimatedVisibility(
            visible = showFullscreenButton && hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
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

        // Bottom overlay stack: Optional special keys strip + Expressive HorizontalFloatingToolbar
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
                    SpecialKeysStrip(
                        stickyCtrl = stickyCtrl,
                        stickyAlt = stickyAlt,
                        onToggleCtrl = {
                            stickyCtrl = !stickyCtrl
                            NativeQemuBridge.sendKeyEvent(SdlScancodeMapper.SCANCODE_LCTRL, 0, 0, stickyCtrl)
                        },
                        onToggleAlt = {
                            stickyAlt = !stickyAlt
                            NativeQemuBridge.sendKeyEvent(SdlScancodeMapper.SCANCODE_LALT, 0, 0, stickyAlt)
                        },
                        onTapScancode = { code ->
                            NativeQemuBridge.tapScancode(code)
                            if (stickyCtrl) {
                                stickyCtrl = false
                                NativeQemuBridge.sendKeyEvent(SdlScancodeMapper.SCANCODE_LCTRL, 0, 0, false)
                            }
                            if (stickyAlt) {
                                stickyAlt = false
                                NativeQemuBridge.sendKeyEvent(SdlScancodeMapper.SCANCODE_LALT, 0, 0, false)
                            }
                        },
                        onSendCtrlAltDel = {
                            NativeQemuBridge.sendKeyCombo(
                                SdlScancodeMapper.SCANCODE_LCTRL,
                                SdlScancodeMapper.SCANCODE_LALT,
                                SdlScancodeMapper.SCANCODE_DELETE
                            )
                        }
                    )
                }

                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(
                            onClick = { showMonitorSheet = true }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = "VM Monitor & Settings"
                            )
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
                                        updateMouseMode(mode)
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { showScalingMenu = true }) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Select viewport scaling mode")
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
                                        updateScalingMode(mode)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(onClick = onToggleSoftKeyboard) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Toggle soft keyboard")
                    }

                    IconButton(onClick = { showSpecialKeys = !showSpecialKeys }) {
                        Icon(Icons.Rounded.KeyboardCommandKey, contentDescription = "Special keys bar")
                    }

                    if (showFullscreenButton) {
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit full screen" else "Full screen"
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            isPaused = !isPaused
                            NativeQemuBridge.setPaused(isPaused)
                        }
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (isPaused) "Resume VM" else "Pause VM"
                        )
                    }
                }
            }
        }
    }

    if (showMonitorSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var consoleCommand by remember { mutableStateOf("") }

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "${vmConfig.name} · SDL2 Viewport & Input",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Live metrics row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricChip(
                        label = "FPS",
                        value = String.format(Locale.US, "%.1f", telemetry.fps),
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = "Guest",
                        value = "${telemetry.guestWidth}x${telemetry.guestHeight}",
                        modifier = Modifier.weight(1f)
                    )
                    MetricChip(
                        label = "Scale",
                        value = scalingMode.label,
                        modifier = Modifier.weight(1f)
                    )
                }

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
                                    onClick = { updateScalingMode(mode) },
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
                                    onClick = { updateMouseMode(mode) },
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
                                onValueChange = { trackpadSensitivity = it },
                                valueRange = 0.5f..3.0f
                            )
                        }
                    }
                }

                Text(
                    text = "Engine: ${telemetry.engineMode} · GPU: ${telemetry.glRenderer}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Quick console text injector (sends SDL2 scancodes + Enter)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = consoleCommand,
                        onValueChange = { consoleCommand = it },
                        label = { Text("Send text to SDL2 guest") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = {
                            if (consoleCommand.isNotEmpty()) {
                                for (ch in consoleCommand) {
                                    val code = SdlScancodeMapper.asciiCharToSdlScancode(ch)
                                    NativeQemuBridge.tapScancode(code, ch.code)
                                }
                                NativeQemuBridge.tapScancode(SdlScancodeMapper.SCANCODE_RETURN, '\n'.code)
                                consoleCommand = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                    }
                }

                // Live Native Log Output
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Native Bridge & SDL2 Log",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        val logBody = if (telemetry.logs.isEmpty()) {
                            qemuCommandLine
                        } else {
                            telemetry.logs.takeLast(10).joinToString("\n")
                        }
                        Text(
                            text = logBody,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Power Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isPaused = false
                            NativeQemuBridge.resetVm()
                            showMonitorSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Hard Reset")
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
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Power Off")
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecialKeysStrip(
    stickyCtrl: Boolean,
    stickyAlt: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onTapScancode: (Int) -> Unit,
    onSendCtrlAltDel: () -> Unit
) {
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
                onClick = onToggleCtrl,
                label = { Text("Ctrl") }
            )
            FilterChip(
                selected = stickyAlt,
                onClick = onToggleAlt,
                label = { Text("Alt") }
            )
            val quickKeys = listOf(
                "Esc" to SdlScancodeMapper.SCANCODE_ESCAPE,
                "Tab" to SdlScancodeMapper.SCANCODE_TAB,
                "Super" to SdlScancodeMapper.SCANCODE_LGUI,
                "↑" to SdlScancodeMapper.SCANCODE_UP,
                "↓" to SdlScancodeMapper.SCANCODE_DOWN,
                "←" to SdlScancodeMapper.SCANCODE_LEFT,
                "→" to SdlScancodeMapper.SCANCODE_RIGHT,
                "F1" to SdlScancodeMapper.SCANCODE_F1,
                "F2" to SdlScancodeMapper.SCANCODE_F2,
                "F8" to SdlScancodeMapper.SCANCODE_F8,
                "F12" to SdlScancodeMapper.SCANCODE_F12
            )
            quickKeys.forEach { (label, scancode) ->
                FilterChip(
                    selected = false,
                    onClick = { onTapScancode(scancode) },
                    label = { Text(label) }
                )
            }
            FilterChip(
                selected = false,
                onClick = onSendCtrlAltDel,
                label = { Text("Ctrl+Alt+Del") }
            )
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
