package com.qemusdl.app.ui.viewport

import android.util.Rational
import androidx.compose.runtime.Immutable
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

/**
 * Foldable posture state for the live SDL2 VM viewport.
 * When [isTabletop] is true, the guest display is pinned to `0..hingeTopPx` above the horizontal
 * crease and the Tabletop Mini-Laptop Deck occupies `hingeBottomPx..windowHeight`.
 */
@Immutable
data class ViewportPostureState(
    val isTabletop: Boolean = false,
    val hingeTopPx: Int = 0,
    val hingeBottomPx: Int = 0
)

/**
 * Visual guest framebuffer rectangle inside [org.libsdl.app.SDLSurface] in surface pixels,
 * used to detect unused bottom letterbox margins on nearly-square unfolded foldable displays.
 */
@Immutable
data class ViewportBoundsState(
    val leftPx: Float = 0f,
    val topPx: Float = 0f,
    val rightPx: Float = 0f,
    val bottomPx: Float = 0f,
    val surfaceWidthPx: Float = 0f,
    val surfaceHeightPx: Float = 0f,
    val guestWidth: Int = 1280,
    val guestHeight: Int = 720
) {
    val bottomLetterboxPx: Float
        get() = (surfaceHeightPx - bottomPx).coerceAtLeast(0f)
}

internal object FoldableViewportHelper {
    const val MIN_TABLETOP_TOP_PANE_PX = 180
    const val MIN_TABLETOP_BOTTOM_DECK_PX = 220
    const val MIN_LETTERBOX_DOCK_DP = 84f

    // Android Picture-in-Picture requires aspect ratio strictly within [1/2.39, 2.39/1]
    private const val MIN_PIP_RATIO = 0.42f
    private const val MAX_PIP_RATIO = 2.38f

    fun resolveTabletopPosture(
        layoutInfo: WindowLayoutInfo,
        windowHeightPx: Int
    ): ViewportPostureState {
        val horizontalFold = layoutInfo.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .firstOrNull { feature ->
                feature.state == FoldingFeature.State.HALF_OPENED &&
                    feature.orientation == FoldingFeature.Orientation.HORIZONTAL
            } ?: return ViewportPostureState()

        return resolveTabletopFromHingeBounds(
            isHalfOpened = true,
            isHorizontal = true,
            hingeTopPx = horizontalFold.bounds.top,
            hingeBottomPx = horizontalFold.bounds.bottom,
            windowHeightPx = windowHeightPx
        )
    }

    fun resolveTabletopFromHingeBounds(
        isHalfOpened: Boolean,
        isHorizontal: Boolean,
        hingeTopPx: Int,
        hingeBottomPx: Int,
        windowHeightPx: Int
    ): ViewportPostureState {
        if (!isHalfOpened || !isHorizontal || windowHeightPx <= 0) {
            return ViewportPostureState()
        }
        val safeTop = hingeTopPx.coerceIn(0, windowHeightPx)
        val safeBottom = hingeBottomPx.coerceIn(safeTop, windowHeightPx)
        val bottomDeckHeight = windowHeightPx - safeBottom
        if (safeTop < MIN_TABLETOP_TOP_PANE_PX || bottomDeckHeight < MIN_TABLETOP_BOTTOM_DECK_PX) {
            return ViewportPostureState()
        }
        return ViewportPostureState(
            isTabletop = true,
            hingeTopPx = safeTop,
            hingeBottomPx = safeBottom
        )
    }

    fun shouldUseLetterboxDock(
        isTabletop: Boolean,
        isInPip: Boolean,
        bottomLetterboxDp: Float
    ): Boolean = !isTabletop && !isInPip && bottomLetterboxDp >= MIN_LETTERBOX_DOCK_DP

    /**
     * Computes a safe (width, height) pair clamped to Android PiP's supported aspect ratio bounds.
     */
    fun computePipAspectRatioPair(guestWidth: Int, guestHeight: Int): Pair<Int, Int> {
        val w = guestWidth.coerceAtLeast(1)
        val h = guestHeight.coerceAtLeast(1)
        val ratio = w.toFloat() / h.toFloat()
        return when {
            ratio < MIN_PIP_RATIO -> (MIN_PIP_RATIO * 1000f).roundToInt() to 1000
            ratio > MAX_PIP_RATIO -> (MAX_PIP_RATIO * 1000f).roundToInt() to 1000
            else -> w to h
        }
    }

    fun computePipAspectRatio(guestWidth: Int, guestHeight: Int): Rational {
        val (w, h) = computePipAspectRatioPair(guestWidth, guestHeight)
        return Rational(w, h)
    }
}
