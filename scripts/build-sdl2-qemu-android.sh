#!/usr/bin/env bash
# ==============================================================================
# QemuSDL Full Automated Android NDK Cross-Build Pipeline
# Cross-compiles:
#   1. libSDL2.so (shared)
#   2. libffi, pcre2, proxy-libintl, glib-2.0, pixman-1, libslirp (static -fPIC)
#   3. libqemu-system-x86_64.so (shared library, --enable-sdl --disable-vnc)
#   4. Installs .so binaries -> app/src/main/jniLibs/<abi>/
#      and SeaBIOS/VGABIOS firmware -> app/src/main/assets/qemu-firmware/
# ==============================================================================
set -euo pipefail

ABI="arm64-v8a"
API_LEVEL="31"
SDL2_VERSION="2.30.10"
LIBFFI_VERSION="3.4.6"
PCRE2_VERSION="10.44"
GLIB_VERSION="2.82.4"
PIXMAN_VERSION="0.44.2"
LIBSLIRP_VERSION="4.8.0"
LIBUSB_VERSION="1.0.27"
QEMU_VERSION="9.2.0"

for arg in "$@"; do
  case "$arg" in
    --abi=*) ABI="${arg#*=}" ;;
    --api=*) API_LEVEL="${arg#*=}" ;;
    --qemu-version=*) QEMU_VERSION="${arg#*=}" ;;
    --sdl2-version=*) SDL2_VERSION="${arg#*=}" ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORK_DIR="$REPO_ROOT/.native-build.nosync"
JNI_OUT_BASE="$REPO_ROOT/app/src/main/jniLibs"
SDL2_HEADERS_OUT="$REPO_ROOT/app/src/main/cpp/third_party/SDL2"
FIRMWARE_ASSETS_OUT="$REPO_ROOT/app/src/main/assets/qemu-firmware"

case "$ABI" in
  arm64-v8a)
    TRIPLE="aarch64-linux-android"
    MESON_CPU_FAMILY="aarch64"
    MESON_CPU="armv8-a"
    QEMU_CPU="aarch64"
    ;;
  x86_64)
    TRIPLE="x86_64-linux-android"
    MESON_CPU_FAMILY="x86_64"
    MESON_CPU="x86_64"
    QEMU_CPU="x86_64"
    ;;
  *)
    echo "Unsupported ABI: $ABI (supported: arm64-v8a, x86_64)"
    exit 1
    ;;
esac

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  DEFAULT_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  if [[ -d "$DEFAULT_SDK/ndk" ]]; then
    ANDROID_NDK_HOME="$(ls -d "$DEFAULT_SDK"/ndk/* | sort -V | tail -n 1)"
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ERROR: ANDROID_NDK_HOME not found. Set ANDROID_NDK_HOME to your NDK r27/r28 path."
  exit 1
fi

HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_TAG="${HOST_OS}-x86_64"
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu)"

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
SYSROOT="$TOOLCHAIN/sysroot"
CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
NM="$TOOLCHAIN/bin/llvm-nm"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"

PREFIX="$WORK_DIR/prefix-$ABI"
SRC_DIR="$WORK_DIR/src"
JNI_OUT="$JNI_OUT_BASE/$ABI"
PKGCFG_DIR="$PREFIX/lib/pkgconfig"

mkdir -p "$SRC_DIR" "$PREFIX/include" "$PREFIX/lib" "$PKGCFG_DIR" "$JNI_OUT" "$SDL2_HEADERS_OUT" "$FIRMWARE_ASSETS_OUT"

export PKG_CONFIG_LIBDIR="$PKGCFG_DIR"
export PKG_CONFIG_PATH="$PKGCFG_DIR"
unset PKG_CONFIG_SYSROOT_DIR || true

echo "============================================================"
echo " QemuSDL Full Automated Cross-Build"
echo "  ABI       : $ABI ($TRIPLE, API $API_LEVEL)"
echo "  NDK       : $ANDROID_NDK_HOME"
echo "  Prefix    : $PREFIX"
echo "  Jobs      : $JOBS"
echo "============================================================"

# ------------------------------------------------------------------------------
# 0. Create zlib.pc pointing to Android NDK sysroot libz + minimal proxy-libintl
# ------------------------------------------------------------------------------
cat > "$PKGCFG_DIR/zlib.pc" <<EOF
prefix=$SYSROOT/usr
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib/$TRIPLE/$API_LEVEL
includedir=\${prefix}/include

Name: zlib
Description: Android NDK zlib
Version: 1.3.1
Libs: -lz
Cflags:
EOF

# Build minimal static PIC libintl (proxy-libintl replacement for Android Bionic)
cat > "$PREFIX/include/libintl.h" <<'EOF'
#ifndef QEMUSDL_PROXY_LIBINTL_H
#define QEMUSDL_PROXY_LIBINTL_H
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#if defined(__GNUC__) || defined(__clang__)
#define QEMUSDL_FMT_ARG(n) __attribute__((__format_arg__(n)))
#else
#define QEMUSDL_FMT_ARG(n)
#endif
char *gettext(const char *msgid) QEMUSDL_FMT_ARG(1);
char *dgettext(const char *domainname, const char *msgid) QEMUSDL_FMT_ARG(2);
char *dcgettext(const char *domainname, const char *msgid, int category) QEMUSDL_FMT_ARG(2);
char *ngettext(const char *msgid1, const char *msgid2, unsigned long int n) QEMUSDL_FMT_ARG(1) QEMUSDL_FMT_ARG(2);
char *dngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long int n) QEMUSDL_FMT_ARG(2) QEMUSDL_FMT_ARG(3);
char *dcngettext(const char *domainname, const char *msgid1, const char *msgid2, unsigned long int n, int category) QEMUSDL_FMT_ARG(2) QEMUSDL_FMT_ARG(3);
char *textdomain(const char *domainname);
char *bindtextdomain(const char *domainname, const char *dirname);
char *bind_textdomain_codeset(const char *domainname, const char *codeset);
#ifdef __cplusplus
}
#endif
#endif
EOF
if [[ ! -f "$PREFIX/lib/libintl.a" ]]; then
  echo "[0/7] Building static PIC proxy-libintl..."
  cat > "$WORK_DIR/proxy_libintl.c" <<'EOF'
#include "libintl.h"
char *gettext(const char *msgid) { return (char *)msgid; }
char *dgettext(const char *d, const char *msgid) { (void)d; return (char *)msgid; }
char *dcgettext(const char *d, const char *msgid, int c) { (void)d; (void)c; return (char *)msgid; }
char *ngettext(const char *m1, const char *m2, unsigned long int n) { return (char *)(n == 1 ? m1 : m2); }
char *dngettext(const char *d, const char *m1, const char *m2, unsigned long int n) { (void)d; return (char *)(n == 1 ? m1 : m2); }
char *dcngettext(const char *d, const char *m1, const char *m2, unsigned long int n, int c) { (void)d; (void)c; return (char *)(n == 1 ? m1 : m2); }
char *textdomain(const char *d) { return (char *)(d ? d : "messages"); }
char *bindtextdomain(const char *d, const char *dir) { (void)d; return (char *)(dir ? dir : "/"); }
char *bind_textdomain_codeset(const char *d, const char *cs) { (void)d; return (char *)(cs ? cs : "UTF-8"); }
EOF
  "$CC" -fPIC -O2 -I"$PREFIX/include" -c "$WORK_DIR/proxy_libintl.c" -o "$WORK_DIR/proxy_libintl.o"
  "$AR" rcs "$PREFIX/lib/libintl.a" "$WORK_DIR/proxy_libintl.o"
fi

# ------------------------------------------------------------------------------
# 1. SDL2 (shared libSDL2.so)
# ------------------------------------------------------------------------------
SDL2_TAR="$SRC_DIR/SDL2-${SDL2_VERSION}.tar.gz"
SDL2_SRC="$SRC_DIR/SDL2-${SDL2_VERSION}"
if [[ ! -d "$SDL2_SRC" ]]; then
  echo "[1/7] Downloading SDL2 ${SDL2_VERSION}..."
  curl -fsSL "https://github.com/libsdl-org/SDL/releases/download/release-${SDL2_VERSION}/SDL2-${SDL2_VERSION}.tar.gz" -o "$SDL2_TAR"
  tar -xzf "$SDL2_TAR" -C "$SRC_DIR"
fi

if [[ ! -f "$JNI_OUT/libSDL2.so" || ! -f "$PREFIX/lib/libSDL2.so" ]]; then
  echo "[1/7] Building libSDL2.so..."
  cmake -S "$SDL2_SRC" -B "$WORK_DIR/build-sdl2-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DSDL_SHARED=ON \
    -DSDL_STATIC=OFF \
    -DSDL_TEST=OFF \
    -DSDL_OPENGLES=ON \
    -DSDL_VULKAN=OFF
  cmake --build "$WORK_DIR/build-sdl2-$ABI" -j"$JOBS"
  cmake --install "$WORK_DIR/build-sdl2-$ABI"
  cp -f "$PREFIX/lib/libSDL2.so" "$JNI_OUT/libSDL2.so"
  cp -Rf "$PREFIX/include/SDL2" "$SDL2_HEADERS_OUT/include"
else
  echo "[1/7] Reusing existing libSDL2.so (refreshing sdl2.pc prefix)..."
fi

# Ensure sdl2.pc prefix matches current $PREFIX (important if moved)
if [[ -f "$PKGCFG_DIR/sdl2.pc" ]]; then
  sed -i.bak "s|^prefix=.*|prefix=$PREFIX|" "$PKGCFG_DIR/sdl2.pc"
  rm -f "$PKGCFG_DIR/sdl2.pc.bak"
fi

# ------------------------------------------------------------------------------
# Meson cross file shared by glib, pixman, libslirp, and QEMU
# ------------------------------------------------------------------------------
CROSS_FILE="$WORK_DIR/android-${ABI}.cross"
cat > "$CROSS_FILE" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
nm = '$NM'
objcopy = '$OBJCOPY'
pkg-config = '$(which pkg-config)'

[built-in options]
c_args = ['-fPIC', '-O2', '-I$PREFIX/include', '-I$PREFIX/include/SDL2']
cpp_args = ['-fPIC', '-O2', '-I$PREFIX/include', '-I$PREFIX/include/SDL2']
c_link_args = ['-L$PREFIX/lib', '-llog', '-landroid']
cpp_link_args = ['-L$PREFIX/lib', '-llog', '-landroid']

[properties]
pkg_config_libdir = '$PKGCFG_DIR'
needs_exe_wrapper = true
have_c99_vsnprintf = true
have_c99_snprintf = true
have_unix98_printf = true
growing_stack = false

[host_machine]
system = 'android'
cpu_family = '$MESON_CPU_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'
EOF

# ------------------------------------------------------------------------------
# 2. libffi (static -fPIC)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libffi.a" ]]; then
  LIBFFI_TAR="$SRC_DIR/libffi-${LIBFFI_VERSION}.tar.gz"
  LIBFFI_SRC="$SRC_DIR/libffi-${LIBFFI_VERSION}"
  if [[ ! -d "$LIBFFI_SRC" ]]; then
    echo "[2/7] Downloading libffi ${LIBFFI_VERSION}..."
    curl -fsSL "https://github.com/libffi/libffi/releases/download/v${LIBFFI_VERSION}/libffi-${LIBFFI_VERSION}.tar.gz" -o "$LIBFFI_TAR"
    tar -xzf "$LIBFFI_TAR" -C "$SRC_DIR"
  fi
  echo "[2/7] Building static libffi..."
  rm -rf "$WORK_DIR/build-libffi-$ABI"
  mkdir -p "$WORK_DIR/build-libffi-$ABI"
  pushd "$WORK_DIR/build-libffi-$ABI" >/dev/null
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-fPIC -O2" \
    "$LIBFFI_SRC/configure" \
    --host="$TRIPLE" \
    --prefix="$PREFIX" \
    --enable-static --disable-shared --disable-docs
  make -j"$JOBS"
  make install
  popd >/dev/null
else
  echo "[2/7] Reusing existing libffi.a"
fi

# ------------------------------------------------------------------------------
# 3. pcre2 (static -fPIC via CMake)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libpcre2-8.a" ]]; then
  PCRE2_TAR="$SRC_DIR/pcre2-${PCRE2_VERSION}.tar.gz"
  PCRE2_SRC="$SRC_DIR/pcre2-${PCRE2_VERSION}"
  if [[ ! -d "$PCRE2_SRC" ]]; then
    echo "[3/7] Downloading pcre2 ${PCRE2_VERSION}..."
    curl -fsSL "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-${PCRE2_VERSION}/pcre2-${PCRE2_VERSION}.tar.gz" -o "$PCRE2_TAR"
    tar -xzf "$PCRE2_TAR" -C "$SRC_DIR"
  fi
  echo "[3/7] Building static pcre2-8..."
  cmake -S "$PCRE2_SRC" -B "$WORK_DIR/build-pcre2-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DPCRE2_BUILD_PCRE2GREP=OFF \
    -DPCRE2_BUILD_TESTS=OFF \
    -DPCRE2_SUPPORT_UNICODE=ON
  cmake --build "$WORK_DIR/build-pcre2-$ABI" -j"$JOBS"
  cmake --install "$WORK_DIR/build-pcre2-$ABI"
else
  echo "[3/7] Reusing existing libpcre2-8.a"
fi

# ------------------------------------------------------------------------------
# 4. glib-2.0 (static -fPIC via Meson)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libglib-2.0.a" ]]; then
  GLIB_MAJOR_MINOR="$(echo "$GLIB_VERSION" | cut -d. -f1-2)"
  GLIB_TAR="$SRC_DIR/glib-${GLIB_VERSION}.tar.xz"
  GLIB_SRC="$SRC_DIR/glib-${GLIB_VERSION}"
  if [[ ! -d "$GLIB_SRC" ]]; then
    echo "[4/7] Downloading glib ${GLIB_VERSION}..."
    curl -fsSL "https://download.gnome.org/sources/glib/${GLIB_MAJOR_MINOR}/glib-${GLIB_VERSION}.tar.xz" -o "$GLIB_TAR"
    tar -xJf "$GLIB_TAR" -C "$SRC_DIR"
  fi
  echo "[4/7] Building static glib-2.0..."
  rm -rf "$WORK_DIR/build-glib-$ABI"
  meson setup "$WORK_DIR/build-glib-$ABI" "$GLIB_SRC" \
    --cross-file="$CROSS_FILE" \
    --prefix="$PREFIX" \
    --libdir=lib \
    --default-library=static \
    -Dtests=false \
    -Dglib_debug=disabled \
    -Dnls=disabled \
    -Dlibmount=disabled \
    -Dselinux=disabled \
    -Dxattr=false \
    -Dintrospection=disabled \
    -Ddocumentation=false \
    -Dman-pages=disabled
  ninja -C "$WORK_DIR/build-glib-$ABI" -j"$JOBS"
  ninja -C "$WORK_DIR/build-glib-$ABI" install
else
  echo "[4/7] Reusing existing libglib-2.0.a"
fi

# ------------------------------------------------------------------------------
# 5. pixman-1 (static -fPIC via Meson)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libpixman-1.a" ]]; then
  PIXMAN_TAR="$SRC_DIR/pixman-${PIXMAN_VERSION}.tar.gz"
  PIXMAN_SRC="$SRC_DIR/pixman-${PIXMAN_VERSION}"
  if [[ ! -d "$PIXMAN_SRC" ]]; then
    echo "[5/7] Downloading pixman ${PIXMAN_VERSION}..."
    curl -fsSL "https://www.cairographics.org/releases/pixman-${PIXMAN_VERSION}.tar.gz" -o "$PIXMAN_TAR"
    tar -xzf "$PIXMAN_TAR" -C "$SRC_DIR"
  fi
  echo "[5/7] Building static pixman-1..."
  rm -rf "$WORK_DIR/build-pixman-$ABI"
  meson setup "$WORK_DIR/build-pixman-$ABI" "$PIXMAN_SRC" \
    --cross-file="$CROSS_FILE" \
    --prefix="$PREFIX" \
    --libdir=lib \
    --default-library=static \
    -Dtests=disabled \
    -Ddemos=disabled \
    -Dgtk=disabled \
    -Dlibpng=disabled \
    -Dcpu-features-path=
  ninja -C "$WORK_DIR/build-pixman-$ABI" -j"$JOBS"
  ninja -C "$WORK_DIR/build-pixman-$ABI" install
else
  echo "[5/7] Reusing existing libpixman-1.a"
fi

# ------------------------------------------------------------------------------
# 6. libslirp (static -fPIC via Meson — rootless user-mode networking)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libslirp.a" ]]; then
  SLIRP_TAR="$SRC_DIR/libslirp-v${LIBSLIRP_VERSION}.tar.bz2"
  SLIRP_SRC="$SRC_DIR/libslirp-v${LIBSLIRP_VERSION}"
  if [[ ! -d "$SLIRP_SRC" ]]; then
    echo "[6/7] Downloading libslirp ${LIBSLIRP_VERSION}..."
    curl -fsSL "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v${LIBSLIRP_VERSION}/libslirp-v${LIBSLIRP_VERSION}.tar.bz2" -o "$SLIRP_TAR"
    tar -xjf "$SLIRP_TAR" -C "$SRC_DIR"
  fi
  echo "[6/7] Building static libslirp..."
  rm -rf "$WORK_DIR/build-slirp-$ABI"
  meson setup "$WORK_DIR/build-slirp-$ABI" "$SLIRP_SRC" \
    --cross-file="$CROSS_FILE" \
    --prefix="$PREFIX" \
    --libdir=lib \
    --default-library=static
  ninja -C "$WORK_DIR/build-slirp-$ABI" -j"$JOBS"
  ninja -C "$WORK_DIR/build-slirp-$ABI" install
else
  echo "[6/8] Reusing existing libslirp.a"
fi

# ------------------------------------------------------------------------------
# 7. libusb-1.0 (with Android Linux usbfs backend and libusb_wrap_sys_device)
# ------------------------------------------------------------------------------
if [[ ! -f "$PREFIX/lib/libusb-1.0.so" ]]; then
  LIBUSB_TAR="$SRC_DIR/libusb-${LIBUSB_VERSION}.tar.bz2"
  LIBUSB_SRC="$SRC_DIR/libusb-${LIBUSB_VERSION}"
  if [[ ! -d "$LIBUSB_SRC" ]]; then
    echo "[7/8] Downloading libusb ${LIBUSB_VERSION}..."
    curl -fsSL "https://github.com/libusb/libusb/releases/download/v${LIBUSB_VERSION}/libusb-${LIBUSB_VERSION}.tar.bz2" -o "$LIBUSB_TAR"
    tar -xjf "$LIBUSB_TAR" -C "$SRC_DIR"
  fi
  echo "[7/8] Building libusb ${LIBUSB_VERSION} ($ABI)..."
  rm -rf "$WORK_DIR/build-libusb-$ABI"
  mkdir -p "$WORK_DIR/build-libusb-$ABI"
  pushd "$WORK_DIR/build-libusb-$ABI" >/dev/null
  "$LIBUSB_SRC/configure" \
    --host="$TRIPLE" \
    --prefix="$PREFIX" \
    --disable-udev \
    --enable-shared \
    --disable-static \
    CC="$CC" \
    CXX="$CXX" \
    AR="$AR" \
    RANLIB="$RANLIB" \
    STRIP="$STRIP" \
    CFLAGS="-O2 -fPIC -D_GNU_SOURCE"
  make -j"$JOBS"
  make install
  popd >/dev/null
  cp -f "$PREFIX/lib/libusb-1.0.so" "$JNI_OUT/libusb-1.0.so"
  "$STRIP" --strip-unneeded "$JNI_OUT/libusb-1.0.so"
else
  echo "[7/8] Reusing existing libusb-1.0.so"
  cp -f "$PREFIX/lib/libusb-1.0.so" "$JNI_OUT/libusb-1.0.so"
fi

# ------------------------------------------------------------------------------
# 8. QEMU 9.2.0 x86_64-softmmu -> libqemu-system-x86_64.so (SDL2, no VNC)
# ------------------------------------------------------------------------------
QEMU_TAR="$SRC_DIR/qemu-${QEMU_VERSION}.tar.xz"
QEMU_SRC="$SRC_DIR/qemu-${QEMU_VERSION}"
if [[ ! -d "$QEMU_SRC" ]]; then
  echo "[8/8] Downloading QEMU ${QEMU_VERSION}..."
  curl -fsSL "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz" -o "$QEMU_TAR"
  tar -xJf "$QEMU_TAR" -C "$SRC_DIR"
fi

# Patch QEMU meson.build to emit PIC static libs + shared_library on Android so SDLActivity can load libqemu-system-x86_64.so
python3 - "$QEMU_SRC/meson.build" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path, "r", encoding="utf-8").read()
changed = False
if "'b_staticpic=false'" in text:
    text = text.replace("'b_staticpic=false'", "'b_staticpic=true'", 1)
    changed = True
marker = "# QEMUSDL_ANDROID_SHARED_LIB"
if "if host_os == 'android'" in text and marker in text:
    text = text.replace("if host_os == 'android'", "if cc.get_define('__ANDROID__') != ''", 1)
    changed = True
elif marker not in text:
    old = """    emulator = executable(exe_name, exe['sources'],
               install: true,
               c_args: c_args,
               dependencies: arch_deps + exe['dependencies'],
               objects: lib.extract_all_objects(recursive: true),
               link_depends: [block_syms, qemu_syms],
               link_args: link_args,
               win_subsystem: exe['win_subsystem'])"""
    new = """    # QEMUSDL_ANDROID_SHARED_LIB
    if cc.get_define('__ANDROID__') != ''
      emulator = shared_library(exe_name, exe['sources'],
                 install: true,
                 c_args: c_args,
                 dependencies: arch_deps + exe['dependencies'],
                 objects: lib.extract_all_objects(recursive: true),
                 link_depends: [block_syms, qemu_syms],
                 link_args: link_args)
    else
      emulator = executable(exe_name, exe['sources'],
                 install: true,
                 c_args: c_args,
                 dependencies: arch_deps + exe['dependencies'],
                 objects: lib.extract_all_objects(recursive: true),
                 link_depends: [block_syms, qemu_syms],
                 link_args: link_args,
                 win_subsystem: exe['win_subsystem'])
    endif"""
    assert old in text, "Expected executable() block not found in QEMU meson.build"
    text = text.replace(old, new, 1)
    changed = True
if changed:
    open(path, "w", encoding="utf-8").write(text)
    print("Patched QEMU meson.build (b_staticpic=true + Android shared_library).")
else:
    print("QEMU meson.build already patched.")
PYEOF

# Patch QEMU backends/hostmem-shm.c to use memfd_create() on Android (Bionic lacks shm_open/shm_unlink)
python3 - "$QEMU_SRC/backends/hostmem-shm.c" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path, "r", encoding="utf-8").read()
if "memfd_create(shm_name->str" not in text:
    text = text.replace(
        "    fd = shm_open(shm_name->str, oflag, mode);",
        "#ifdef __ANDROID__\n    (void)oflag;\n    (void)mode;\n    fd = memfd_create(shm_name->str, MFD_CLOEXEC);\n#else\n    fd = shm_open(shm_name->str, oflag, mode);\n#endif",
        1
    ).replace(
        "    shm_unlink(shm_name->str);",
        "#ifndef __ANDROID__\n    shm_unlink(shm_name->str);\n#endif",
        1
    )
    open(path, "w", encoding="utf-8").write(text)
    print("Patched backends/hostmem-shm.c for Android memfd_create.")
PYEOF

# Apply Android AArch64 AAPCS64 coroutine backend (avoids Bionic 14+ PAC/longjmp SIGILL)
if [[ -f "$SCRIPT_DIR/patches/coroutine-sigaltstack-android.c" ]]; then
  cp -f "$SCRIPT_DIR/patches/coroutine-sigaltstack-android.c" "$QEMU_SRC/util/coroutine-sigaltstack.c"
  echo "Applied Android AArch64 coroutine backend to util/coroutine-sigaltstack.c."
fi

# Ensure a clean local host Python venv with distlib for QEMU's mkvenv
HOST_VENV="$WORK_DIR/host-venv"
if [[ ! -x "$HOST_VENV/bin/python3" ]]; then
  echo "[7/7] Creating isolated host Python venv with distlib..."
  python3 -m venv "$HOST_VENV"
  "$HOST_VENV/bin/pip" install --quiet distlib
fi

# Create Android-compatible configure wrapper flags
QEMU_BUILD="$WORK_DIR/build-qemu-$ABI"
rm -rf "$QEMU_BUILD"
mkdir -p "$QEMU_BUILD"

echo "[7/7] Configuring QEMU ${QEMU_VERSION} (x86_64-softmmu, SDL2, no VNC)..."
pushd "$QEMU_BUILD" >/dev/null
PKG_CONFIG_LIBDIR="$PKGCFG_DIR" \
PKG_CONFIG_PATH="$PKGCFG_DIR" \
PKG_CONFIG="$(which pkg-config)" \
AR="$AR" \
RANLIB="$RANLIB" \
STRIP="$STRIP" \
NM="$NM" \
OBJCOPY="$OBJCOPY" \
MESON="$(which meson)" \
"$QEMU_SRC/configure" \
  --cross-prefix="" \
  --cc="$CC" \
  --cxx="$CXX" \
  --host-cc="cc" \
  --python="$HOST_VENV/bin/python3" \
  --ninja="$(which ninja)" \
  --cpu="$QEMU_CPU" \
  --target-list=x86_64-softmmu \
  --prefix="$PREFIX" \
  --enable-sdl \
  --disable-vnc \
  --disable-gtk \
  --disable-curses \
  --disable-opengl \
  --disable-virglrenderer \
  --disable-dbus-display \
  --enable-slirp \
  --enable-libusb \
  --enable-tcg \
  --with-coroutine=sigaltstack \
  --disable-kvm \
  --disable-xen \
  --disable-docs \
  --disable-tools \
  --disable-guest-agent \
  --disable-linux-io-uring \
  --disable-vhost-user \
  --disable-vhost-kernel \
  --disable-vhost-net \
  --disable-vhost-crypto \
  --disable-vhost-vdpa \
  --disable-vduse-blk-export \
  --disable-virtfs \
  --disable-capstone \
  --disable-fdt \
  --disable-werror \
  --disable-pie \
  --extra-cflags="-fPIC -O2 -I$PREFIX/include -I$PREFIX/include/SDL2 -I$PREFIX/include/libusb-1.0" \
  --extra-ldflags="-L$PREFIX/lib -lSDL2 -lusb-1.0 -llog -landroid -lz"

echo "[8/8] Compiling libqemu-system-x86_64.so..."
ninja -j"$JOBS" libqemu-system-x86_64.so
popd >/dev/null

cp -f "$QEMU_BUILD/libqemu-system-x86_64.so" "$JNI_OUT/libqemu-system-x86_64.so"
"$STRIP" --strip-unneeded "$JNI_OUT/libqemu-system-x86_64.so"

# Copy x86_64 SeaBIOS / VGABIOS / PXE firmware + keymaps into Android assets
echo "Bundling x86_64 SeaBIOS/VGABIOS firmware into $FIRMWARE_ASSETS_OUT..."
for rom in \
  bios.bin bios-256k.bin bios-microvm.bin \
  vgabios.bin vgabios-stdvga.bin vgabios-virtio.bin vgabios-qxl.bin \
  vgabios-vmware.bin vgabios-cirrus.bin vgabios-bochs-display.bin vgabios-ramfb.bin \
  efi-virtio.rom efi-e1000.rom efi-e1000e.rom \
  kvmvapic.bin linuxboot.bin linuxboot_dma.bin; do
  if [[ -f "$QEMU_SRC/pc-bios/$rom" ]]; then
    cp -f "$QEMU_SRC/pc-bios/$rom" "$FIRMWARE_ASSETS_OUT/$rom"
  fi
done
rm -rf "$FIRMWARE_ASSETS_OUT/keymaps"
cp -Rf "$QEMU_SRC/pc-bios/keymaps" "$FIRMWARE_ASSETS_OUT/keymaps"

echo "============================================================"
echo " SUCCESS! Installed:"
echo "  - $JNI_OUT/libSDL2.so"
echo "  - $JNI_OUT/libusb-1.0.so"
echo "  - $JNI_OUT/libqemu-system-x86_64.so"
echo "  - $FIRMWARE_ASSETS_OUT/ (SeaBIOS + VGABIOS + keymaps)"
echo "============================================================"
