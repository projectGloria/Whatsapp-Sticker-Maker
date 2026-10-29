cmake_minimum_required(VERSION 3.22.1)
project(stickerstudio LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The installable personal build is debuggable, but the codec must still run at
# production speed. Apply before add_subdirectory so libwebp is optimized too.
add_compile_options("$<$<CONFIG:Debug>:-O2>")
foreach(tool ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX EXTRAS)
    set(WEBP_BUILD_${tool} OFF CACHE BOOL "" FORCE)
endforeach()
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
add_subdirectory(libwebp)
add_library(studio SHARED studio.cpp)
target_include_directories(studio PRIVATE libwebp/src)
target_link_libraries(studio webp libwebpmux webpdemux jnigraphics)
# NDK uses Windows short paths when the SDK path contains spaces; the shortened
# clang++ filename loses its ++ suffix, so explicitly retain C++ driver mode.
target_link_options(studio PRIVATE "--driver-mode=g++" "-Wl,-z,max-page-size=16384")
