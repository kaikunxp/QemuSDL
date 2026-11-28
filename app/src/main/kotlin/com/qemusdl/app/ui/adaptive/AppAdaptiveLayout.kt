package com.qemusdl.app.ui.adaptive

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import com.qemusdl.app.ui.viewport.DesktopWindowingHelper

/**
 * Three-tier adaptive presentation tier:
 * - [PHONE]: Compact portrait/narrow windows (< 600dp) — preserves the classic mobile QemuSDL layout.
 * - [FOLDABLE_TABLET]: Unfolded foldables & touch tablets (>= 600dp) — navigation rail + 2-pane Nav3 list-detail.
 * - [DESKTOP]: GooglebookOS / Android Desktop / ChromeOS / laptop workstations — permanent sidebar,
 *   2-pane inspector/editor workspace, desktop keyboard shortcuts, tooltips, and pointer hover cursors.
 */
enum class AppFormFactor {
    PHONE,
    FOLDABLE_TABLET,
    DESKTOP
}

@Immutable
data class AppAdaptiveInfo(
    val formFactor: AppFormFactor,
    val windowWidthDp: Int,
    val windowHeightDp: Int,
    val isDesktopOs: Boolean,
    val hasPhysicalKeyboard: Boolean,
    val hasFinePointer: Boolean
) {
    val isPhone: Boolean get() = formFactor == AppFormFactor.PHONE
    val isFoldableOrTablet: Boolean get() = formFactor == AppFormFactor.FOLDABLE_TABLET
    val isDesktop: Boolean get() = formFactor == AppFormFactor.DESKTOP
    val supportsMultiPane: Boolean get() = formFactor != AppFormFactor.PHONE && windowWidthDp >= 600
}

/**
 * Pure decision logic for resolving [AppFormFactor], separated for deterministic unit testing.
 */
internal object AppFormFactorResolver {
    const val COMPACT_WIDTH_BREAKPOINT_DP = 600
    const val EXPANDED_WIDTH_BREAKPOINT_DP = 840

    fun resolve(
        windowWidthDp: Int,
        isDesktopOs: Boolean,
        hasPhysicalKeyboard: Boolean,
        hasFinePointer: Boolean
    ): AppFormFactor {
        if (windowWidthDp < COMPACT_WIDTH_BREAKPOINT_DP) {
            return AppFormFactor.PHONE
        }
        val isDesktopWorkstation =
            isDesktopOs || (windowWidthDp >= EXPANDED_WIDTH_BREAKPOINT_DP && hasPhysicalKeyboard && hasFinePointer)
        return if (isDesktopWorkstation) {
            AppFormFactor.DESKTOP
        } else {
            AppFormFactor.FOLDABLE_TABLET
        }
    }
}

private fun detectInputCapabilities(configuration: Configuration): Pair<Boolean, Boolean> {
    var hasPhysicalKeyboard =
        configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
            configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    var hasFinePointer = false
    runCatching {
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            if (device.isVirtual) continue
            if (device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                hasPhysicalKeyboard = true
            }
            if (device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            ) {
                hasFinePointer = true
            }
        }
    }
    return hasPhysicalKeyboard to hasFinePointer
}

@Composable
fun rememberAppAdaptiveInfo(): AppAdaptiveInfo {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize

    val widthDp = with(density) { containerSize.width.toDp().value.toInt() }
    val heightDp = with(density) { containerSize.height.toDp().value.toInt() }

    return remember(widthDp, heightDp, configuration.uiMode, configuration.keyboard, configuration.hardKeyboardHidden) {
        val isDesktopOs = DesktopWindowingHelper.isDesktopPlatform(context)
        val (hasPhysicalKeyboard, hasFinePointer) = detectInputCapabilities(configuration)
        val formFactor = AppFormFactorResolver.resolve(
            windowWidthDp = widthDp,
            isDesktopOs = isDesktopOs,
            hasPhysicalKeyboard = hasPhysicalKeyboard,
            hasFinePointer = hasFinePointer
        )
        AppAdaptiveInfo(
            formFactor = formFactor,
            windowWidthDp = widthDp,
            windowHeightDp = heightDp,
            isDesktopOs = isDesktopOs,
            hasPhysicalKeyboard = hasPhysicalKeyboard,
            hasFinePointer = hasFinePointer
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTooltipBox(
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = TooltipAnchorPosition.Above
        ),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content
    )
}

fun Modifier.desktopHandCursor(enabled: Boolean = true): Modifier =
    pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)

fun Modifier.desktopTextCursor(): Modifier =
    pointerHoverIcon(PointerIcon.Text)

fun Modifier.desktopWaitCursor(waiting: Boolean): Modifier =
    if (waiting) pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_WAIT)) else this
