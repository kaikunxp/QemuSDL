cmake_minimum_required(VERSION 3.22.1)
project(qemusdl_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Optional: if prebuilt libSDL2.so / SDL2 headers are provided, enable direct linkage headers
option(QEMUSDL_ENABLE_GLES3 "Enable OpenGL ES 3.0 SDL2-compatible viewport renderer" ON)

add_library(qemusdl_bridge SHARED
    qemu_sdl_bridge.cpp
    sdl_viewport_renderer.cpp
)

target_include_directories(qemusdl_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(qemusdl_bridge PRIVATE
    $<$<CONFIG:Release>:-O3 -DNDEBUG -fvisibility=hidden -fno-semantic-interposition>
)

target_link_options(qemusdl_bridge PRIVATE
    $<$<CONFIG:Release>:-Wl,-Bsymbolic-functions>
)

find_library(ANDROID_LIB android)
find_library(LOG_LIB log)
find_library(EGL_LIB EGL)
find_library(GLESV3_LIB GLESv3)

target_link_libraries(qemusdl_bridge PRIVATE
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${EGL_LIB}
    ${GLESV3_LIB}
    dl
)

# Check if user has dropped prebuilt SDL2/QEMU headers into src/main/cpp/third_party/SDL2/include
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/SDL2/include/SDL.h")
    message(STATUS "QemuSDL: Found bundled SDL2 headers")
    target_include_directories(qemusdl_bridge PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/third_party/SDL2/include")
    target_compile_definitions(qemusdl_bridge PRIVATE QEMUSDL_HAS_SDL2_HEADERS=1)
endif()
