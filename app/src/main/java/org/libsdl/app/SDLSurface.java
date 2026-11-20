package org.libsdl.app;


import android.content.Context;
import android.content.pm.ActivityInfo;
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
    private boolean mSyncTickerActive = false;

    public interface CursorListener {
        void onCursorUpdated(float screenX, float screenY, boolean visible, boolean leftDown, boolean rightDown);
    }

    private CursorListener mCursorListener;
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
            mCursorListener.onCursorUpdated(screenX, screenY, true, mLeftButtonDown, mRightButtonDown);
        }
    }

    public void performVirtualClick(boolean rightClick) {
        float[] vp = new float[4];
        computeSdlInnerViewport(vp);
        final float clickX = vp[0] + mCursorNormX * vp[2];
        final float clickY = vp[1] + mCursorNormY * vp[3];
        final int btn = rightClick ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        if (rightClick) {
            mRightButtonDown = true;
        } else {
            mLeftButtonDown = true;
        }
        notifyCursorChanged();
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

    private final Runnable mViewportSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mSyncTickerActive) {
                return;
            }
            int surfW = Math.round(mWidth > 1f ? mWidth : (float) getWidth());
            int surfH = Math.round(mHeight > 1f ? mHeight : (float) getHeight());
            if (surfW > 0 && surfH > 0) {
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
            postDelayed(this, 180L);
        }
    };

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
        SDLActivity.nativeSetScreenResolution(width, height, nDeviceWidth, nDeviceHeight, mDisplay.getRefreshRate());
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

        // Don't skip in MultiWindow.
        if (skip) {
            if (Build.VERSION.SDK_INT >= 24 /* Android 7.0 (N) */) {
                if (SDLActivity.mSingleton.isInMultiWindowMode()) {
                    Log.v("SDL", "Don't skip in Multi-Window");
                    skip = false;
                }
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
        mSyncTickerActive = true;
        removeCallbacks(mViewportSyncRunnable);
        postDelayed(mViewportSyncRunnable, 180L);

        // Automatically seed initial mouse focus and trigger QEMU SDL2's mouse grab once the guest window opens
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

        // 12290 = Samsung DeX mode desktop mouse
        // 12290 = 0x3002 = 0x2002 | 0x1002 = SOURCE_MOUSE | SOURCE_TOUCHSCREEN
        // 0x2   = SOURCE_CLASS_POINTER
        if (event.getSource() == InputDevice.SOURCE_MOUSE || event.getSource() == (InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_TOUCHSCREEN)) {
            int mouseButton = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0 : MotionEvent.BUTTON_PRIMARY;
            try {
                Object object = event.getClass().getMethod("getButtonState").invoke(event);
                if (object != null) {
                    mouseButton = (Integer) object;
                }
            } catch(Exception ignored) {
            }

            // We need to check if we're in relative mouse mode and get the axis offset rather than the x/y values
            // if we are.  We'll leverage our existing mouse motion listener
            SDLGenericMotionListener_API12 motionListener = SDLActivity.getMotionListener();
            boolean rel = motionListener.inRelativeMode();
            x = motionListener.getEventX(event);
            y = motionListener.getEventY(event);
            if (!rel) {
                mCursorNormX = clampNorm((x - vpX) / vpW);
                mCursorNormY = clampNorm((y - vpY) / vpH);
                x = vpX + mCursorNormX * vpW;
                y = vpY + mCursorNormY * vpH;
                notifyCursorChanged();
            }

            SDLActivity.onNativeMouse(mouseButton, action, x, y, rel);
            return true;
        }

        if (mMouseEmulationMode == 0) {
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

    // Captured pointer events for API 26.
    public boolean onCapturedPointerEvent(MotionEvent event)
    {
        int action = event.getActionMasked();

        float x, y;
        switch (action) {
            case MotionEvent.ACTION_SCROLL:
                x = event.getAxisValue(MotionEvent.AXIS_HSCROLL, 0);
                y = event.getAxisValue(MotionEvent.AXIS_VSCROLL, 0);
                SDLActivity.onNativeMouse(0, action, x, y, false);
                return true;

            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                x = event.getX(0);
                y = event.getY(0);
                SDLActivity.onNativeMouse(0, action, x, y, true);
                return true;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:

                // Change our action value to what SDL's code expects.
                if (action == MotionEvent.ACTION_BUTTON_PRESS) {
                    action = MotionEvent.ACTION_DOWN;
                } else { /* MotionEvent.ACTION_BUTTON_RELEASE */
                    action = MotionEvent.ACTION_UP;
                }

                x = event.getX(0);
                y = event.getY(0);
                int button = event.getButtonState();

                SDLActivity.onNativeMouse(button, action, x, y, true);
                return true;
        }

        return false;
    }
}
