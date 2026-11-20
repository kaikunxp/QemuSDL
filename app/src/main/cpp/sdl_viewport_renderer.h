#pragma once

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace qemusdl {

enum class ScalingMode : int {
    FitAspect = 0,
    FillStretch = 1,
    Integer1x1 = 2,
};

struct ViewportTelemetry {
    double fps = 0.0;
    double frameTimeMs = 0.0;
    uint64_t frameCount = 0;
    int guestWidth = 800;
    int guestHeight = 600;
    int surfaceWidth = 0;
    int surfaceHeight = 0;
    bool running = false;
    bool paused = false;
    std::string glRenderer = "Initializing...";
    std::string engineMode = "GLES3_SDL2_FALLBACK";
    std::vector<std::string> recentLogs;
};

class SdlViewportRenderer {
public:
    SdlViewportRenderer();
    ~SdlViewportRenderer();

    void configureSession(
        const std::string& vmName,
        int guestWidth,
        int guestHeight,
        const std::vector<std::string>& qemuArgs,
        const std::string& engineMode,
        const std::string& runtimeHint
    );

    void attachSurface(ANativeWindow* window);
    void resizeSurface(int width, int height);
    void detachSurface();

    void setScalingMode(ScalingMode mode);
    void setPaused(bool paused);
    void reset();
    void stop();

    void onKeyEvent(int sdlScancode, int androidKeycode, int unicodeChar, bool isDown);
    void onMouseMove(float normX, float normY, float dx, float dy, bool isRelative);
    void onMouseButton(int button, bool isDown);
    void onMouseWheel(float dx, float dy);

    void appendLog(const std::string& line);
    ViewportTelemetry getTelemetry() const;

private:
    void renderLoop();
    bool initEgl(ANativeWindow* window);
    void destroyEgl();
    bool initGlResources();
    void destroyGlResources();

    void renderFallbackFramebuffer(uint64_t tickMs);
    void drawRect(int x, int y, int w, int h, uint32_t abgr);
    void drawChar(int x, int y, char c, uint32_t fgAbgr, int scale);
    void drawText(int x, int y, const std::string& text, uint32_t fgAbgr, int scale = 1);
    void drawCursor(int cx, int cy);
    void computeViewportRect(int surfaceW, int surfaceH, int& vx, int& vy, int& vw, int& vh) const;

    mutable std::mutex mutex_;
    std::thread renderThread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};
    std::atomic<int> scalingMode_{0};

    ANativeWindow* pendingWindow_ = nullptr;
    ANativeWindow* activeWindow_ = nullptr;
    bool surfaceDirty_ = false;
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLConfig eglConfig_ = nullptr;

    GLuint shaderProgram_ = 0;
    GLuint textureId_ = 0;
    GLuint vao_ = 0;
    GLuint vbo_ = 0;

    int guestWidth_ = 800;
    int guestHeight_ = 600;
    std::vector<uint32_t> framebuffer_;

    std::string vmName_ = "QEMU x86_64";
    std::vector<std::string> qemuArgs_;
    std::string engineMode_ = "GLES3_SDL2_FALLBACK";
    std::string runtimeHint_;
    std::string glRenderer_ = "OpenGL ES 3.0";

    float cursorX_ = 400.0f;
    float cursorY_ = 300.0f;
    bool mouseLeftDown_ = false;
    bool mouseRightDown_ = false;
    int wheelDeltaAccum_ = 0;

    std::string typedBuffer_;
    std::string lastKeyEventStr_ = "None";
    uint64_t bootEpochMs_ = 0;
    int resetCount_ = 0;

    double fps_ = 0.0;
    double frameTimeMs_ = 0.0;
    uint64_t frameCount_ = 0;
    std::deque<std::string> logs_;
};

} // namespace qemusdl
