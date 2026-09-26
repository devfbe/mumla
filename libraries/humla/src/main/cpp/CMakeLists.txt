cmake_minimum_required(VERSION 3.22)
project(humla_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
# -z,max-page-size=16384: Android 15+ requires 16 KB aligned segments. Recent NDKs already default
# to it for 64-bit ABIs; the flag keeps it independent of the NDK default.
#
# --exclude-libs,ALL hides everything from static archives (vendored codecs, libc++_static.a), so
# the library does not re-export its static C++ runtime.
#
# check_library.cmake asserts both properties after every link.
set(HUMLA_LINK_OPTIONS "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# Symbols of the JNI translation units themselves are not reached by --exclude-libs; JNI_OnLoad
# keeps default visibility explicitly.
set(HUMLA_JNI_C_OPTIONS -fvisibility=hidden)

# Fails the build when the library exports anything but JNI_OnLoad, or has a segment that is not
# 16 KB aligned (see check_library.cmake).
#
# POST_BUILD commands take no DEPENDS, so the script is a link dependency instead: changing it
# relinks the library, which reruns the check.
function(humla_check_library target)
  set_property(TARGET ${target} APPEND PROPERTY LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/check_library.cmake)
  add_custom_command(TARGET ${target} POST_BUILD
      COMMAND ${CMAKE_COMMAND} -DNM=${CMAKE_NM} -DREADELF=${CMAKE_READELF}
                               -DLIBRARY=$<TARGET_FILE:${target}>
                               -P ${CMAKE_CURRENT_SOURCE_DIR}/check_library.cmake
      VERBATIM)
endfunction()

# ---------------------------------------------------------------- opus 1.6.1 (upstream CMake)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
# SIMD intrinsics on (SSE/AVX with runtime detection on x86_64). On ARM only the NEON
# intrinsics are used: upstream's MAY/PRESUME_NEON switches select ARMv7 assembly
# (celt_pitch_xcorr_neon) in a fixed-point build, which its CMake never assembles and which does
# not exist for arm64. OpusConfig.cmake forces PRESUME_NEON on for aarch64, so those defines are
# stripped afterwards and replaced by the intrinsic-only ones.
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)
set(OPUS_MAY_HAVE_NEON OFF CACHE BOOL "" FORCE)
set(OPUS_PRESUME_NEON OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/opus" EXCLUDE_FROM_ALL)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    get_target_property(OPUS_DEFINITIONS opus COMPILE_DEFINITIONS)
    list(REMOVE_ITEM OPUS_DEFINITIONS OPUS_ARM_MAY_HAVE_NEON OPUS_ARM_PRESUME_NEON OPUS_HAVE_RTCD)
    # NEON is part of both Android ARM ABIs, so no runtime check is needed.
    list(APPEND OPUS_DEFINITIONS OPUS_ARM_MAY_HAVE_NEON_INTR OPUS_ARM_PRESUME_NEON_INTR)
    list(REMOVE_DUPLICATES OPUS_DEFINITIONS)
    set_target_properties(opus PROPERTIES COMPILE_DEFINITIONS "${OPUS_DEFINITIONS}")
endif()

# ---------------------------------------------------------------- speexdsp 1.2.1 (resampler, jitter, preprocess)
set(SPEEXDSP_DIR "${THIRD_PARTY}/speexdsp/libspeexdsp")
add_library(speexdsp STATIC
    ${SPEEXDSP_DIR}/buffer.c ${SPEEXDSP_DIR}/fftwrap.c ${SPEEXDSP_DIR}/filterbank.c
    ${SPEEXDSP_DIR}/jitter.c ${SPEEXDSP_DIR}/kiss_fft.c ${SPEEXDSP_DIR}/kiss_fftr.c
    ${SPEEXDSP_DIR}/mdf.c ${SPEEXDSP_DIR}/preprocess.c ${SPEEXDSP_DIR}/resample.c
    ${SPEEXDSP_DIR}/scal.c)
target_include_directories(speexdsp PUBLIC "${THIRD_PARTY}/speexdsp/include" PRIVATE "${SPEEXDSP_DIR}")
target_compile_definitions(speexdsp PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)

# ---------------------------------------------------------------- rnnoise + webrtc apm 2.1
# Noise suppression (RNNoise) and echo cancellation (WebRTC APM).
add_subdirectory(rnnoise)
add_subdirectory(webrtc_apm)

# ---------------------------------------------------------------- libhumla_native.so
# One library, one System.loadLibrary: JNI_OnLoad registers every bridge's methods.
add_library(humla_native SHARED
    jni_onload.cpp jni_opus.cpp jni_speexdsp.cpp jni_rnnoise.cpp jni_webrtc_apm.cpp)
target_link_libraries(humla_native PRIVATE opus speexdsp humla_rnnoise humla_apm log)
target_link_options(humla_native PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_native PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_native)
