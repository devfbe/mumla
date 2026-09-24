cmake_minimum_required(VERSION 3.22)
project(humla_native_tests C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

# ---------------------------------------------------------------- sanitizer availability
# The sanitized tests only register where the host toolchain supports the flags.
# HUMLA_REQUIRE_SANITIZERS turns a missing sanitizer runtime into a configure error instead of a
# silently smaller test run (CI sets it and runs ctest with --no-tests=error).
option(HUMLA_REQUIRE_SANITIZERS
       "Fail configuration if the host toolchain cannot build the sanitized tests"
       $ENV{HUMLA_REQUIRE_SANITIZERS})
# option() only writes the cache the first time, so a warm build directory would ignore a later
# HUMLA_REQUIRE_SANITIZERS in the environment. The environment is authoritative whenever it is
# set (outranking -D); unset it to hand control back to -D.
if(DEFINED ENV{HUMLA_REQUIRE_SANITIZERS} AND NOT "$ENV{HUMLA_REQUIRE_SANITIZERS}" STREQUAL "")
  set(HUMLA_REQUIRE_SANITIZERS "$ENV{HUMLA_REQUIRE_SANITIZERS}" CACHE BOOL
      "Fail configuration if the host toolchain cannot build the sanitized tests" FORCE)
endif()

if(NOT ANDROID AND NOT CMAKE_CROSSCOMPILING)
  include(CheckCSourceCompiles)
  set(CMAKE_REQUIRED_FLAGS "-fsanitize=address,undefined")
  check_c_source_compiles("int main(void) { return 0; }" HUMLA_HAVE_SANITIZERS)
  unset(CMAKE_REQUIRED_FLAGS)
endif()

if(HUMLA_REQUIRE_SANITIZERS AND NOT HUMLA_HAVE_SANITIZERS)
  message(FATAL_ERROR
      "HUMLA_REQUIRE_SANITIZERS is set, but this toolchain cannot compile and link "
      "-fsanitize=address,undefined. The sanitized tests would have been skipped silently. "
      "Install the ASan/UBSan runtimes, or clear HUMLA_REQUIRE_SANITIZERS to accept a run "
      "with only the plain tests.")
endif()

if(HUMLA_HAVE_SANITIZERS)
  # -O1 -g -fno-omit-frame-pointer so sanitizer reports name a line.
  set(HUMLA_SAN_FLAGS -O1 -g -fno-omit-frame-pointer
                      -fsanitize=address,undefined -fno-sanitize-recover=all)
  # print_stacktrace makes a UBSan finding actionable; halt_on_error stops at the first one so
  # ctest reports a failure instead of a pile of warnings and exit code 0.
  set(HUMLA_SAN_TEST_ENV
      "UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1;ASAN_OPTIONS=detect_leaks=1")
else()
  message(STATUS "humla: no ASan/UBSan on this toolchain, skipping the sanitized tests")
endif()

# Every test carries a label (fast set per push, instrumented set on a schedule). Run ctest with
# --no-tests=error, or a label that matches nothing passes.
function(humla_add_labelled_test name target label)
  add_test(NAME ${name} COMMAND ${target})
  set_tests_properties(${name} PROPERTIES LABELS ${label})
  if("${label}" STREQUAL "sanitized")
    set_tests_properties(${name} PROPERTIES ENVIRONMENT "${HUMLA_SAN_TEST_ENV}")
  endif()
endfunction()

# ---------------------------------------------------------------- rnnoise
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../rnnoise ${CMAKE_BINARY_DIR}/rnnoise)

add_executable(test_rnnoise test_rnnoise.c)
target_link_libraries(test_rnnoise PRIVATE humla_rnnoise m)
humla_add_labelled_test(rnnoise test_rnnoise plain)

# The same sources again under ASan+UBSan. Some bugs are invisible to a plain build, e.g.
# rnnoise_model_free() reading an uninitialised FILE* (see humla_rnnoise_destroy) only fails
# reliably under ASan, which fills fresh heap with garbage. LeakSanitizer turns the create/destroy
# cycles into a leak check.
if(HUMLA_HAVE_SANITIZERS)
  # A second, instrumented copy of the library. Sources, include dirs and definitions are read
  # back from the real target so the two cannot drift apart.
  get_target_property(HUMLA_RNNOISE_SOURCES humla_rnnoise SOURCES)
  get_target_property(HUMLA_RNNOISE_INCLUDES humla_rnnoise INCLUDE_DIRECTORIES)
  get_target_property(HUMLA_RNNOISE_DEFS humla_rnnoise COMPILE_DEFINITIONS)

  add_library(humla_rnnoise_asan STATIC ${HUMLA_RNNOISE_SOURCES})
  target_include_directories(humla_rnnoise_asan PUBLIC ${HUMLA_RNNOISE_INCLUDES})
  target_compile_definitions(humla_rnnoise_asan PRIVATE ${HUMLA_RNNOISE_DEFS})
  # The sanitizer flags are restricted to C so the .incbin assembly file is assembled exactly
  # as it is in the real target.
  target_compile_options(humla_rnnoise_asan PRIVATE
      $<$<COMPILE_LANGUAGE:C>:${HUMLA_SAN_FLAGS} -fvisibility=hidden -Wno-unused-function>)
  target_link_options(humla_rnnoise_asan PUBLIC -fsanitize=address,undefined)
  target_link_libraries(humla_rnnoise_asan PUBLIC m)

  add_executable(test_rnnoise_asan test_rnnoise.c)
  target_compile_options(test_rnnoise_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_rnnoise_asan PRIVATE humla_rnnoise_asan m)

  humla_add_labelled_test(rnnoise_sanitized test_rnnoise_asan sanitized)
endif()

# ---------------------------------------------------------------- webrtc audio processing
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../webrtc_apm ${CMAKE_BINARY_DIR}/webrtc_apm)

# test_apm.c is C on purpose, to prove humla_apm.h is valid C with C linkage. The C++ driver
# links it because humla_apm and webrtc need the C++ runtime.
add_executable(test_apm test_apm.c)
set_target_properties(test_apm PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(test_apm PRIVATE humla_apm m)
humla_add_labelled_test(apm test_apm plain)

if(HUMLA_HAVE_SANITIZERS)
  # Only the wrapper is instrumented by default, not the ~200 upstream translation units (which
  # would dominate build time); the bugs worth catching live in humla_apm.cpp and its caller.
  # ASan's malloc interceptors are process-wide, so allocations are still covered. Configure with
  # -DHUMLA_SANITIZE_WEBRTC=ON to instrument upstream too.
  option(HUMLA_SANITIZE_WEBRTC "Also instrument the vendored webrtc sources" OFF)

  if(HUMLA_SANITIZE_WEBRTC)
    get_target_property(HUMLA_APM_ASAN_SOURCES humla_apm SOURCES)
  else()
    set(HUMLA_APM_ASAN_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc_apm/humla_apm.cpp)
  endif()

  add_library(humla_apm_asan STATIC ${HUMLA_APM_ASAN_SOURCES})
  # Same INTERFACE target as the real library, so flags and dependencies cannot drift.
  target_link_libraries(humla_apm_asan PRIVATE humla_apm_buildflags)
  target_include_directories(humla_apm_asan PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc_apm)
  target_compile_options(humla_apm_asan PRIVATE
      ${HUMLA_SAN_FLAGS} -fvisibility=hidden -Wno-deprecated-declarations)
  target_link_options(humla_apm_asan PUBLIC -fsanitize=address,undefined)
  if(NOT HUMLA_SANITIZE_WEBRTC)
    # The uninstrumented upstream objects come from humla_apm. humla_apm.cpp is in both archives;
    # the instrumented one is listed first so its definitions win. apm_is_instrumented verifies
    # that on the linked image (see assert_instrumented.cmake).
    target_link_libraries(humla_apm_asan PUBLIC humla_apm)
  endif()

  add_executable(test_apm_asan test_apm.c)
  set_target_properties(test_apm_asan PROPERTIES LINKER_LANGUAGE CXX)
  target_compile_options(test_apm_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_apm_asan PRIVATE humla_apm_asan m)

  humla_add_labelled_test(apm_sanitized test_apm_asan sanitized)

  # objdump is required: an optional check could silently disappear.
  find_program(HUMLA_OBJDUMP NAMES ${CMAKE_OBJDUMP} llvm-objdump objdump)
  if(NOT HUMLA_OBJDUMP)
    message(FATAL_ERROR
        "No objdump found. It is needed to verify that test_apm_asan links the instrumented "
        "copy of humla_apm.cpp rather than the uninstrumented one; without it that test can "
        "pass while instrumenting nothing.")
  endif()
  add_test(NAME apm_is_instrumented
           COMMAND ${CMAKE_COMMAND} -DOBJDUMP=${HUMLA_OBJDUMP}
                                    -DBINARY=$<TARGET_FILE:test_apm_asan>
                                    -DSYMBOL=humla_apm_create
                                    -P ${CMAKE_CURRENT_SOURCE_DIR}/assert_instrumented.cmake)
  set_tests_properties(apm_is_instrumented PROPERTIES LABELS sanitized)
endif()

# ---------------------------------------------------------------- the handle table
add_executable(test_handle_table test_handle_table.cpp)
target_include_directories(test_handle_table PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
humla_add_labelled_test(handle_table test_handle_table plain)

if(HUMLA_HAVE_SANITIZERS)
  add_executable(test_handle_table_asan test_handle_table.cpp)
  target_include_directories(test_handle_table_asan PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
  target_compile_options(test_handle_table_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_options(test_handle_table_asan PRIVATE -fsanitize=address,undefined)
  humla_add_labelled_test(handle_table_sanitized test_handle_table_asan sanitized)
endif()

# ---------------------------------------------------------------- the JNI bridges
# jni_rnnoise.cpp and jni_webrtc_apm.cpp run here through a stand-in JNIEnv (jni_env_stub.h):
# a missing length check, a double free or a bridge wired to the wrong C function produces no
# error code and is invisible from Kotlin. The Android build only proves these files compile.
#
# The JDK headers (JAVA_HOME, provided by the nix dev shell) are required, not optional, so the
# test cannot silently disappear. JNINativeInterface's layout is fixed by the JNI spec, so the JDK
# jni.h is equivalent to the NDK's for this purpose.
find_path(HUMLA_JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include")
find_path(HUMLA_JNI_MD_INCLUDE_DIR jni_md.h
          HINTS "$ENV{JAVA_HOME}/include/linux" "$ENV{JAVA_HOME}/include/darwin")
if(NOT HUMLA_JNI_INCLUDE_DIR OR NOT HUMLA_JNI_MD_INCLUDE_DIR)
  message(FATAL_ERROR
      "jni.h / jni_md.h not found. Set JAVA_HOME (the nix dev shell does) -- without them the "
      "JNI bridges would be tested nowhere at all.")
endif()

set(HUMLA_JNI_BRIDGE_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/test_jni_bridges.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../jni_rnnoise.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../jni_webrtc_apm.cpp)
# The bridges include headers relative to cpp/, as in the Android build.
set(HUMLA_JNI_BRIDGE_INCLUDES ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${HUMLA_JNI_INCLUDE_DIR} ${HUMLA_JNI_MD_INCLUDE_DIR})

add_executable(test_jni_bridges ${HUMLA_JNI_BRIDGE_SOURCES})
target_include_directories(test_jni_bridges PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
target_link_libraries(test_jni_bridges PRIVATE humla_rnnoise humla_apm m)
humla_add_labelled_test(jni_bridges test_jni_bridges plain)

if(HUMLA_HAVE_SANITIZERS)
  # The same bridges against the instrumented libraries. jni_env_stub.h hands out exact-size
  # malloc'd array copies, so an overrun lands in ASan's redzone; LeakSanitizer catches a bridge
  # that does not release its array copy.
  add_executable(test_jni_bridges_asan ${HUMLA_JNI_BRIDGE_SOURCES})
  target_include_directories(test_jni_bridges_asan PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
  target_compile_options(test_jni_bridges_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_jni_bridges_asan PRIVATE humla_rnnoise_asan humla_apm_asan m)
  humla_add_labelled_test(jni_bridges_sanitized test_jni_bridges_asan sanitized)
endif()

# ---------------------------------------------------------------- the speexdsp bridge
# libspeexdsp takes sample counts from the state or from separate arguments, never from the
# buffer, so the JNI layer is the only place that can compare the two.
#
# libspeexdsp is rebuilt here because ../CMakeLists.txt defines Android SHARED targets that link
# -llog and cannot be configured on the host. The source list mirrors ../CMakeLists.txt; drift
# shows up as a link error.
set(HUMLA_SPEEXDSP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/speexdsp/libspeexdsp)
set(HUMLA_SPEEXDSP_SOURCES
    ${HUMLA_SPEEXDSP_DIR}/buffer.c ${HUMLA_SPEEXDSP_DIR}/fftwrap.c
    ${HUMLA_SPEEXDSP_DIR}/filterbank.c ${HUMLA_SPEEXDSP_DIR}/jitter.c
    ${HUMLA_SPEEXDSP_DIR}/kiss_fft.c ${HUMLA_SPEEXDSP_DIR}/kiss_fftr.c
    ${HUMLA_SPEEXDSP_DIR}/mdf.c ${HUMLA_SPEEXDSP_DIR}/preprocess.c
    ${HUMLA_SPEEXDSP_DIR}/resample.c ${HUMLA_SPEEXDSP_DIR}/scal.c)

function(humla_add_speexdsp name)  # name, then the extra compile options
  add_library(${name} STATIC ${HUMLA_SPEEXDSP_SOURCES})
  target_include_directories(${name}
      PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/speexdsp/include
      PRIVATE ${HUMLA_SPEEXDSP_DIR})
  target_compile_definitions(${name} PUBLIC __EMX__ PRIVATE FIXED_POINT USE_KISS_FFT EXPORT=)
  target_compile_options(${name} PRIVATE ${ARGN})
  target_link_libraries(${name} PUBLIC m)
endfunction()

humla_add_speexdsp(speexdsp_hosttest)

set(HUMLA_JNI_SPEEXDSP_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/test_jni_speexdsp.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../jni_speexdsp.cpp)

add_executable(test_jni_speexdsp ${HUMLA_JNI_SPEEXDSP_SOURCES})
target_include_directories(test_jni_speexdsp PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
target_link_libraries(test_jni_speexdsp PRIVATE speexdsp_hosttest m)
humla_add_labelled_test(jni_speexdsp test_jni_speexdsp plain)

if(HUMLA_HAVE_SANITIZERS)
  humla_add_speexdsp(speexdsp_hosttest_asan ${HUMLA_SAN_FLAGS})
  target_link_options(speexdsp_hosttest_asan PUBLIC -fsanitize=address,undefined)

  add_executable(test_jni_speexdsp_asan ${HUMLA_JNI_SPEEXDSP_SOURCES})
  target_include_directories(test_jni_speexdsp_asan PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
  target_compile_options(test_jni_speexdsp_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_jni_speexdsp_asan PRIVATE speexdsp_hosttest_asan m)
  humla_add_labelled_test(jni_speexdsp_sanitized test_jni_speexdsp_asan sanitized)
endif()

# ---------------------------------------------------------------- the opus bridge
# libopus is built with the same options as ../CMakeLists.txt. Only the bridge and the test are
# instrumented in the sanitized variant.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)
# abseil turns BUILD_TESTING on, and opus would then register its own suite here.
set(BUILD_TESTING OFF)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/opus ${CMAKE_BINARY_DIR}/opus EXCLUDE_FROM_ALL)

set(HUMLA_JNI_OPUS_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/test_jni_opus.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../jni_opus.cpp)

add_executable(test_jni_opus ${HUMLA_JNI_OPUS_SOURCES})
target_include_directories(test_jni_opus PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
target_link_libraries(test_jni_opus PRIVATE opus m)
humla_add_labelled_test(jni_opus test_jni_opus plain)

if(HUMLA_HAVE_SANITIZERS)
  add_executable(test_jni_opus_asan ${HUMLA_JNI_OPUS_SOURCES})
  target_include_directories(test_jni_opus_asan PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
  target_compile_options(test_jni_opus_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_options(test_jni_opus_asan PRIVATE -fsanitize=address,undefined)
  target_link_libraries(test_jni_opus_asan PRIVATE opus m)
  humla_add_labelled_test(jni_opus_sanitized test_jni_opus_asan sanitized)
endif()
