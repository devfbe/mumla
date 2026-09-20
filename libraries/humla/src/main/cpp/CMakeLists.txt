cmake_minimum_required(VERSION 3.22)
project(humla_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
# -z,max-page-size=16384 aligns every segment to 16 KB, which is what Android 15+ requires of a
# shared library it loads. Measured on NDK 29.0.14206865 with the flag removed and the build
# directories recreated: only armeabi-v7a changes (0x1000 instead of 0x4000); arm64-v8a and x86_64
# stay at 0x4000 because the NDK has aligned to 16 KB by default since r28, and there is no 32-bit
# device with 16 KB pages. So the flag is not currently holding a load failure at bay on any ABI.
# It is on all seven targets so that the alignment does not depend on an NDK default that was
# different one release ago, and so that one rule covers all of them; check_library.cmake asserts
# the result after every link, because a comment claiming an alignment is exactly what gets
# checked once and then deleted.
#
# --exclude-libs,ALL hides everything that comes out of a static archive, which here means the
# vendored codecs and, above all, libc++_static.a. Without it each humla_*.so re-exports the C++
# runtime it statically contains -- typeinfo, the __cxa_* handlers, std::locale's id objects --
# with default visibility, and the dynamic linker is then free to bind one library's copy to
# another library's. That is exactly the duplicated-libc++-state hazard the NDK warns about for
# ANDROID_STL=c++_static, and build.gradle rules it out on the grounds that these libraries
# "export only C JNI entry points". They did not: measured on this tree before this line was
# added, libhumla_opus exported 382 symbols and libhumlaapm 1010-2355 depending on the ABI. Now
# they export their JNI entry points and nothing else, and check_library.cmake fails the build if
# that ever stops being true.
set(HUMLA_LINK_OPTIONS "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# Symbols defined in the JNI translation unit itself are not in an archive, so --exclude-libs
# does not reach them; the JNIEXPORT entry points carry an explicit default-visibility attribute
# and survive this.
set(HUMLA_JNI_C_OPTIONS -fvisibility=hidden)

# Fails the build when one of these libraries exports anything but its JNI entry points, or has a
# segment that is not 16 KB aligned. Both are one linker flag away from silently going back and
# neither is visible to anything else here: the first goes wrong only once two libraries start
# sharing a C++ runtime by accident, the second only on a 16 KB device.
#
# check_library.cmake is a DEPENDS and not just a -P argument: without that, sharpening the script
# leaves every already-linked library unchecked until something else forces a relink.
function(humla_check_library target)
  add_custom_command(TARGET ${target} POST_BUILD
      COMMAND ${CMAKE_COMMAND} -DNM=${CMAKE_NM} -DREADELF=${CMAKE_READELF}
                               -DLIBRARY=$<TARGET_FILE:${target}>
                               -P ${CMAKE_CURRENT_SOURCE_DIR}/check_library.cmake
      DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/check_library.cmake
      VERBATIM)
endfunction()
# celt 0.7/0.11 predate C99-strict clang: keep implicit declarations as warnings, as ndk-build did.
set(CELT_C_OPTIONS -fvisibility=hidden -Wno-error=implicit-function-declaration -Wno-implicit-function-declaration)

# ---------------------------------------------------------------- opus 1.6.1 (upstream CMake)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)            # as the ndk-build flags (-DFIXED_POINT)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
# Plain C, as the ndk-build flavor was: Android.mk passed only -DOPUS_BUILD -DVAR_ARRAYS
# -DFIXED_POINT and never any OPUS_ARM_MAY_HAVE_* / OPUS_X86_MAY_HAVE_* define, so no
# intrinsic or assembly path was ever selected. Upstream's CMake additionally defines
# OPUS_ARM_MAY_HAVE_NEON on ARM while building none of the .s files that hold
# celt_pitch_xcorr_neon, which does not link in a fixed-point build.
set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/opus" EXCLUDE_FROM_ALL)

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
# __EMX__ picks the plain short/int typedefs in speex_types.h (no configure run needed);
# the other three are exactly what Android.mk passed.
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
# Stream B: noise suppression (RNNoise) and echo cancellation (WebRTC APM). Both subdirectories
# were added to the repository before this point and were, until now, compiled only by the host
# ctest in tests/ -- these four lines are the first time either is linked for Android.
#
# The plan's listing left target_link_options off both of these targets. HUMLA_LINK_OPTIONS
# applies here for the same reasons it applies to the five above, in that order of weight:
# --exclude-libs,ALL, without which libhumlaapm alone re-exported 1010-2355 symbols of static
# libc++ and abseil; and -z,max-page-size=16384, which keeps the segment alignment independent of
# the NDK's default rather than rescuing any ABI today -- on NDK 29 only armeabi-v7a would change
# without it, and no 32-bit device has 16 KB pages. See the HUMLA_LINK_OPTIONS comment at the top
# for the measurements. humla_check_library() below asserts both after every link.
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
