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
# --exclude-libs,ALL hides everything from static archives (vendored codecs, libc++_static.a).
# Without it each library re-exports its static C++ runtime and the dynamic linker may bind one
# library's copy to another's, the duplicated-libc++-state hazard of ANDROID_STL=c++_static.
#
# check_library.cmake asserts both properties after every link.
set(HUMLA_LINK_OPTIONS "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# Symbols of the JNI translation unit itself are not reached by --exclude-libs; JNIEXPORT entry
# points keep default visibility explicitly.
set(HUMLA_JNI_C_OPTIONS -fvisibility=hidden)

# Fails the build when a library exports anything but its JNI entry points, or has a segment that
# is not 16 KB aligned (see check_library.cmake).
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
# celt 0.7/0.11 predate C99-strict clang: keep implicit declarations as warnings, as ndk-build did.
set(CELT_C_OPTIONS -fvisibility=hidden -Wno-error=implicit-function-declaration -Wno-implicit-function-declaration)

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

add_library(humla_opus SHARED jni_opus.cpp)
target_link_libraries(humla_opus PRIVATE opus log)
target_link_options(humla_opus PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_opus PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_opus)

# ---------------------------------------------------------------- speex 1.2.1 (codec)
set(SPEEX_DIR "${THIRD_PARTY}/speex/libspeex")
add_library(speex STATIC
    ${SPEEX_DIR}/bits.c ${SPEEX_DIR}/cb_search.c ${SPEEX_DIR}/exc_10_16_table.c
    ${SPEEX_DIR}/exc_10_32_table.c ${SPEEX_DIR}/exc_20_32_table.c ${SPEEX_DIR}/exc_5_256_table.c
    ${SPEEX_DIR}/exc_5_64_table.c ${SPEEX_DIR}/exc_8_128_table.c ${SPEEX_DIR}/filters.c
    ${SPEEX_DIR}/gain_table.c ${SPEEX_DIR}/gain_table_lbr.c ${SPEEX_DIR}/hexc_10_32_table.c
    ${SPEEX_DIR}/hexc_table.c ${SPEEX_DIR}/high_lsp_tables.c ${SPEEX_DIR}/kiss_fft.c
    ${SPEEX_DIR}/kiss_fftr.c ${SPEEX_DIR}/lpc.c ${SPEEX_DIR}/lsp.c ${SPEEX_DIR}/lsp_tables_nb.c
    ${SPEEX_DIR}/ltp.c ${SPEEX_DIR}/modes.c ${SPEEX_DIR}/modes_wb.c ${SPEEX_DIR}/nb_celp.c
    ${SPEEX_DIR}/quant_lsp.c ${SPEEX_DIR}/sb_celp.c ${SPEEX_DIR}/speex.c
    ${SPEEX_DIR}/speex_callbacks.c ${SPEEX_DIR}/speex_header.c ${SPEEX_DIR}/stereo.c
    ${SPEEX_DIR}/vbr.c ${SPEEX_DIR}/vq.c ${SPEEX_DIR}/window.c)
target_include_directories(speex PUBLIC "${THIRD_PARTY}/speex/include" PRIVATE "${SPEEX_DIR}")
# __EMX__ picks the plain short/int typedefs in speex_types.h (no configure run needed).
target_compile_definitions(speex PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)

add_library(humla_speex SHARED jni_speex.cpp)
target_link_libraries(humla_speex PRIVATE speex log)
target_link_options(humla_speex PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_speex PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_speex)

# ---------------------------------------------------------------- speexdsp 1.2.1 (resampler, jitter, preprocess)
set(SPEEXDSP_DIR "${THIRD_PARTY}/speexdsp/libspeexdsp")
add_library(speexdsp STATIC
    ${SPEEXDSP_DIR}/buffer.c ${SPEEXDSP_DIR}/fftwrap.c ${SPEEXDSP_DIR}/filterbank.c
    ${SPEEXDSP_DIR}/jitter.c ${SPEEXDSP_DIR}/kiss_fft.c ${SPEEXDSP_DIR}/kiss_fftr.c
    ${SPEEXDSP_DIR}/mdf.c ${SPEEXDSP_DIR}/preprocess.c ${SPEEXDSP_DIR}/resample.c
    ${SPEEXDSP_DIR}/scal.c)
target_include_directories(speexdsp PUBLIC "${THIRD_PARTY}/speexdsp/include" PRIVATE "${SPEEXDSP_DIR}")
target_compile_definitions(speexdsp PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)

add_library(humla_speexdsp SHARED jni_speexdsp.cpp)
target_link_libraries(humla_speexdsp PRIVATE speexdsp log)
target_link_options(humla_speexdsp PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_speexdsp PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_speexdsp)

# ---------------------------------------------------------------- celt 0.7.1 ("CELT alpha")
set(CELT7_DIR "${THIRD_PARTY}/celt-0.7.0/libcelt")
add_library(celt7 STATIC
    ${CELT7_DIR}/bands.c ${CELT7_DIR}/celt.c ${CELT7_DIR}/cwrs.c ${CELT7_DIR}/entcode.c
    ${CELT7_DIR}/entdec.c ${CELT7_DIR}/entenc.c ${CELT7_DIR}/header.c ${CELT7_DIR}/kiss_fft.c
    ${CELT7_DIR}/kiss_fftr.c ${CELT7_DIR}/laplace.c ${CELT7_DIR}/mdct.c ${CELT7_DIR}/modes.c
    ${CELT7_DIR}/pitch.c ${CELT7_DIR}/psy.c ${CELT7_DIR}/quant_bands.c ${CELT7_DIR}/rangedec.c
    ${CELT7_DIR}/rangeenc.c ${CELT7_DIR}/rate.c ${CELT7_DIR}/vq.c)
target_include_directories(celt7 PUBLIC "${CELT7_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/celt-0.7.0-build")
target_compile_definitions(celt7 PRIVATE HAVE_CONFIG_H)
target_compile_options(celt7 PRIVATE ${CELT_C_OPTIONS})

add_library(humla_celt7 SHARED jni_celt7.cpp)
target_link_libraries(humla_celt7 PRIVATE celt7 log)
target_link_options(humla_celt7 PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_celt7 PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_celt7)

# ---------------------------------------------------------------- celt 0.11.1 ("CELT beta")
set(CELT11_DIR "${THIRD_PARTY}/celt-0.11.0/libcelt")
add_library(celt11 STATIC
    ${CELT11_DIR}/bands.c ${CELT11_DIR}/celt.c ${CELT11_DIR}/cwrs.c ${CELT11_DIR}/entcode.c
    ${CELT11_DIR}/entdec.c ${CELT11_DIR}/entenc.c ${CELT11_DIR}/header.c ${CELT11_DIR}/kiss_fft.c
    ${CELT11_DIR}/laplace.c ${CELT11_DIR}/mathops.c ${CELT11_DIR}/mdct.c ${CELT11_DIR}/modes.c
    ${CELT11_DIR}/pitch.c ${CELT11_DIR}/plc.c ${CELT11_DIR}/quant_bands.c ${CELT11_DIR}/rate.c
    ${CELT11_DIR}/vq.c)
target_include_directories(celt11 PUBLIC "${CELT11_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/celt-0.11.0-build")
target_compile_definitions(celt11 PRIVATE HAVE_CONFIG_H)
target_compile_options(celt11 PRIVATE ${CELT_C_OPTIONS})

add_library(humla_celt11 SHARED jni_celt11.cpp)
target_link_libraries(humla_celt11 PRIVATE celt11 log)
target_link_options(humla_celt11 PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humla_celt11 PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humla_celt11)

# ---------------------------------------------------------------- rnnoise 0.2 + webrtc apm 2.1
# Noise suppression (RNNoise) and echo cancellation (WebRTC APM).
add_subdirectory(rnnoise)
add_subdirectory(webrtc_apm)

add_library(humlarnnoise SHARED jni_rnnoise.cpp)
target_link_libraries(humlarnnoise PRIVATE humla_rnnoise log)
target_link_options(humlarnnoise PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humlarnnoise PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humlarnnoise)

add_library(humlaapm SHARED jni_webrtc_apm.cpp)
target_link_libraries(humlaapm PRIVATE humla_apm log)
target_link_options(humlaapm PRIVATE ${HUMLA_LINK_OPTIONS})
target_compile_options(humlaapm PRIVATE ${HUMLA_JNI_C_OPTIONS})
humla_check_library(humlaapm)
