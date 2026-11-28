package com.qemusdl.app.ui.viewport

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Snapshot of the Activity's current windowing environment used to decide whether to show
 * desktop-oriented controls (such as the Full Screen toggle button) and how system bars behave.
 */
data class WindowingEnvironmentState(
    val isDesktopPlatform: Boolean = false,
    val isWindowed: Boolean = false,
    val showFullscreenButton: Boolean = false,
    val isFullscreen: Boolean = false
)

/**
 * Pure decision logic for desktop / windowed platform detection, separated for unit testing.
 */
internal object DesktopWindowingDetector {
    fun evaluate(
        isDeXMode: Boolean,
        isChromebook: Boolean,
        hasPcFeature: Boolean,
        isDeskUiMode: Boolean,
        isSecondaryDisplay: Boolean,
        isInMultiWindowMode: Boolean,
        hasFreeformFeature: Boolean,
        currentWidthPx: Int,
        currentHeightPx: Int,
        maxWidthPx: Int,
        maxHeightPx: Int,
        previouslyDetectedWindowed: Boolean = false,
        isCurrentlyFullscreen: Boolean = false
    ): WindowingEnvironmentState {
        val isDesktopPlatform = isDeXMode || isChromebook || hasPcFeature || isDeskUiMode || isSecondaryDisplay
        val isSmallerThanDisplay =
            maxWidthPx > 0 && maxHeightPx > 0 &&
                currentWidthPx > 0 && currentHeightPx > 0 &&
                (currentWidthPx < (maxWidthPx * 0.96f) || currentHeightPx < (maxHeightPx * 0.96f))
        val isWindowed = isInMultiWindowMode || isSmallerThanDisplay || (hasFreeformFeature && isDesktopPlatform)
        val showFullscreenButton =
            isDesktopPlatform || isWindowed || previouslyDetectedWindowed || (isCurrentlyFullscreen && previouslyDetectedWindowed)
        return WindowingEnvironmentState(
            isDesktopPlatform = isDesktopPlatform,
            isWindowed = isWindowed,
            showFullscreenButton = showFullscreenButton,
            isFullscreen = isCurrentlyFullscreen
        )
    }
}

/**
 * Detects desktop/windowed Android environments (Samsung DeX, Moto Ready For / Smart Connect,
 * ChromeOS / ARC, Android Desktop Mode, Freeform / Multi-Window, external displays) and manages
 * entering/exiting true fullscreen mode (including hiding/showing freeform window caption bars).
 */
object DesktopWindowingHelper {

    fun isDeXMode(context: Context): Boolean {
        return runCatching {
            val config = context.resources.configuration
            val configClass = config.javaClass
            val enabledConst = configClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(configClass)
            val currentVal = configClass.getField("semDesktopModeEnabled").getInt(config)
            enabledConst == currentVal
        }.getOrDefault(false)
    }

    fun isChromebook(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature("org.chromium.arc") ||
            pm.hasSystemFeature("org.chromium.arc.device_management") ||
            Build.BRAND.equals("chromium", ignoreCase = true) ||
            Build.MANUFACTURER.equals("chromium", ignoreCase = true)
    }

    fun isDesktopOrWindowedEnvironment(activity: Activity): Boolean {
        val state = inspect(activity)
        return state.isDesktopPlatform || state.isWindowed
    }

    fun inspect(
        activity: Activity,
        previouslyDetectedWindowed: Boolean = false,
        isCurrentlyFullscreen: Boolean = false
    ): WindowingEnvironmentState {
        val pm = activity.packageManager
        val config = activity.resources.configuration
        val isDeskUiMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK
        val hasPcFeature = pm.hasSystemFeature(PackageManager.FEATURE_PC)
        val hasFreeformFeature = pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        val isSecondaryDisplay = runCatching {
            val displayId = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
            displayId != Display.DEFAULT_DISPLAY
        }.getOrDefault(false)

        val windowManager = activity.windowManager
        val currentBounds = windowManager.currentWindowMetrics.bounds
        val maxBounds = windowManager.maximumWindowMetrics.bounds

        return DesktopWindowingDetector.evaluate(
            isDeXMode = isDeXMode(activity),
            isChromebook = isChromebook(activity),
            hasPcFeature = hasPcFeature,
            isDeskUiMode = isDeskUiMode,
            isSecondaryDisplay = isSecondaryDisplay,
            isInMultiWindowMode = activity.isInMultiWindowMode,
            hasFreeformFeature = hasFreeformFeature,
            currentWidthPx = currentBounds.width(),
            currentHeightPx = currentBounds.height(),
            maxWidthPx = maxBounds.width(),
            maxHeightPx = maxBounds.height(),
            previouslyDetectedWindowed = previouslyDetectedWindowed,
            isCurrentlyFullscreen = isCurrentlyFullscreen
        )
    }

    /**
     * Applies or clears fullscreen mode for the given [activity].
     * On ChromeOS, Samsung DeX, Moto Ready For, and Android Desktop Mode, hiding
     * `systemBars() | captionBar()` collapses the desktop title bar and taskbar into true fullscreen.
     */
    fun applyFullscreen(activity: Activity, fullscreen: Boolean) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val barTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.captionBar()
        if (fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(barTypes)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        } else {
            controller.show(barTypes)
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        }
    }
}
