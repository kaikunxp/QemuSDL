package org.libsdl.app;


import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;


/**
    SDLSurface. This is what we draw on, so we need to know when it's created
    in order to do anything useful.

    Because of this, that's where we set up the SDL thread
*/
public class SDLSurface extends SurfaceView implements SurfaceHolder.Callback,
    View.OnKeyListener, View.OnTouchListener, SensorEventListener  {

    // Sensors
    protected SensorManager mSensorManager;
    protected Display mDisplay;

    // Keep track of the surface size to normalize touch events
    protected float mWidth, mHeight;

    // Guest logical resolution (dynamically synced from SDL_RenderGetLogicalSize)
    protected int mGuestWidth = 1280;
    protected int mGuestHeight = 720;
    // Runtime scaling mode (0 = FIT_ASPECT, 1 = FILL_STRETCH, 2 = INTEGER_1X1)
    protected int mScalingMode = 0;

    // Mouse emulation mode (0 = TRACKPAD_RELATIVE, 1 = DIRECT_TOUCH, 2 = DIRECT_HOVER_TAP)
    protected int mMouseEmulationMode = 0;
    protected float mTrackpadSensitivity = 1.4f;
    private float mLastTouchX = 0f;
    private float mLastTouchY = 0f;
    private float mDownTouchX = 0f;
    private float mDownTouchY = 0f;
    private long mDownTimeMs = 0L;
    private int mMaxPointers = 1;
    private boolean mMovedBeyondSlop = false;
    // Normalized guest cursor position in [0.001f, 0.999f] within the active SDL logical viewport
    private float mCursorNormX = 0.5f;
    private float mCursorNormY = 0.5f;
    // True when guest uses a relative mouse (e.g. Mac OS 9 usb-mouse/adb-mouse or FreeDOS PS/2) without usb-tablet
    private boolean mRelativeGuestPointer = false;
    private float mRelAccumX = 0f;
    private float mRelAccumY = 0f;
    // True when a physical mouse/trackpad or stylus is actively driving the cursor (hides redundant virtual touch cursor)
    private boolean mHardwareMouseActive = false;
    private boolean mPointerCaptured = false;
    private boolean mSyncTickerActive = false;
    private boolean mInitialMouseSeeded = false;
    // Stylus hover & drag state (Moto Pen Ultra, S Pen, USI styluses)
    private float mLastStylusX = 0f;
    private float mLastStylusY = 0f;
    private boolean mHasStylusHoverPos = false;
    private int mStylusActiveButton = 0;

    public void setRelativeGuestPointer(boolean relativeGuest) {
        mRelativeGuestPointer = relativeGuest;
        mRelAccumX = 0f;
        mRelAccumY = 0f;
        notifyCursorChanged();
    }

    public boolean isRelativeGuestPointer() {
        return mRelativeGuestPointer;
    }

    public interface PointerCaptureListener {
        void onPointerCaptureChanged(boolean captured);
    }

    private PointerCaptureListener mPointerCaptureListener;

    public void setPointerCaptureListener(PointerCaptureListener listener) {
        mPointerCaptureListener = listener;
        if (listener != null) {
            listener.onPointerCaptureChanged(mPointerCaptured);
        }
    }

    public boolean isPointerCaptureActive() {
        return mPointerCaptured;
    }

    public void setPointerCapture(boolean capture) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        if (capture) {
            if (!hasFocus()) {
                requestFocus();
            }
            requestPointerCapture();
        } else {
            releasePointerCapture();
        }
    }

    public void togglePointerCapture() {
        setPointerCapture(!mPointerCaptured);
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        mPointerCaptured = hasCapture;
        if (hasCapture) {
            mHardwareMouseActive = true;
            mRelAccumX = 0f;
            mRelAccumY = 0f;
        }
        notifyCursorChanged();
        if (mPointerCaptureListener != null) {
            mPointerCaptureListener.onPointerCaptureChanged(hasCapture);
        }
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();
        switch (action) {
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE: {
                float dx = event.getX();
                float dy = event.getY();
                if (dx != 0f || dy != 0f) {
                    SDLActivity.onNativeMouse(buttonState, MotionEvent.ACTION_MOVE, dx, dy, true);
                }
                return true;
            }
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_BUTTON_PRESS:
                SDLActivity.onNativeMouse(buttonState, MotionEvent.ACTION_DOWN, 0f, 0f, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_BUTTON_RELEASE:
            case MotionEvent.ACTION_CANCEL:
                SDLActivity.onNativeMouse(buttonState, MotionEvent.ACTION_UP, 0f, 0f, true);
                return true;
            case MotionEvent.ACTION_SCROLL: {
                float sx = event.getAxisValue(MotionEvent.AXIS_HSCROLL, 0);
                float sy = event.getAxisValue(MotionEvent.AXIS_VSCROLL, 0);
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, sx, sy, false);
                return true;
            }
            default:
                return super.onCapturedPointerEvent(event);
        }
    }

    /**
     * Maps raw physical mouse coordinates in SDLSurface space into the clamped inner SDL2 viewport
     * and suppresses the virtual touch cursor overlay so the host mouse cursor isn't duplicated.
     */
    float[] mapPhysicalMouseToViewport(float rawX, float rawY) {
        if (!mHardwareMouseActive) {
            mHardwareMouseActive = true;
            notifyCursorChanged();
        }
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float vpX = vp[0];
        float vpY = vp[1];
        float vpW = Math.max(1f, vp[2]);
        float vpH = Math.max(1f, vp[3]);
        mCursorNormX = clampNorm((rawX - vpX) / vpW);
        mCursorNormY = clampNorm((rawY - vpY) / vpH);
        return new float[] { vpX + mCursorNormX * vpW, vpY + mCursorNormY * vpH };
    }

    public interface CursorListener {
        void onCursorUpdated(float screenX, float screenY, boolean visible, boolean leftDown, boolean rightDown);
    }

    public interface ViewportBoundsListener {
        void onViewportBoundsUpdated(
                float leftPx,
                float topPx,
                float rightPx,
                float bottomPx,
                float surfaceWidthPx,
                float surfaceHeightPx,
                int guestWidth,
                int guestHeight
        );
    }

    private CursorListener mCursorListener;
    private ViewportBoundsListener mViewportBoundsListener;
    private boolean mLeftButtonDown = false;
    private boolean mRightButtonDown = false;

    // Is SurfaceView ready for rendering
    public boolean mIsSurfaceReady;

    private static float clampNorm(float v) {
        return Math.max(0.001f, Math.min(0.999f, v));
    }

    /**
     * Computes the inner rectangle [vpX, vpY, vpW, vpH] inside SDLSurface (0..mWidth, 0..mHeight)
     * where SDL2's UpdateLogicalSize (SDL_RenderSetLogicalSize) renders the guest framebuffer.
     */
    private void computeSdlInnerViewport(float[] outRect) {
        float surfW = Math.max(1f, mWidth > 1f ? mWidth : (float) getWidth());
        float surfH = Math.max(1f, mHeight > 1f ? mHeight : (float) getHeight());
        float guestW = Math.max(1f, (float) mGuestWidth);
        float guestH = Math.max(1f, (float) mGuestHeight);
        float sdlScale = Math.min(surfW / guestW, surfH / guestH);
        float vpW = Math.max(1f, (float) Math.floor(guestW * sdlScale));
        float vpH = Math.max(1f, (float) Math.floor(guestH * sdlScale));
        float vpX = (surfW - vpW) * 0.5f;
        float vpY = (surfH - vpH) * 0.5f;
        outRect[0] = vpX;
        outRect[1] = vpY;
        outRect[2] = vpW;
        outRect[3] = vpH;
    }

    public void setCursorListener(CursorListener listener) {
        mCursorListener = listener;
        notifyCursorChanged();
    }

    public void setViewportBoundsListener(ViewportBoundsListener listener) {
        mViewportBoundsListener = listener;
        notifyViewportBoundsChanged();
    }

    private void notifyViewportBoundsChanged() {
        if (mViewportBoundsListener == null) {
            return;
        }
        float surfW = Math.max(1f, mWidth > 1f ? mWidth : (float) getWidth());
        float surfH = Math.max(1f, mHeight > 1f ? mHeight : (float) getHeight());
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float centerX = surfW * 0.5f;
        float centerY = surfH * 0.5f;
        float sx = getScaleX();
        float sy = getScaleY();
        float visualLeft = Math.max(0f, centerX + (vp[0] - centerX) * sx);
        float visualTop = Math.max(0f, centerY + (vp[1] - centerY) * sy);
        float visualRight = Math.min(surfW, centerX + ((vp[0] + vp[2]) - centerX) * sx);
        float visualBottom = Math.min(surfH, centerY + ((vp[1] + vp[3]) - centerY) * sy);
        mViewportBoundsListener.onViewportBoundsUpdated(
                visualLeft,
                visualTop,
                visualRight,
                visualBottom,
                surfW,
                surfH,
                mGuestWidth,
                mGuestHeight
        );
    }

    private int activeVirtualButtonMask() {
        int mask = 0;
        if (mLeftButtonDown) mask |= MotionEvent.BUTTON_PRIMARY;
        if (mRightButtonDown) mask |= MotionEvent.BUTTON_SECONDARY;
        return mask;
    }

    /**
     * Drives the guest pointer from an external trackpad surface (e.g. Tabletop Mini-Laptop Deck).
     * Respects any currently held virtual mouse button for smooth click-and-drag across both
     * absolute (usb-tablet) and relative (Mac OS 9 / PS/2) guests.
     */
    public void dispatchTrackpadDelta(float rawDx, float rawDy) {
        if (mHardwareMouseActive && !mPointerCaptured) {
            mHardwareMouseActive = false;
        }
        float scaledDx = rawDx * mTrackpadSensitivity;
        float scaledDy = rawDy * mTrackpadSensitivity;
        int btnMask = activeVirtualButtonMask();
        int action = (btnMask != 0) ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;

        if (mRelativeGuestPointer) {
            mRelAccumX += scaledDx;
            mRelAccumY += scaledDy;
            int intDx = (int) mRelAccumX;
            int intDy = (int) mRelAccumY;
            mRelAccumX -= intDx;
            mRelAccumY -= intDy;
            if (intDx != 0 || intDy != 0) {
                SDLActivity.onNativeMouse(btnMask, action, intDx, intDy, true);
            }
            return;
        }

        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float vpX = vp[0];
        float vpY = vp[1];
        float vpW = Math.max(1f, vp[2]);
        float vpH = Math.max(1f, vp[3]);
        mCursorNormX = clampNorm(mCursorNormX + (scaledDx / vpW));
        mCursorNormY = clampNorm(mCursorNormY + (scaledDy / vpH));
        float sdlX = vpX + mCursorNormX * vpW;
        float sdlY = vpY + mCursorNormY * vpH;
        notifyCursorChanged();
        SDLActivity.onNativeMouse(btnMask, action, sdlX, sdlY, false);
    }

    /**
     * Dispatches 2-finger scroll from an external trackpad surface (e.g. Tabletop Mini-Laptop Deck).
     */
    public void dispatchTrackpadScroll(float dx, float dy) {
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, dx * 0.05f, -dy * 0.05f, false);
    }

    /**
     * Press or release a virtual mouse button (supports hold-to-drag on Tabletop trackpad buttons).
     */
    public void setVirtualMouseButton(boolean rightClick, boolean pressed) {
        if (rightClick) {
            if (mRightButtonDown == pressed) return;
            mRightButtonDown = pressed;
        } else {
            if (mLeftButtonDown == pressed) return;
            mLeftButtonDown = pressed;
        }
        notifyCursorChanged();
        int btnMask = activeVirtualButtonMask();
        int action = pressed ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
        if (mRelativeGuestPointer) {
            SDLActivity.onNativeMouse(btnMask, action, 0f, 0f, true);
            return;
        }
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float clickX = vp[0] + mCursorNormX * vp[2];
        float clickY = vp[1] + mCursorNormY * vp[3];
        if (pressed) {
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, clickX, clickY, false);
        }
        SDLActivity.onNativeMouse(btnMask, action, clickX, clickY, false);
    }

    /**
     * Returns true when a MotionEvent originates from a stylus / eraser (e.g. Moto Pen Ultra, S Pen).
     */
    static boolean isStylusEvent(MotionEvent event) {
        int count = event.getPointerCount();
        if (count > 0) {
            int idx = Math.max(0, Math.min(event.getActionIndex(), count - 1));
            int tool = event.getToolType(idx);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                return true;
            }
        }
        return event.isFromSource(InputDevice.SOURCE_STYLUS) &&
                (count == 0 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER);
    }

    /**
     * Handles stylus air-hover movement and stylus barrel-button clicks (mapped to right-click).
     */
    boolean handleStylusHover(MotionEvent event) {
        int action = event.getActionMasked();
        float rawX = event.getX(0);
        float rawY = event.getY(0);

        if (!mHardwareMouseActive) {
            mHardwareMouseActive = true;
            notifyCursorChanged();
        }

        if (action == MotionEvent.ACTION_HOVER_EXIT) {
            mHasStylusHoverPos = false;
            return true;
        }

        if (action == MotionEvent.ACTION_HOVER_ENTER) {
            mLastStylusX = rawX;
            mLastStylusY = rawY;
            mHasStylusHoverPos = true;
            if (!mRelativeGuestPointer) {
                float[] mapped = mapPhysicalMouseToViewport(rawX, rawY);
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mapped[0], mapped[1], false);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            if (mRelativeGuestPointer) {
                if (mHasStylusHoverPos) {
                    float dx = (rawX - mLastStylusX) * mTrackpadSensitivity;
                    float dy = (rawY - mLastStylusY) * mTrackpadSensitivity;
                    mRelAccumX += dx;
                    mRelAccumY += dy;
                    int intDx = (int) mRelAccumX;
                    int intDy = (int) mRelAccumY;
                    mRelAccumX -= intDx;
                    mRelAccumY -= intDy;
                    if (intDx != 0 || intDy != 0) {
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, intDx, intDy, true);
                    }
                }
                mLastStylusX = rawX;
                mLastStylusY = rawY;
                mHasStylusHoverPos = true;
            } else {
                float[] mapped = mapPhysicalMouseToViewport(rawX, rawY);
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mapped[0], mapped[1], false);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            boolean barrelRight = (event.getButtonState() &
                    (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
            int btn = (action == MotionEvent.ACTION_BUTTON_PRESS)
                    ? (barrelRight ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY)
                    : 0;
            int sdlAction = (action == MotionEvent.ACTION_BUTTON_PRESS)
                    ? MotionEvent.ACTION_DOWN
                    : MotionEvent.ACTION_UP;
            if (mRelativeGuestPointer) {
                SDLActivity.onNativeMouse(btn, sdlAction, 0f, 0f, true);
            } else {
                float[] mapped = mapPhysicalMouseToViewport(rawX, rawY);
                if (sdlAction == MotionEvent.ACTION_DOWN) {
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mapped[0], mapped[1], false);
                }
                SDLActivity.onNativeMouse(btn, sdlAction, mapped[0], mapped[1], false);
            }
            return true;
        }
        return false;
    }

    private void notifyCursorChanged() {
        if (mCursorListener != null) {
            float surfW = Math.max(1f, mWidth > 1f ? mWidth : (float) getWidth());
            float surfH = Math.max(1f, mHeight > 1f ? mHeight : (float) getHeight());
            float[] vp = new float[4];
            computeSdlInnerViewport(vp);
            float vpX = vp[0];
            float vpY = vp[1];
            float vpW = vp[2];
            float vpH = vp[3];

            float sdlX = vpX + mCursorNormX * vpW;
            float sdlY = vpY + mCursorNormY * vpH;
            float centerX = surfW * 0.5f;
            float centerY = surfH * 0.5f;
            float screenX = centerX + (sdlX - centerX) * getScaleX();
            float screenY = centerY + (sdlY - centerY) * getScaleY();
            // Hide the virtual touch cursor overlay for relative-mouse guests or active hardware mice/pointer capture
            boolean showVirtualCursor = !mRelativeGuestPointer && !mHardwareMouseActive && !mPointerCaptured;
            mCursorListener.onCursorUpdated(screenX, screenY, showVirtualCursor, mLeftButtonDown, mRightButtonDown);
        }
    }

    public void performVirtualClick(boolean rightClick) {
        final int btn = rightClick ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        if (rightClick) {
            mRightButtonDown = true;
        } else {
            mLeftButtonDown = true;
        }
        notifyCursorChanged();
        if (mRelativeGuestPointer) {
            SDLActivity.onNativeMouse(btn, MotionEvent.ACTION_DOWN, 0f, 0f, true);
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    mLeftButtonDown = false;
                    mRightButtonDown = false;
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true);
                }
            }, 65L);
            return;
        }
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        final float clickX = vp[0] + mCursorNormX * vp[2];
        final float clickY = vp[1] + mCursorNormY * vp[3];
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, clickX, clickY, false);
        SDLActivity.onNativeMouse(btn, MotionEvent.ACTION_DOWN, clickX, clickY, false);
        postDelayed(new Runnable() {
            @Override
            public void run() {
                mLeftButtonDown = false;
                mRightButtonDown = false;
                notifyCursorChanged();
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, clickX, clickY, false);
            }
        }, 55L);
    }

    private void syncViewportNow(int surfW, int surfH) {
        if (surfW <= 0 || surfH <= 0) {
            return;
        }
        try {
            long packed = com.qemusdl.app.sdl.NativeQemuBridge.syncSdlViewport(surfW, surfH);
            if (packed != 0L) {
                int liveW = (int) (packed >> 32);
                int liveH = (int) (packed & 0xFFFFFFFFL);
                if (liveW > 0 && liveH > 0 && (liveW != mGuestWidth || liveH != mGuestHeight)) {
                    mGuestWidth = liveW;
                    mGuestHeight = liveH;
                    applyViewScaling();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable mViewportSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mSyncTickerActive) {
                return;
            }
            int surfW = Math.round(mWidth > 1f ? mWidth : (float) getWidth());
            int surfH = Math.round(mHeight > 1f ? mHeight : (float) getHeight());
            syncViewportNow(surfW, surfH);
            postDelayed(this, 180L);
        }
    };

    public void requestViewportRescale() {
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            mWidth = w;
            mHeight = h;
            Display activeDisplay = getDisplay();
            if (activeDisplay != null) {
                mDisplay = activeDisplay;
            }
            float refreshRate = (mDisplay != null) ? mDisplay.getRefreshRate() : 60.0f;
            if (mIsSurfaceReady) {
                SDLActivity.nativeSetScreenResolution(w, h, w, h, refreshRate);
                SDLActivity.onNativeResize();
                syncViewportNow(w, h);
            }
            applyViewScaling();
        }
    }

    public void setGuestResolution(int guestWidth, int guestHeight) {
        mGuestWidth = Math.max(320, guestWidth);
        mGuestHeight = Math.max(240, guestHeight);
        mCursorNormX = 0.5f;
        mCursorNormY = 0.5f;
        applyViewScaling();
    }

    public void setScalingMode(int scalingMode) {
        mScalingMode = scalingMode;
        applyViewScaling();
    }

    public int getScalingMode() {
        return mScalingMode;
    }

    public void setMouseEmulationMode(int mode) {
        mMouseEmulationMode = mode;
        notifyCursorChanged();
    }

    public int getMouseEmulationMode() {
        return mMouseEmulationMode;
    }

    public void setTrackpadSensitivity(float sensitivity) {
        mTrackpadSensitivity = Math.max(0.25f, Math.min(4.0f, sensitivity));
    }

    public float getTrackpadSensitivity() {
        return mTrackpadSensitivity;
    }

    private void applyViewScaling() {
        float surfW = Math.max(1f, mWidth > 1f ? mWidth : (float) getWidth());
        float surfH = Math.max(1f, mHeight > 1f ? mHeight : (float) getHeight());
        if (surfW <= 1f || surfH <= 1f || mGuestWidth <= 0 || mGuestHeight <= 0) {
            return;
        }
        // Keep scaling centered around the current view bounds across freeform window resizes
        if (Build.VERSION.SDK_INT >= 28) {
            resetPivot();
        } else {
            setPivotX(surfW * 0.5f);
            setPivotY(surfH * 0.5f);
        }
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float vpW = Math.max(1f, vp[2]);
        float vpH = Math.max(1f, vp[3]);
        float guestW = Math.max(1f, (float) mGuestWidth);
        float guestH = Math.max(1f, (float) mGuestHeight);

        if (mScalingMode == 1) {
            // FILL_STRETCH: SDL2 rendered the guest into the centered vpW x vpH inner viewport.
            // Scale by (surfW / vpW, surfH / vpH) around the center so the guest image stretches
            // edge-to-edge across 100% of surfW horizontally AND 100% of surfH vertically.
            setScaleX(surfW / vpW);
            setScaleY(surfH / vpH);
        } else if (mScalingMode == 2) {
            // INTEGER_1X1: exact guest pixels (1:1 or integer multiple)
            float minRatio = Math.min(surfW / guestW, surfH / guestH);
            float intScale = (minRatio >= 1.0f) ? (float) Math.floor(minRatio) : minRatio;
            setScaleX((guestW * intScale) / vpW);
            setScaleY((guestH * intScale) / vpH);
        } else {
            // FIT_ASPECT (default): SDL2's inner viewport [vpX, vpY, vpW, vpH] is already aspect-fitted!
            setScaleX(1.0f);
            setScaleY(1.0f);
        }
        notifyCursorChanged();
        notifyViewportBoundsChanged();
    }

    // Startup
    public SDLSurface(Context context) {
        super(context);
        getHolder().addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);

        mDisplay = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);

        setOnGenericMotionListener(SDLActivity.getMotionListener());

        // Some arbitrary defaults to avoid a potential division by zero
        mWidth = 1.0f;
        mHeight = 1.0f;

        mIsSurfaceReady = false;
    }

    public void handlePause() {
        enableSensor(Sensor.TYPE_ACCELEROMETER, false);
    }

    public void handleResume() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnKeyListener(this);
        setOnTouchListener(this);
        enableSensor(Sensor.TYPE_ACCELEROMETER, true);
    }

    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }

    // Called when we have a valid drawing surface
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.v("SDL", "surfaceCreated()");
        SDLActivity.onNativeSurfaceCreated();
    }

    // Called when we lose the surface
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.v("SDL", "surfaceDestroyed()");
        mSyncTickerActive = false;
        removeCallbacks(mViewportSyncRunnable);

        // Transition to pause, if needed
        SDLActivity.mNextNativeState = SDLActivity.NativeState.PAUSED;
        SDLActivity.handleNativeState();

        mIsSurfaceReady = false;
        SDLActivity.onNativeSurfaceDestroyed();
    }

    // Called when the surface is resized
    @Override
    public void surfaceChanged(SurfaceHolder holder,
                               int format, int width, int height) {
        Log.v("SDL", "surfaceChanged()");

        if (SDLActivity.mSingleton == null) {
            return;
        }

        mWidth = width;
        mHeight = height;
        int nDeviceWidth = width;
        int nDeviceHeight = height;

        synchronized(SDLActivity.getContext()) {
            // In case we're waiting on a size change after going fullscreen, send a notification.
            SDLActivity.getContext().notifyAll();
        }

        Log.v("SDL", "Window size: " + width + "x" + height);
        Log.v("SDL", "Device size: " + nDeviceWidth + "x" + nDeviceHeight);
        Display activeDisplay = getDisplay();
        if (activeDisplay != null) {
            mDisplay = activeDisplay;
        }
        float refreshRate = (mDisplay != null) ? mDisplay.getRefreshRate() : 60.0f;
        SDLActivity.nativeSetScreenResolution(width, height, nDeviceWidth, nDeviceHeight, refreshRate);
        SDLActivity.onNativeResize();

        // Prevent a screen distortion glitch,
        // for instance when the device is in Landscape and a Portrait App is resumed.
        boolean skip = false;
        int requestedOrientation = SDLActivity.mSingleton.getRequestedOrientation();

        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) {
            if (mWidth > mHeight) {
               skip = true;
            }
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            if (mWidth < mHeight) {
               skip = true;
            }
        }

        // Special Patch for Square Resolution: Black Berry Passport
        if (skip) {
           double min = Math.min(mWidth, mHeight);
           double max = Math.max(mWidth, mHeight);

           if (max / min < 1.20) {
              Log.v("SDL", "Don't skip on such aspect-ratio. Could be a square resolution.");
              skip = false;
           }
        }

        // Don't skip in MultiWindow or Desktop/Windowed environments (Samsung DeX, Moto Ready For, ChromeOS, external displays).
        if (skip) {
            boolean isMultiWindow = (Build.VERSION.SDK_INT >= 24) && SDLActivity.mSingleton.isInMultiWindowMode();
            boolean isDeskUi = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK;
            boolean isSecondaryDisplay = (mDisplay != null && mDisplay.getDisplayId() != Display.DEFAULT_DISPLAY);
            boolean isPcFeature = getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_PC);
            if (isMultiWindow || SDLActivity.isDeXMode() || SDLActivity.isChromebook() || isDeskUi || isSecondaryDisplay || isPcFeature) {
                Log.v("SDL", "Don't skip in Multi-Window / Desktop mode");
                skip = false;
            }
        }

        if (skip) {
           Log.v("SDL", "Skip .. Surface is not ready.");
           mIsSurfaceReady = false;
           return;
        }

        /* If the surface has been previously destroyed by onNativeSurfaceDestroyed, recreate it here */
        SDLActivity.onNativeSurfaceChanged();

        /* Surface is ready */
        mIsSurfaceReady = true;

        SDLActivity.mNextNativeState = SDLActivity.NativeState.RESUMED;
        SDLActivity.handleNativeState();

        applyViewScaling();
        syncViewportNow(width, height);
        com.qemusdl.app.sdl.NativeQemuBridge.notifySdlExposed();
        mSyncTickerActive = true;
        removeCallbacks(mViewportSyncRunnable);
        postDelayed(mViewportSyncRunnable, 180L);

        // Automatically seed initial mouse focus and trigger QEMU SDL2's mouse grab once when the guest window first opens
        if (!mInitialMouseSeeded) {
            mInitialMouseSeeded = true;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    mCursorNormX = 0.5f;
                    mCursorNormY = 0.5f;
                    float[] vp = new float[4];
                    computeSdlInnerViewport(vp);
                    float cx = vp[0] + 0.5f * vp[2];
                    float cy = vp[1] + 0.5f * vp[3];
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, cx, cy, false);
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, cx, cy, false);
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, cx, cy, false);
                }
            }, 700L);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
            mWidth = w;
            mHeight = h;
            applyViewScaling();
            if (mIsSurfaceReady) {
                Display activeDisplay = getDisplay();
                if (activeDisplay != null) {
                    mDisplay = activeDisplay;
                }
                float refreshRate = (mDisplay != null) ? mDisplay.getRefreshRate() : 60.0f;
                SDLActivity.nativeSetScreenResolution(w, h, w, h, refreshRate);
                SDLActivity.onNativeResize();
                syncViewportNow(w, h);
            }
        }
    }

    // Key events
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return SDLActivity.handleKeyEvent(v, keyCode, event, null);
    }

    // Touch events
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        /* Ref: http://developer.android.com/training/gestures/multi.html */
        int touchDevId = event.getDeviceId();
        final int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();
        float x, y;

        /*
         * Prevent id to be -1, since it's used in SDL internal for synthetic events
         * Appears when using Android emulator, eg:
         *  adb shell input mouse tap 100 100
         *  adb shell input touchscreen tap 100 100
         */
        if (touchDevId < 0) {
            touchDevId -= 1;
        }

        // Compute the inner SDL2 logical viewport [vpX, vpY, vpW, vpH] inside SDLSurface (0..mWidth, 0..mHeight).
        // Because Android's ViewGroup.dispatchTransformedTouchEvent already applies the inverse of
        // SDLSurface's (scaleX, scaleY) around the center of SDLSurface, event.getX(0) and event.getY(0)
        // are already in SDLSurface local coordinates where the guest viewport spans [vpX, vpX + vpW] x [vpY, vpY + vpH].
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        float vpX = vp[0];
        float vpY = vp[1];
        float vpW = Math.max(1f, vp[2]);
        float vpH = Math.max(1f, vp[3]);

        // Handle physical mouse / trackpad events across Android Desktop, GooglebookOS, ChromeOS, and DeX
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            if (!mHardwareMouseActive) {
                mHardwareMouseActive = true;
                notifyCursorChanged();
            }

            // On relative-pointer guests (e.g. Mac OS 9), clicking with a physical mouse automatically
            // enters Android Pointer Capture so deltas are unbounded and the host cursor doesn't duplicate.
            if (mRelativeGuestPointer && Build.VERSION.SDK_INT >= 26 && !mPointerCaptured &&
                    (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS)) {
                setPointerCapture(true);
            }

            int mouseButton = event.getButtonState();
            if (mouseButton == 0 && action == MotionEvent.ACTION_DOWN) {
                mouseButton = MotionEvent.BUTTON_PRIMARY;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mouseButton = event.getButtonState();
            }

            SDLGenericMotionListener_API12 motionListener = SDLActivity.getMotionListener();
            boolean rel = motionListener.inRelativeMode() || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
            x = motionListener.getEventX(event);
            y = motionListener.getEventY(event);
            if (!rel) {
                float[] mapped = mapPhysicalMouseToViewport(x, y);
                x = mapped[0];
                y = mapped[1];
            }

            int sdlAction = action;
            if (action == MotionEvent.ACTION_BUTTON_PRESS) {
                sdlAction = MotionEvent.ACTION_DOWN;
            } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
                sdlAction = MotionEvent.ACTION_UP;
            }
            SDLActivity.onNativeMouse(mouseButton, sdlAction, x, y, rel);
            return true;
        }

        // First-class Stylus handling (Moto Pen Ultra, S Pen, USI pen):
        // Always acts as a precision direct pointer even when finger touch is set to Trackpad mode,
        // and maps the stylus barrel button (BUTTON_STYLUS_PRIMARY) to native Right-Click.
        if (isStylusEvent(event)) {
            if (!mHardwareMouseActive) {
                mHardwareMouseActive = true;
                notifyCursorChanged();
            }
            boolean barrelRight = (event.getButtonState() &
                    (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
            float rawX = event.getX(0);
            float rawY = event.getY(0);

            if (mRelativeGuestPointer) {
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        mLastStylusX = rawX;
                        mLastStylusY = rawY;
                        mRelAccumX = 0f;
                        mRelAccumY = 0f;
                        mStylusActiveButton = barrelRight ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
                        SDLActivity.onNativeMouse(mStylusActiveButton, MotionEvent.ACTION_DOWN, 0f, 0f, true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = (rawX - mLastStylusX) * mTrackpadSensitivity;
                        float dy = (rawY - mLastStylusY) * mTrackpadSensitivity;
                        mLastStylusX = rawX;
                        mLastStylusY = rawY;
                        mRelAccumX += dx;
                        mRelAccumY += dy;
                        int intDx = (int) mRelAccumX;
                        int intDy = (int) mRelAccumY;
                        mRelAccumX -= intDx;
                        mRelAccumY -= intDy;
                        if (intDx != 0 || intDy != 0) {
                            SDLActivity.onNativeMouse(mStylusActiveButton, MotionEvent.ACTION_MOVE, intDx, intDy, true);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mStylusActiveButton = 0;
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true);
                        break;
                    default:
                        break;
                }
                return true;
            }

            float[] mapped = mapPhysicalMouseToViewport(rawX, rawY);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mStylusActiveButton = barrelRight ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, mapped[0], mapped[1], false);
                    SDLActivity.onNativeMouse(mStylusActiveButton, MotionEvent.ACTION_DOWN, mapped[0], mapped[1], false);
                    break;
                case MotionEvent.ACTION_MOVE:
                    SDLActivity.onNativeMouse(mStylusActiveButton, MotionEvent.ACTION_MOVE, mapped[0], mapped[1], false);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mStylusActiveButton = 0;
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mapped[0], mapped[1], false);
                    break;
                default:
                    break;
            }
            return true;
        }

        // Touch interaction restores the virtual touch cursor overlay (for absolute guests)
        if (mHardwareMouseActive) {
            mHardwareMouseActive = false;
            notifyCursorChanged();
        }

        if (mMouseEmulationMode == 0 || (mRelativeGuestPointer && mMouseEmulationMode == 2)) {
            // TRACKPAD_RELATIVE mode: relative pointer movement, 1-finger tap = Left Click, 2-finger tap = Right Click, 2-finger drag = Scroll
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastTouchX = event.getX(0);
                    mLastTouchY = event.getY(0);
                    mDownTouchX = mLastTouchX;
                    mDownTouchY = mLastTouchY;
                    mDownTimeMs = event.getEventTime();
                    mMaxPointers = 1;
                    mMovedBeyondSlop = false;
                    mRelAccumX = 0f;
                    mRelAccumY = 0f;
                    notifyCursorChanged();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mMaxPointers = Math.max(mMaxPointers, pointerCount);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float rawDx = (event.getX(0) - mLastTouchX) * mTrackpadSensitivity;
                    float rawDy = (event.getY(0) - mLastTouchY) * mTrackpadSensitivity;
                    mLastTouchX = event.getX(0);
                    mLastTouchY = event.getY(0);
                    if (Math.abs(event.getX(0) - mDownTouchX) > 10f || Math.abs(event.getY(0) - mDownTouchY) > 10f) {
                        mMovedBeyondSlop = true;
                    }
                    if (pointerCount >= 2) {
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, rawDx * 0.05f, -rawDy * 0.05f, false);
                    } else if (mRelativeGuestPointer) {
                        // Send true unbounded relative deltas so usb-mouse / adb-mouse / PS/2 guests never clamp at screen edges
                        mRelAccumX += rawDx;
                        mRelAccumY += rawDy;
                        int intDx = (int) mRelAccumX;
                        int intDy = (int) mRelAccumY;
                        mRelAccumX -= intDx;
                        mRelAccumY -= intDy;
                        if (intDx != 0 || intDy != 0) {
                            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, intDx, intDy, true);
                        }
                    } else {
                        mCursorNormX = clampNorm(mCursorNormX + (rawDx / vpW));
                        mCursorNormY = clampNorm(mCursorNormY + (rawDy / vpH));
                        float sdlX = vpX + mCursorNormX * vpW;
                        float sdlY = vpY + mCursorNormY * vpH;
                        notifyCursorChanged();
                        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, sdlX, sdlY, false);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    long duration = event.getEventTime() - mDownTimeMs;
                    if (!mMovedBeyondSlop && duration < 300L) {
                        performVirtualClick(mMaxPointers >= 2);
                    }
                    break;
                }
                default:
                    break;
            }
            return true;
        } else if (mMouseEmulationMode == 2) {
            // DIRECT_HOVER_TAP mode: move finger to hover cursor at absolute guest position, tap to click
            mCursorNormX = clampNorm((event.getX(0) - vpX) / vpW);
            mCursorNormY = clampNorm((event.getY(0) - vpY) / vpH);
            float sdlX = vpX + mCursorNormX * vpW;
            float sdlY = vpY + mCursorNormY * vpH;
            notifyCursorChanged();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mDownTouchX = event.getX(0);
                    mDownTouchY = event.getY(0);
                    mDownTimeMs = event.getEventTime();
                    mMaxPointers = 1;
                    mMovedBeyondSlop = false;
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, sdlX, sdlY, false);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mMaxPointers = Math.max(mMaxPointers, pointerCount);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX(0) - mDownTouchX) > 10f || Math.abs(event.getY(0) - mDownTouchY) > 10f) {
                        mMovedBeyondSlop = true;
                    }
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, sdlX, sdlY, false);
                    break;
                case MotionEvent.ACTION_UP: {
                    long duration = event.getEventTime() - mDownTimeMs;
                    if (!mMovedBeyondSlop && duration < 300L) {
                        performVirtualClick(mMaxPointers >= 2);
                    }
                    break;
                }
                default:
                    break;
            }
            return true;
        } else if (mRelativeGuestPointer) {
            // DIRECT_TOUCH mode (1) on relative-mouse guests: hold-and-drag with left button (great for Classic Mac menus & windows)
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastTouchX = event.getX(0);
                    mLastTouchY = event.getY(0);
                    mRelAccumX = 0f;
                    mRelAccumY = 0f;
                    mLeftButtonDown = true;
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, 0f, 0f, true);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float rawDx = (event.getX(0) - mLastTouchX) * mTrackpadSensitivity;
                    float rawDy = (event.getY(0) - mLastTouchY) * mTrackpadSensitivity;
                    mLastTouchX = event.getX(0);
                    mLastTouchY = event.getY(0);
                    mRelAccumX += rawDx;
                    mRelAccumY += rawDy;
                    int intDx = (int) mRelAccumX;
                    int intDy = (int) mRelAccumY;
                    mRelAccumX -= intDx;
                    mRelAccumY -= intDy;
                    if (intDx != 0 || intDy != 0) {
                        SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_MOVE, intDx, intDy, true);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mLeftButtonDown = false;
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true);
                    break;
                default:
                    break;
            }
            return true;
        } else {
            // DIRECT_TOUCH mode (1): direct absolute touch click and drag in guest logical coordinates
            mCursorNormX = clampNorm((event.getX(0) - vpX) / vpW);
            mCursorNormY = clampNorm((event.getY(0) - vpY) / vpH);
            float sdlX = vpX + mCursorNormX * vpW;
            float sdlY = vpY + mCursorNormY * vpH;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLeftButtonDown = true;
                    notifyCursorChanged();
                    // Move cursor first so PS/2 or USB tablet registers the target coordinate even before/during grab
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_HOVER_MOVE, sdlX, sdlY, false);
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, sdlX, sdlY, false);
                    break;
                case MotionEvent.ACTION_MOVE:
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_MOVE, sdlX, sdlY, false);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mLeftButtonDown = false;
                    notifyCursorChanged();
                    SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, sdlX, sdlY, false);
                    break;
                default:
                    break;
            }
            return true;
        }
   }

    // Sensor events
    public void enableSensor(int sensortype, boolean enabled) {
        // TODO: This uses getDefaultSensor - what if we have >1 accels?
        if (enabled) {
            mSensorManager.registerListener(this,
                            mSensorManager.getDefaultSensor(sensortype),
                            SensorManager.SENSOR_DELAY_GAME, null);
        } else {
            mSensorManager.unregisterListener(this,
                            mSensorManager.getDefaultSensor(sensortype));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {

            // Since we may have an orientation set, we won't receive onConfigurationChanged events.
            // We thus should check here.
            int newOrientation;

            float x, y;
            switch (mDisplay.getRotation()) {
                case Surface.ROTATION_90:
                    x = -event.values[1];
                    y = event.values[0];
                    newOrientation = SDLActivity.SDL_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    x = event.values[1];
                    y = -event.values[0];
                    newOrientation = SDLActivity.SDL_ORIENTATION_LANDSCAPE_FLIPPED;
                    break;
                case Surface.ROTATION_180:
                    x = -event.values[0];
                    y = -event.values[1];
                    newOrientation = SDLActivity.SDL_ORIENTATION_PORTRAIT_FLIPPED;
                    break;
                case Surface.ROTATION_0:
                default:
                    x = event.values[0];
                    y = event.values[1];
                    newOrientation = SDLActivity.SDL_ORIENTATION_PORTRAIT;
                    break;
            }

            if (newOrientation != SDLActivity.mCurrentOrientation) {
                SDLActivity.mCurrentOrientation = newOrientation;
                SDLActivity.onNativeOrientationChanged(newOrientation);
            }

            SDLActivity.onNativeAccel(-x / SensorManager.GRAVITY_EARTH,
                                      y / SensorManager.GRAVITY_EARTH,
                                      event.values[2] / SensorManager.GRAVITY_EARTH);


        }
    }
}
