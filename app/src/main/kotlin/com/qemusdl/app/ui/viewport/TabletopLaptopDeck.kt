package com.qemusdl.app.ui.viewport

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qemusdl.app.data.MouseEmulationMode
import com.qemusdl.app.data.SdlScalingMode
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.ui.adaptive.AppTooltipBox
import com.qemusdl.app.ui.adaptive.desktopHandCursor
import kotlin.math.abs

/**
 * Dedicated Mini-Laptop control deck displayed in the lower half of a half-folded foldable
 * (Tabletop posture). Provides a large multi-touch glass trackpad, hold-to-drag Left/Right mouse
 * buttons, sticky modifier & function keys, and quick VM actions without covering a single pixel
 * of the guest screen above the crease.
 */
@Composable
internal fun TabletopLaptopDeck(
    vmConfig: VmConfig,
    hingeBottomPx: Int,
    scalingMode: SdlScalingMode,
    leftButtonDown: Boolean,
    rightButtonDown: Boolean,
    onSelectScaling: (SdlScalingMode) -> Unit,
    onTrackpadDelta: (Float, Float) -> Unit,
    onTrackpadScroll: (Float, Float) -> Unit,
    onPerformClick: (Boolean) -> Unit,
    onVirtualMouseButton: (rightClick: Boolean, pressed: Boolean) -> Unit,
    onShowKeyboard: () -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onEnterPip: () -> Unit,
    onOpenMonitor: () -> Unit,
    onPowerOff: () -> Unit
) {
    val density = LocalDensity.current
    val hingeBottomDp = with(density) { hingeBottomPx.toDp() }

    var showScalingMenu by remember { mutableStateOf(false) }
    var stickyCtrl by remember { mutableStateOf(false) }
    var stickyAlt by remember { mutableStateOf(false) }
    var stickyMeta by remember { mutableStateOf(false) }

    fun releaseStickyModifiers() {
        if (stickyCtrl) {
            stickyCtrl = false
            onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (stickyAlt) {
            stickyAlt = false
            onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (stickyMeta) {
            stickyMeta = false
            onKeyUp(KeyEvent.KEYCODE_META_LEFT)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = hingeBottomDp),
        color = Color(0xFF0D111C),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Deck Header Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Laptop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${vmConfig.name} · Laptop Mode",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AppTooltipBox(tooltip = "Soft keyboard") {
                        IconButton(onClick = onShowKeyboard, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Keyboard, contentDescription = "Soft keyboard", modifier = Modifier.size(20.dp))
                        }
                    }
                    Box {
                        AppTooltipBox(tooltip = "Viewport scaling") {
                            IconButton(onClick = { showScalingMenu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.AspectRatio, contentDescription = "Scaling", modifier = Modifier.size(20.dp))
                            }
                        }
                        DropdownMenu(
                            expanded = showScalingMenu,
                            onDismissRequest = { showScalingMenu = false }
                        ) {
                            SdlScalingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(if (scalingMode == mode) "✓ ${mode.label}" else mode.label) },
                                    onClick = {
                                        showScalingMenu = false
                                        onSelectScaling(mode)
                                    }
                                )
                            }
                        }
                    }
                    AppTooltipBox(tooltip = "Picture-in-Picture") {
                        IconButton(onClick = onEnterPip, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Picture-in-Picture", modifier = Modifier.size(20.dp))
                        }
                    }
                    AppTooltipBox(tooltip = "VM Monitor & Settings") {
                        IconButton(onClick = onOpenMonitor, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "VM Settings", modifier = Modifier.size(20.dp))
                        }
                    }
                    AppTooltipBox(tooltip = "Power off VM") {
                        IconButton(onClick = onPowerOff, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.PowerSettingsNew,
                                contentDescription = "Power off VM",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Modifier & Function Key Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
                FilterChip(
                    selected = stickyMeta,
                    onClick = {
                        stickyMeta = !stickyMeta
                        if (stickyMeta) onKeyDown(KeyEvent.KEYCODE_META_LEFT)
                        else onKeyUp(KeyEvent.KEYCODE_META_LEFT)
                    },
                    label = { Text("Cmd") }
                )

                val deckKeys = listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE,
                    "Tab" to KeyEvent.KEYCODE_TAB,
                    "Enter" to KeyEvent.KEYCODE_ENTER,
                    "Bksp" to KeyEvent.KEYCODE_DEL,
                    "Del" to KeyEvent.KEYCODE_FORWARD_DEL,
                    "↑" to KeyEvent.KEYCODE_DPAD_UP,
                    "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                    "←" to KeyEvent.KEYCODE_DPAD_LEFT,
                    "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
                    "F1" to KeyEvent.KEYCODE_F1,
                    "F2" to KeyEvent.KEYCODE_F2,
                    "F4" to KeyEvent.KEYCODE_F4,
                    "F8" to KeyEvent.KEYCODE_F8,
                    "F10" to KeyEvent.KEYCODE_F10,
                    "F11" to KeyEvent.KEYCODE_F11,
                    "F12" to KeyEvent.KEYCODE_F12
                )
                deckKeys.forEach { (label, keyCode) ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            onKeyDown(keyCode)
                            onKeyUp(keyCode)
                            releaseStickyModifiers()
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

            // Large Multi-Touch Glass Trackpad
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var downTimeMs = 0L
                            var maxPointers = 0
                            var movedBeyondSlop = false
                            var lastCentroid: Offset? = null
                            var startCentroid: Offset? = null

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedChanges = event.changes.filter { it.pressed }
                                if (pressedChanges.isEmpty()) {
                                    if (maxPointers > 0) {
                                        val duration = System.currentTimeMillis() - downTimeMs
                                        if (!movedBeyondSlop && duration < 280L) {
                                            onPerformClick(maxPointers >= 2)
                                        }
                                    }
                                    maxPointers = 0
                                    movedBeyondSlop = false
                                    lastCentroid = null
                                    startCentroid = null
                                    continue
                                }

                                val centroid = pressedChanges
                                    .fold(Offset.Zero) { acc, c -> acc + c.position } / pressedChanges.size.toFloat()

                                if (maxPointers == 0) {
                                    downTimeMs = System.currentTimeMillis()
                                    maxPointers = pressedChanges.size
                                    movedBeyondSlop = false
                                    startCentroid = centroid
                                    lastCentroid = centroid
                                } else {
                                    if (pressedChanges.size > maxPointers) {
                                        maxPointers = pressedChanges.size
                                        // Reset centroid anchor when adding a 2nd finger to avoid a jump
                                        lastCentroid = centroid
                                    }
                                    val prev = lastCentroid
                                    if (prev != null) {
                                        val dx = centroid.x - prev.x
                                        val dy = centroid.y - prev.y
                                        val start = startCentroid ?: centroid
                                        if (abs(centroid.x - start.x) > 12f || abs(centroid.y - start.y) > 12f) {
                                            movedBeyondSlop = true
                                        }
                                        if (dx != 0f || dy != 0f) {
                                            if (pressedChanges.size >= 2) {
                                                onTrackpadScroll(dx, dy)
                                            } else {
                                                onTrackpadDelta(dx, dy)
                                            }
                                        }
                                    }
                                    lastCentroid = centroid
                                }
                                pressedChanges.forEach { it.consume() }
                            }
                        }
                    },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                        )
                        Text(
                            text = "Trackpad · 1-finger move/tap · 2-finger scroll/right-click",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            // Dedicated Tactile Hold-to-Drag Mouse Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TactileMouseButton(
                    label = "Left Click",
                    pressed = leftButtonDown,
                    onPressChanged = { pressed -> onVirtualMouseButton(false, pressed) },
                    modifier = Modifier.weight(1f)
                )
                TactileMouseButton(
                    label = "Right Click",
                    pressed = rightButtonDown,
                    onPressChanged = { pressed -> onVirtualMouseButton(true, pressed) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TactileMouseButton(
    label: String,
    pressed: Boolean,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPressChanged(true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        onPressChanged(false)
                    }
                }
            },
        shape = RoundedCornerShape(14.dp),
        color = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (pressed) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (pressed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Compact HUD dock seated strictly inside the unused bottom letterbox margin of a nearly-square
 * unfolded foldable display (e.g. 16:9 or 4:3 guest on an 8:7.2 inner screen) so zero pixels of
 * the guest OS taskbar or menu bar are obscured.
 */
@Composable
internal fun LetterboxDockedControls(
    viewportBottomPx: Float,
    mouseEmulationMode: MouseEmulationMode,
    scalingMode: SdlScalingMode,
    isPointerCaptured: Boolean,
    leftButtonDown: Boolean,
    rightButtonDown: Boolean,
    showFullscreenButton: Boolean,
    isFullscreen: Boolean,
    showSpecialKeys: Boolean,
    onToggleSpecialKeys: () -> Unit,
    onSelectMouseMode: (MouseEmulationMode) -> Unit,
    onSelectScaling: (SdlScalingMode) -> Unit,
    onPerformClick: (Boolean) -> Unit,
    onShowKeyboard: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenMonitor: () -> Unit,
    onPowerOff: () -> Unit,
    specialKeysStrip: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val topStartDp = with(density) { viewportBottomPx.toDp() }

    var showMouseMenu by remember { mutableStateOf(false) }
    var showScalingMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topStartDp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(visible = showSpecialKeys) {
                specialKeysStrip()
            }

            Surface(
                shape = RoundedCornerShape(100),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!isPointerCaptured && mouseEmulationMode != MouseEmulationMode.DIRECT_TOUCH) {
                        FilterChip(
                            selected = leftButtonDown,
                            onClick = { onPerformClick(false) },
                            label = { Text("L") }
                        )
                        FilterChip(
                            selected = rightButtonDown,
                            onClick = { onPerformClick(true) },
                            label = { Text("R") }
                        )
                        Spacer(Modifier.width(4.dp))
                    }

                    Box {
                        AppTooltipBox(tooltip = "Mouse mode") {
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
                                    contentDescription = "Mouse mode"
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showMouseMenu,
                            onDismissRequest = { showMouseMenu = false }
                        ) {
                            MouseEmulationMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(if (mouseEmulationMode == mode) "✓ ${mode.label}" else mode.label) },
                                    onClick = {
                                        showMouseMenu = false
                                        onSelectMouseMode(mode)
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
                                Icon(Icons.Rounded.AspectRatio, contentDescription = "Viewport scaling")
                            }
                        }
                        DropdownMenu(
                            expanded = showScalingMenu,
                            onDismissRequest = { showScalingMenu = false }
                        ) {
                            SdlScalingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(if (scalingMode == mode) "✓ ${mode.label}" else mode.label) },
                                    onClick = {
                                        showScalingMenu = false
                                        onSelectScaling(mode)
                                    }
                                )
                            }
                        }
                    }

                    AppTooltipBox(tooltip = "Soft keyboard") {
                        IconButton(onClick = onShowKeyboard, modifier = Modifier.desktopHandCursor()) {
                            Icon(Icons.Rounded.Keyboard, contentDescription = "Soft keyboard")
                        }
                    }
                    AppTooltipBox(tooltip = "Special keys bar") {
                        IconButton(onClick = onToggleSpecialKeys, modifier = Modifier.desktopHandCursor()) {
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
                    AppTooltipBox(tooltip = "VM Settings & Monitor") {
                        IconButton(onClick = onOpenMonitor, modifier = Modifier.desktopHandCursor()) {
                            Icon(Icons.Rounded.Terminal, contentDescription = "VM Settings & Monitor")
                        }
                    }
                    AppTooltipBox(tooltip = "Power off VM") {
                        IconButton(onClick = onPowerOff, modifier = Modifier.desktopHandCursor()) {
                            Icon(
                                Icons.Rounded.PowerSettingsNew,
                                contentDescription = "Power off VM",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
