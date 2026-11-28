#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <stdlib.h>
#include <sys/stat.h>

#include <atomic>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "sdl_viewport_renderer.h"

#define LOG_TAG "QemuSDLBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using QemuInitFn = void (*)(int argc, char** argv);
using QemuMainLoopFn = int (*)(void);
using QemuCleanupFn = void (*)(void);
using SdlMainFn = int (*)(int argc, char** argv);

enum class QemuTargetArch {
    X86_64,
    AARCH64,
    PPC
};

struct LoadedNativeRuntime {
    void* sdlHandle = nullptr;
    void* qemuHandle = nullptr;
    bool sdlLoaded = false;
    bool qemuLoaded = false;
    bool qemuAarch64Loaded = false;
    bool qemuPpcLoaded = false;
    bool hasQemuInit = false;
    bool hasSdlMain = false;
    bool hasLibusb = false;
    bool hasLibusbWrap = false;
    std::string sdlPath;
    std::string qemuPath;
    std::string qemuAarch64Path;
    std::string qemuPpcPath;
    std::string detail;

    void close() {
        if (qemuHandle) {
            dlclose(qemuHandle);
            qemuHandle = nullptr;
        }
        if (sdlHandle) {
            dlclose(sdlHandle);
            sdlHandle = nullptr;
        }
        sdlLoaded = false;
        qemuLoaded = false;
        qemuAarch64Loaded = false;
        qemuPpcLoaded = false;
        hasQemuInit = false;
        hasSdlMain = false;
    }
};

std::mutex gSessionMutex;
std::unique_ptr<qemusdl::SdlViewportRenderer> gRenderer;
LoadedNativeRuntime gRuntime;
std::thread gQemuThread;
std::atomic<bool> gQemuRunning{false};

std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

std::string escapeJson(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '\"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) >= 0x20) out += c;
                break;
        }
    }
    return out;
}

bool isValidElfForCurrentAbi(const std::string& path) {
    if (path.empty()) return false;
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || st.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) {
        return false;
    }
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;
    Elf64_Ehdr hdr {};
    file.read(reinterpret_cast<char*>(&hdr), sizeof(hdr));
    if (!file) return false;
    if (hdr.e_ident[EI_MAG0] != ELFMAG0 ||
        hdr.e_ident[EI_MAG1] != ELFMAG1 ||
        hdr.e_ident[EI_MAG2] != ELFMAG2 ||
        hdr.e_ident[EI_MAG3] != ELFMAG3 ||
        hdr.e_ident[EI_CLASS] != ELFCLASS64) {
        return false;
    }
#if defined(__aarch64__)
    return hdr.e_machine == EM_AARCH64;
#elif defined(__x86_64__)
    return hdr.e_machine == EM_X86_64;
#else
    return true;
#endif
}

std::string findElfLib(const std::vector<std::string>& candidates) {
    for (const auto& path : candidates) {
        if (isValidElfForCurrentAbi(path)) {
            return path;
        }
    }
    return {};
}

LoadedNativeRuntime probeLibrariesMetadataOnly(const std::string& nativeLibDir, const std::string& customLibDir) {
    // Inspect ELF files on disk WITHOUT calling dlopen()/dlclose() in the UI process,
    // because libqemu-system-*.so spawns background RCU threads in .init_array
    // constructors that would SIGSEGV if unmapped by dlclose().
    LoadedNativeRuntime rt;
    std::vector<std::string> sdlCandidates;
    std::vector<std::string> qemuX86Candidates;
    std::vector<std::string> qemuArm64Candidates;
    std::vector<std::string> qemuPpcCandidates;

    if (!nativeLibDir.empty()) {
        sdlCandidates.push_back(nativeLibDir + "/libSDL2.so");
        qemuX86Candidates.push_back(nativeLibDir + "/libqemu-system-x86_64.so");
        qemuArm64Candidates.push_back(nativeLibDir + "/libqemu-system-aarch64.so");
        qemuPpcCandidates.push_back(nativeLibDir + "/libqemu-system-ppc.so");
    }
    if (!customLibDir.empty()) {
        sdlCandidates.push_back(customLibDir + "/libSDL2.so");
        qemuX86Candidates.push_back(customLibDir + "/libqemu-system-x86_64.so");
        qemuArm64Candidates.push_back(customLibDir + "/libqemu-system-aarch64.so");
        qemuPpcCandidates.push_back(customLibDir + "/libqemu-system-ppc.so");
    }

    rt.sdlPath = findElfLib(sdlCandidates);
    rt.sdlLoaded = !rt.sdlPath.empty();

    rt.qemuPath = findElfLib(qemuX86Candidates);
    rt.qemuLoaded = !rt.qemuPath.empty();

    rt.qemuAarch64Path = findElfLib(qemuArm64Candidates);
    rt.qemuAarch64Loaded = !rt.qemuAarch64Path.empty();

    rt.qemuPpcPath = findElfLib(qemuPpcCandidates);
    rt.qemuPpcLoaded = !rt.qemuPpcPath.empty();

    rt.hasQemuInit = rt.qemuLoaded || rt.qemuAarch64Loaded || rt.qemuPpcLoaded;
    rt.hasSdlMain = rt.qemuLoaded || rt.qemuAarch64Loaded || rt.qemuPpcLoaded;

    std::vector<std::string> libusbCandidates;
    if (!nativeLibDir.empty()) {
        libusbCandidates.push_back(nativeLibDir + "/libusb-1.0.so");
    }
    if (!customLibDir.empty()) {
        libusbCandidates.push_back(customLibDir + "/libusb-1.0.so");
    }
    std::string libusbPath = findElfLib(libusbCandidates);
    rt.hasLibusb = !libusbPath.empty();
    rt.hasLibusbWrap = rt.hasLibusb;

    std::vector<std::string> readyArchLibs;
    if (rt.qemuLoaded) readyArchLibs.emplace_back("libqemu-system-x86_64.so");
    if (rt.qemuAarch64Loaded) readyArchLibs.emplace_back("libqemu-system-aarch64.so");
    if (rt.qemuPpcLoaded) readyArchLibs.emplace_back("libqemu-system-ppc.so");

    if (rt.sdlLoaded && !readyArchLibs.empty()) {
        std::ostringstream detailSs;
        detailSs << "Native libSDL2.so";
        for (const auto& lib : readyArchLibs) {
            detailSs << " + " << lib;
        }
        detailSs << " ready for in-process SDL2 boot.";
        rt.detail = detailSs.str();
    } else if (rt.sdlLoaded) {
        rt.detail = "libSDL2.so found, awaiting libqemu-system-*.so (GLES3 viewport fallback active).";
    } else {
        rt.detail = "GLES3 SDL2-compatible viewport ready. Add libSDL2.so + libqemu-system-*.so to boot guest OSes.";
    }
    return rt;
}

LoadedNativeRuntime probeLibraries(const std::string& nativeLibDir, const std::string& customLibDir) {
    return probeLibrariesMetadataOnly(nativeLibDir, customLibDir);
}

LoadedNativeRuntime loadRuntimeForTarget(
    const std::string& nativeLibDir,
    const std::string& customLibDir,
    QemuTargetArch targetArch
) {
    // Dynamically dlopen ONLY the target architecture's QEMU shared object
    // (x86_64 vs aarch64 vs ppc) with RTLD_LOCAL so global QEMU symbols never collide.
    LoadedNativeRuntime rt = probeLibrariesMetadataOnly(nativeLibDir, customLibDir);
    if (!rt.sdlPath.empty()) {
        rt.sdlHandle = dlopen(rt.sdlPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    std::string targetQemuSo;
    switch (targetArch) {
        case QemuTargetArch::AARCH64:
            targetQemuSo = rt.qemuAarch64Path;
            break;
        case QemuTargetArch::PPC:
            targetQemuSo = rt.qemuPpcPath;
            break;
        case QemuTargetArch::X86_64:
        default:
            targetQemuSo = rt.qemuPath;
            break;
    }
    if (!targetQemuSo.empty()) {
        rt.qemuHandle = dlopen(targetQemuSo.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (rt.qemuHandle) {
            rt.hasQemuInit = dlsym(rt.qemuHandle, "qemu_init") != nullptr &&
                             dlsym(rt.qemuHandle, "qemu_main_loop") != nullptr;
            rt.hasSdlMain = dlsym(rt.qemuHandle, "SDL_main") != nullptr;
        }
    }
    return rt;
}

std::string getAbiName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeProbeRuntime(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jNativeLibDir,
    jstring jCustomLibDir
) {
    const std::string nativeLibDir = jstringToStd(env, jNativeLibDir);
    const std::string customLibDir = jstringToStd(env, jCustomLibDir);

    LoadedNativeRuntime rt = probeLibraries(nativeLibDir, customLibDir);
    std::ostringstream ss;
    ss << "{"
       << "\"abi\":\"" << escapeJson(getAbiName()) << "\","
       << "\"bridgeLoaded\":true,"
       << "\"sdl2Loaded\":" << (rt.sdlLoaded ? "true" : "false") << ","
       << "\"qemuLoaded\":" << (rt.qemuLoaded ? "true" : "false") << ","
       << "\"qemuAarch64Loaded\":" << (rt.qemuAarch64Loaded ? "true" : "false") << ","
       << "\"qemuPpcLoaded\":" << (rt.qemuPpcLoaded ? "true" : "false") << ","
       << "\"hasQemuEntry\":" << ((rt.hasQemuInit || rt.hasSdlMain) ? "true" : "false") << ","
       << "\"hasLibusb\":" << (rt.hasLibusb ? "true" : "false") << ","
       << "\"hasLibusbWrap\":" << (rt.hasLibusbWrap ? "true" : "false") << ","
       << "\"sdl2Path\":\"" << escapeJson(rt.sdlPath) << "\","
       << "\"qemuPath\":\"" << escapeJson(rt.qemuPath) << "\","
       << "\"qemuAarch64Path\":\"" << escapeJson(rt.qemuAarch64Path) << "\","
       << "\"qemuPpcPath\":\"" << escapeJson(rt.qemuPpcPath) << "\","
       << "\"detail\":\"" << escapeJson(rt.detail) << "\""
       << "}";
    rt.close();
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeStartSession(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jVmName,
    jint guestWidth,
    jint guestHeight,
    jobjectArray jQemuArgs,
    jstring jNativeLibDir,
    jstring jCustomLibDir
) {
    const std::string vmName = jstringToStd(env, jVmName);
    const std::string nativeLibDir = jstringToStd(env, jNativeLibDir);
    const std::string customLibDir = jstringToStd(env, jCustomLibDir);

    std::vector<std::string> args;
    if (jQemuArgs) {
        jsize count = env->GetArrayLength(jQemuArgs);
        args.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto jArg = static_cast<jstring>(env->GetObjectArrayElement(jQemuArgs, i));
            args.push_back(jstringToStd(env, jArg));
            env->DeleteLocalRef(jArg);
        }
    }

    QemuTargetArch targetArch = QemuTargetArch::X86_64;
    if (!args.empty()) {
        if (args[0].find("aarch64") != std::string::npos) {
            targetArch = QemuTargetArch::AARCH64;
        } else if (args[0].find("ppc") != std::string::npos) {
            targetArch = QemuTargetArch::PPC;
        }
    }

    // Configure SDL2 Android video/render/audio environment variables before loading SDL2/QEMU
    setenv("SDL_VIDEODRIVER", "android", 1);
    setenv("SDL_RENDER_DRIVER", "opengles2", 1);
    setenv("SDL_AUDIODRIVER", "openslES", 1);
    setenv("SDL_HINT_ANDROID_TRAP_BACK_BUTTON", "1", 1);
    setenv("SDL_HINT_MOUSE_RELATIVE_MODE_CENTER", "1", 1);

    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gRenderer) {
        gRenderer = std::make_unique<qemusdl::SdlViewportRenderer>();
    }

    gRuntime.close();
    gRuntime = loadRuntimeForTarget(nativeLibDir, customLibDir, targetArch);

    const bool canBootRealQemu =
        gRuntime.sdlHandle != nullptr && gRuntime.qemuHandle != nullptr &&
        (gRuntime.hasQemuInit || gRuntime.hasSdlMain);

    const std::string mode = canBootRealQemu ? "QEMU_SDL2_NATIVE" : "GLES3_SDL2_FALLBACK";
    gRenderer->configureSession(vmName, guestWidth, guestHeight, args, mode, gRuntime.detail);

    if (canBootRealQemu && !gQemuRunning.load()) {
        gQemuRunning.store(true);
        auto qemuHandle = gRuntime.qemuHandle;
        auto useQemuInit = gRuntime.hasQemuInit;
        auto copiedArgs = args;

        gQemuThread = std::thread([qemuHandle, useQemuInit, copiedArgs]() {
            std::vector<char*> argv;
            argv.reserve(copiedArgs.size() + 1);
            for (const auto& s : copiedArgs) {
                argv.push_back(const_cast<char*>(s.c_str()));
            }
            argv.push_back(nullptr);

            LOGI("Launching in-process %s with %zu args",
                 copiedArgs.empty() ? "qemu" : copiedArgs[0].c_str(),
                 copiedArgs.size());
            if (useQemuInit) {
                auto initFn = reinterpret_cast<QemuInitFn>(dlsym(qemuHandle, "qemu_init"));
                auto loopFn = reinterpret_cast<QemuMainLoopFn>(dlsym(qemuHandle, "qemu_main_loop"));
                auto cleanFn = reinterpret_cast<QemuCleanupFn>(dlsym(qemuHandle, "qemu_cleanup"));
                if (initFn && loopFn) {
                    initFn(static_cast<int>(copiedArgs.size()), argv.data());
                    loopFn();
                    if (cleanFn) cleanFn();
                }
            } else {
                auto sdlMainFn = reinterpret_cast<SdlMainFn>(dlsym(qemuHandle, "SDL_main"));
                if (sdlMainFn) {
                    sdlMainFn(static_cast<int>(copiedArgs.size()), argv.data());
                }
            }
            gQemuRunning.store(false);
        });
        gQemuThread.detach();
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeAttachSurface(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject surface
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (!gRenderer) {
        gRenderer = std::make_unique<qemusdl::SdlViewportRenderer>();
    }
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    gRenderer->attachSurface(window);
    if (window) {
        ANativeWindow_release(window); // Renderer acquired its own ref
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeResizeSurface(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint width,
    jint height
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->resizeSurface(width, height);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeDetachSurface(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->detachSurface();
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSendKeyEvent(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint sdlScancode,
    jint androidKeycode,
    jint unicodeChar,
    jboolean isDown
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->onKeyEvent(sdlScancode, androidKeycode, unicodeChar, isDown == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSendMouseMove(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat normX,
    jfloat normY,
    jfloat dx,
    jfloat dy,
    jboolean isRelative
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->onMouseMove(normX, normY, dx, dy, isRelative == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSendMouseButton(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint button,
    jboolean isDown
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->onMouseButton(button, isDown == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSendMouseWheel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jfloat dx,
    jfloat dy
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->onMouseWheel(dx, dy);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSetScalingMode(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint mode
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->setScalingMode(static_cast<qemusdl::ScalingMode>(mode));
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSetPaused(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean paused
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->setPaused(paused == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeResetVm(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->reset();
    }
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeStopSession(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    std::lock_guard<std::mutex> lock(gSessionMutex);
    if (gRenderer) {
        gRenderer->stop();
        gRenderer.reset();
    }
}

JNIEXPORT jstring JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeGetTelemetryJson(
    JNIEnv* env,
    jobject /*thiz*/
) {
    qemusdl::ViewportTelemetry t;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        if (gRenderer) {
            t = gRenderer->getTelemetry();
        }
    }

    std::ostringstream ss;
    ss << "{"
       << "\"fps\":" << t.fps << ","
       << "\"frameTimeMs\":" << t.frameTimeMs << ","
       << "\"frameCount\":" << t.frameCount << ","
       << "\"guestWidth\":" << t.guestWidth << ","
       << "\"guestHeight\":" << t.guestHeight << ","
       << "\"surfaceWidth\":" << t.surfaceWidth << ","
       << "\"surfaceHeight\":" << t.surfaceHeight << ","
       << "\"running\":" << (t.running ? "true" : "false") << ","
       << "\"paused\":" << (t.paused ? "true" : "false") << ","
       << "\"glRenderer\":\"" << escapeJson(t.glRenderer) << "\","
       << "\"engineMode\":\"" << escapeJson(t.engineMode) << "\","
       << "\"logs\":[";
    for (size_t i = 0; i < t.recentLogs.size(); ++i) {
        if (i > 0) ss << ",";
        ss << "\"" << escapeJson(t.recentLogs[i]) << "\"";
    }
    ss << "]}";
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jlong JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeSyncSdlViewport(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint surfaceWidth,
    jint surfaceHeight
) {
    using GetWindowFromIdFn = void* (*)(uint32_t);
    using GetWindowSizeFn = void (*)(void*, int*, int*);
    using GetWindowDataFn = void* (*)(void*, const char*);
    using GetRendererFn = void* (*)(void*);
    using GetRendererOutputSizeFn = int (*)(void*, int*, int*);
    using RenderGetLogicalSizeFn = void (*)(void*, int*, int*);
    using AndroidSetScreenResFn = void (*)(int, int, int, int, float);
    using AndroidSendResizeFn = void (*)(void*);

    static auto fnGetWin = reinterpret_cast<GetWindowFromIdFn>(dlsym(RTLD_DEFAULT, "SDL_GetWindowFromID"));
    static auto fnGetWinSize = reinterpret_cast<GetWindowSizeFn>(dlsym(RTLD_DEFAULT, "SDL_GetWindowSize"));
    static auto fnGetWinData = reinterpret_cast<GetWindowDataFn>(dlsym(RTLD_DEFAULT, "SDL_GetWindowData"));
    static auto fnGetRenderer = reinterpret_cast<GetRendererFn>(dlsym(RTLD_DEFAULT, "SDL_GetRenderer"));
    static auto fnGetRendererOut = reinterpret_cast<GetRendererOutputSizeFn>(dlsym(RTLD_DEFAULT, "SDL_GetRendererOutputSize"));
    static auto fnGetLogical = reinterpret_cast<RenderGetLogicalSizeFn>(dlsym(RTLD_DEFAULT, "SDL_RenderGetLogicalSize"));
    static auto fnSetRes = reinterpret_cast<AndroidSetScreenResFn>(dlsym(RTLD_DEFAULT, "Android_SetScreenResolution"));
    static auto fnSendResize = reinterpret_cast<AndroidSendResizeFn>(dlsym(RTLD_DEFAULT, "Android_SendResize"));

    if (!fnGetWin) {
        return 0L;
    }

    void* window = nullptr;
    for (uint32_t id = 1; id <= 8; ++id) {
        window = fnGetWin(id);
        if (window != nullptr) break;
    }
    if (!window) {
        return 0L;
    }

    void* renderer = fnGetRenderer ? fnGetRenderer(window) : nullptr;

    if (surfaceWidth > 0 && surfaceHeight > 0 && fnGetWinSize && fnSetRes && fnSendResize) {
        int winW = 0;
        int winH = 0;
        fnGetWinSize(window, &winW, &winH);
        bool needsResize = (winW != surfaceWidth || winH != surfaceHeight);
        if (!needsResize && renderer && fnGetRendererOut) {
            int outW = 0;
            int outH = 0;
            if (fnGetRendererOut(renderer, &outW, &outH) == 0 && outW > 0 && outH > 0) {
                needsResize = (outW != surfaceWidth || outH != surfaceHeight);
            }
        }
        if (needsResize) {
            fnSetRes(surfaceWidth, surfaceHeight, surfaceWidth, surfaceHeight, 60.0f);
            fnSendResize(window);
        }
    }

    int logicalW = 0;
    int logicalH = 0;
    if (renderer && fnGetLogical) {
        fnGetLogical(renderer, &logicalW, &logicalH);
    }
    if ((logicalW <= 0 || logicalH <= 0) && fnGetWinData) {
        logicalW = static_cast<int>(reinterpret_cast<uintptr_t>(fnGetWinData(window, "qemu_guest_w")));
        logicalH = static_cast<int>(reinterpret_cast<uintptr_t>(fnGetWinData(window, "qemu_guest_h")));
    }
    if (logicalW <= 0 || logicalH <= 0) {
        return 0L;
    }

    return (static_cast<jlong>(logicalW) << 32) | (static_cast<jlong>(logicalH) & 0xFFFFFFFFLL);
}

JNIEXPORT void JNICALL
Java_com_qemusdl_app_sdl_NativeQemuBridge_nativeNotifySdlExposed(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    using GetWindowFromIdFn = void* (*)(uint32_t);
    using GetWindowIdFn = uint32_t (*)(void*);
    using PushEventFn = int (*)(void*);

    static auto fnGetWin = reinterpret_cast<GetWindowFromIdFn>(dlsym(RTLD_DEFAULT, "SDL_GetWindowFromID"));
    static auto fnGetWinId = reinterpret_cast<GetWindowIdFn>(dlsym(RTLD_DEFAULT, "SDL_GetWindowID"));
    static auto fnPushEvent = reinterpret_cast<PushEventFn>(dlsym(RTLD_DEFAULT, "SDL_PushEvent"));

    if (!fnGetWin || !fnGetWinId || !fnPushEvent) {
        return;
    }

    void* window = nullptr;
    for (uint32_t id = 1; id <= 8; ++id) {
        window = fnGetWin(id);
        if (window != nullptr) break;
    }
    if (!window) {
        return;
    }

    // Matches SDL2's SDL_WindowEvent (first 24 bytes of 56-byte SDL_Event union).
    // SDL_WINDOWEVENT = 0x200, SDL_WINDOWEVENT_EXPOSED = 3.
    struct alignas(8) SdlWindowEventCompat {
        uint32_t type;
        uint32_t timestamp;
        uint32_t windowID;
        uint8_t event;
        uint8_t padding1;
        uint8_t padding2;
        uint8_t padding3;
        int32_t data1;
        int32_t data2;
        uint8_t tailPadding[40];
    };

    SdlWindowEventCompat ev {};
    ev.type = 0x200U;
    ev.windowID = fnGetWinId(window);
    ev.event = 3U; // SDL_WINDOWEVENT_EXPOSED -> triggers sdl2_redraw(scon) in QEMU
    fnPushEvent(&ev);
}

} // extern "C"
