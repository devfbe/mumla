cmake_minimum_required(VERSION 3.22)
project(humla_native_tests C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

# ---------------------------------------------------------------- sanitizer availability
# Detected once, up front, because both library groups below need the answer.
#
# Guarded so the sanitized tests only register where the host toolchain actually supports the
# flags. The Android build never enters this directory, but the cross-compile check is spelled
# out anyway.
#
# That guard used to be able to fail silently: when the compile check came back false, ctest
# registered only the plain tests, ran them and exited 0, so a toolchain without sanitizer
# runtimes switched off the entire sanitized half of this directory with nothing a green
# pipeline would surface. HUMLA_REQUIRE_SANITIZERS closes that -- where the sanitized tests are
# meant to run, configuration fails instead of quietly shrinking.
#
# Configure-time failure rather than an expected-test-count assertion in CI, because a count has
# to be edited in .gitlab-ci.yml every time a test is added here (this change alone adds two
# entries), and an assertion that is routinely edited to match reality stops being an assertion.
# The check below cannot drift from the guard it protects: it reads the same variable the guard
# does. The other half of the fix lives in .gitlab-ci.yml, where ctest runs with
# --no-tests=error so that a label selecting nothing fails instead of passing.
option(HUMLA_REQUIRE_SANITIZERS
       "Fail configuration if the host toolchain cannot build the sanitized tests"
       $ENV{HUMLA_REQUIRE_SANITIZERS})
# option() only writes the cache the first time. Without the line below, a build directory that
# was first configured without HUMLA_REQUIRE_SANITIZERS in the environment keeps :BOOL=OFF
# forever, and reconfiguring it with the variable set changes nothing -- the guard would go on
# being off in exactly the situation someone had just asked for it to be on. CI never sees this
# because build/ is not cached; a developer with a warm build directory does. The environment is
# therefore treated as authoritative whenever it says anything at all, in either direction,
# which also means it outranks -DHUMLA_REQUIRE_SANITIZERS on the command line. Unset it to
# hand control back to -D.
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
  # -O1 -g -fno-omit-frame-pointer rather than the real targets' -O2: it is what makes a
  # sanitizer report name a line.
  set(HUMLA_SAN_FLAGS -O1 -g -fno-omit-frame-pointer
                      -fsanitize=address,undefined -fno-sanitize-recover=all)
  # print_stacktrace makes a UBSan finding actionable; halt_on_error stops at the first one so
  # ctest reports a failure instead of a pile of warnings and exit code 0.
  set(HUMLA_SAN_TEST_ENV
      "UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1;ASAN_OPTIONS=detect_leaks=1")
else()
  message(STATUS "humla: no ASan/UBSan on this toolchain, skipping the sanitized tests")
endif()

# Every test carries a label, so CI can run the fast set on every push and the slow instrumented
# set on a schedule. Both invocations need ctest --no-tests=error, or a label that matches
# nothing passes.
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

# The same sources again under ASan+UBSan. This is not belt-and-braces: part of what this
# directory has to defend against is invisible to a plain build. The trap documented in
# humla_rnnoise_destroy (rnnoise_model_free() reading an uninitialised FILE*) was
# mutation-tested -- the mutant passes the plain binary 60 runs out of 60, and fails the
# sanitized one 5 out of 5, because ASan fills fresh heap with 0xbe and the garbage FILE*
# becomes a deterministic SEGV inside fclose(). LeakSanitizer (on by default with ASan on
# Linux) additionally turns the 200 create/destroy cycles into a real leak check.
if(HUMLA_HAVE_SANITIZERS)
  # A second, instrumented copy of the library: the bugs worth catching live in rnnoise and in
  # the wrapper, not in the test. The source list, include dirs and definitions are read back
  # off the real target so the two cannot drift apart.
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

# test_apm.c is C on purpose (see its header comment): it is the proof that humla_apm.h is valid
# C and that the wrapper's symbols really have C linkage. CMake would therefore pick the C
# driver to link it, which cannot resolve the C++ runtime that humla_apm and webrtc pull in, so
# the link step is switched to the C++ driver explicitly.
add_executable(test_apm test_apm.c)
set_target_properties(test_apm PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(test_apm PRIVATE humla_apm m)
humla_add_labelled_test(apm test_apm plain)

if(HUMLA_HAVE_SANITIZERS)
  # Only the wrapper is instrumented by default, not the ~200 upstream translation units:
  # rebuilding those under ASan dominates the whole directory's build time (numbers in the task
  # report), and the bug class this repository keeps hitting -- a handle used after free, a
  # frame buffer written past its end across the JNI boundary -- lives in humla_apm.cpp and in
  # its caller, not in upstream's DSP. ASan still sees every allocation humla_apm makes and
  # every access the test performs, because the malloc interceptors are process-wide; what it
  # cannot see is accesses made inside the uninstrumented upstream objects. Configure with
  # -DHUMLA_SANITIZE_WEBRTC=ON when a suspected fault points in there.
  option(HUMLA_SANITIZE_WEBRTC "Also instrument the vendored webrtc sources" OFF)

  if(HUMLA_SANITIZE_WEBRTC)
    get_target_property(HUMLA_APM_ASAN_SOURCES humla_apm SOURCES)
  else()
    set(HUMLA_APM_ASAN_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc_apm/humla_apm.cpp)
  endif()

  add_library(humla_apm_asan STATIC ${HUMLA_APM_ASAN_SOURCES})
  # humla_apm_buildflags is the same INTERFACE target the real library uses, so the include
  # dirs, the compile definitions and the abseil dependency cannot drift between the two.
  target_link_libraries(humla_apm_asan PRIVATE humla_apm_buildflags)
  target_include_directories(humla_apm_asan PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../webrtc_apm)
  target_compile_options(humla_apm_asan PRIVATE
      ${HUMLA_SAN_FLAGS} -fvisibility=hidden -Wno-deprecated-declarations)
  target_link_options(humla_apm_asan PUBLIC -fsanitize=address,undefined)
  if(NOT HUMLA_SANITIZE_WEBRTC)
    # The uninstrumented upstream objects still have to come from somewhere. humla_apm.cpp is
    # in both archives; the instrumented one is listed first so its definitions win.
    #
    # "so its definitions win" is an assumption about link order, and an assumption that fails
    # silently: every target would still build and apm_sanitized would still pass while
    # instrumenting nothing. apm_is_instrumented below stops asserting it and starts checking
    # it, on the linked image. See assert_instrumented.cmake for why it cannot be checked from
    # inside the test process.
    target_link_libraries(humla_apm_asan PUBLIC humla_apm)
  endif()

  add_executable(test_apm_asan test_apm.c)
  set_target_properties(test_apm_asan PROPERTIES LINKER_LANGUAGE CXX)
  target_compile_options(test_apm_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_apm_asan PRIVATE humla_apm_asan m)

  humla_add_labelled_test(apm_sanitized test_apm_asan sanitized)

  # objdump is a hard requirement here rather than a soft one: making the check optional would
  # reintroduce exactly the silent shrinkage HUMLA_REQUIRE_SANITIZERS was added to prevent.
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

# ---------------------------------------------------------------- the JNI bridges
# jni_rnnoise.cpp and jni_webrtc_apm.cpp are compiled and executed here through a stand-in
# JNIEnv (jni_env_stub.h). Everything the JNI layer can get wrong -- a missing length check, a
# handle freed twice, a bridge wired to the wrong C function -- is invisible from Kotlin and
# invisible from the C wrappers underneath, and none of it produces an error code. The Android
# build only proves these files compile and link.
#
# The JDK headers are a hard requirement, for the same reason objdump is below: making the test
# optional would reintroduce the silent shrinkage HUMLA_REQUIRE_SANITIZERS exists to prevent.
# They come from the nix dev shell (JAVA_HOME), which CI and every developer already has because
# Gradle needs it. The NDK's jni.h is a different file, but only in ways this test does not
# depend on: JNINativeInterface's layout is fixed by the JNI specification.
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
# The bridges include "rnnoise/humla_rnnoise.h" and "webrtc_apm/humla_apm.h", i.e. relative to
# the cpp/ directory, exactly as they do in the Android build.
set(HUMLA_JNI_BRIDGE_INCLUDES ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${HUMLA_JNI_INCLUDE_DIR} ${HUMLA_JNI_MD_INCLUDE_DIR})

add_executable(test_jni_bridges ${HUMLA_JNI_BRIDGE_SOURCES})
target_include_directories(test_jni_bridges PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
target_link_libraries(test_jni_bridges PRIVATE humla_rnnoise humla_apm m)
humla_add_labelled_test(jni_bridges test_jni_bridges plain)

if(HUMLA_HAVE_SANITIZERS)
  # The same bridges against the instrumented libraries. This is where the rnnoise short-buffer
  # case stops being an assertion about a return value and becomes a trapped heap-buffer-overflow:
  # jni_env_stub.h hands out array copies that are exact-size malloc blocks, so element 480 of a
  # 480-element frame is in ASan's redzone. LeakSanitizer additionally catches a bridge that
  # returns without releasing its array copy.
  add_executable(test_jni_bridges_asan ${HUMLA_JNI_BRIDGE_SOURCES})
  target_include_directories(test_jni_bridges_asan PRIVATE ${HUMLA_JNI_BRIDGE_INCLUDES})
  target_compile_options(test_jni_bridges_asan PRIVATE ${HUMLA_SAN_FLAGS})
  target_link_libraries(test_jni_bridges_asan PRIVATE humla_rnnoise_asan humla_apm_asan m)
  humla_add_labelled_test(jni_bridges_sanitized test_jni_bridges_asan sanitized)
endif()
