cmake_minimum_required(VERSION 3.22)
project(humla_native_tests C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
enable_testing()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../rnnoise ${CMAKE_BINARY_DIR}/rnnoise)

add_executable(test_rnnoise test_rnnoise.c)
target_link_libraries(test_rnnoise PRIVATE humla_rnnoise m)
add_test(NAME rnnoise COMMAND test_rnnoise)

# ---------------------------------------------------------------- sanitized variant
# The same sources again under ASan+UBSan. This is not belt-and-braces: part of what this
# directory has to defend against is invisible to a plain build. The trap documented in
# humla_rnnoise_destroy (rnnoise_model_free() reading an uninitialised FILE*) was
# mutation-tested -- the mutant passes the plain binary 60 runs out of 60, and fails the
# sanitized one 5 out of 5, because ASan fills fresh heap with 0xbe and the garbage FILE*
# becomes a deterministic SEGV inside fclose(). LeakSanitizer (on by default with ASan on
# Linux) additionally turns the 200 create/destroy cycles into a real leak check.
#
# Guarded so it only registers where the host toolchain actually supports the flags. The
# Android build never enters this directory, but the cross-compile check is spelled out anyway.
if(NOT ANDROID AND NOT CMAKE_CROSSCOMPILING)
  include(CheckCSourceCompiles)
  set(CMAKE_REQUIRED_FLAGS "-fsanitize=address,undefined")
  check_c_source_compiles("int main(void) { return 0; }" HUMLA_HAVE_SANITIZERS)
  unset(CMAKE_REQUIRED_FLAGS)
endif()

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
  # -O1 -g -fno-omit-frame-pointer rather than the target's -O2: it is what makes a sanitizer
  # report name a line. The sanitizer flags are restricted to C so the .incbin assembly file is
  # assembled exactly as it is in the real target.
  target_compile_options(humla_rnnoise_asan PRIVATE
      $<$<COMPILE_LANGUAGE:C>:-O1 -g -fno-omit-frame-pointer -fvisibility=hidden
                              -Wno-unused-function
                              -fsanitize=address,undefined -fno-sanitize-recover=all>)
  target_link_options(humla_rnnoise_asan PUBLIC -fsanitize=address,undefined)
  target_link_libraries(humla_rnnoise_asan PUBLIC m)

  add_executable(test_rnnoise_asan test_rnnoise.c)
  target_compile_options(test_rnnoise_asan PRIVATE
      -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all)
  target_link_libraries(test_rnnoise_asan PRIVATE humla_rnnoise_asan m)

  add_test(NAME rnnoise_sanitized COMMAND test_rnnoise_asan)
  # print_stacktrace makes a UBSan finding actionable; halt_on_error stops at the first one so
  # ctest reports a failure instead of a pile of warnings and exit code 0.
  set_tests_properties(rnnoise_sanitized PROPERTIES ENVIRONMENT
      "UBSAN_OPTIONS=print_stacktrace=1:halt_on_error=1;ASAN_OPTIONS=detect_leaks=1")
else()
  message(STATUS "humla: no ASan/UBSan on this toolchain, skipping the rnnoise_sanitized test")
endif()
