# Asserts that a named function in a linked test binary really carries AddressSanitizer
# instrumentation.
#
# This exists because test_apm_asan links humla_apm.cpp twice: once instrumented, from
# humla_apm_asan, and once not, from the humla_apm archive that supplies the ~200 uninstrumented
# upstream objects. Which definition ends up in the executable is decided by the order of the
# archives on the link line and by nothing else. It is currently the instrumented one. If that
# ever flips, every target still builds, apm_sanitized still passes, and it silently stops
# instrumenting the code it exists to instrument -- the same class of quiet, green-pipeline
# failure the rest of this directory is built to prevent, one level further down.
#
# A preprocessor check inside test_apm.c would not catch this. __SANITIZE_ADDRESS__ describes
# how the test translation unit was compiled, and that translation unit is compiled with the
# sanitizer flags unconditionally; it says nothing about which copy of the library the linker
# chose. Nor does anything observable at run time: ASan's allocator interceptors are
# process-wide, so heap redzones exist either way, and what is actually lost when the
# uninstrumented copy wins -- stack and global instrumentation inside the wrapper -- leaves no
# trace the process can query. The property is a property of the linked image, so it is checked
# on the linked image.
#
# Invoked as: cmake -DOBJDUMP=... -DBINARY=... -DSYMBOL=... -P assert_instrumented.cmake
execute_process(COMMAND ${OBJDUMP} -d --disassemble=${SYMBOL} ${BINARY}
                OUTPUT_VARIABLE disasm ERROR_VARIABLE err RESULT_VARIABLE rc)
if(NOT rc EQUAL 0)
  message(FATAL_ERROR "objdump failed on ${BINARY}: ${err}")
endif()
# A symbol that is not in the image disassembles to nothing, which must not read as a pass.
if(NOT disasm MATCHES "<${SYMBOL}>:")
  message(FATAL_ERROR
      "${SYMBOL} is not in ${BINARY}. Either the symbol was renamed or it was stripped; "
      "either way this check is no longer checking anything.")
endif()
string(REGEX MATCHALL "__asan" hits "${disasm}")
list(LENGTH hits count)
if(count EQUAL 0)
  message(FATAL_ERROR
      "${SYMBOL} in ${BINARY} contains no AddressSanitizer instrumentation. The uninstrumented "
      "copy of humla_apm.cpp won the link, so the sanitized test is exercising uninstrumented "
      "code. Check the order of humla_apm_asan and humla_apm on the link line.")
endif()
message(STATUS "${SYMBOL}: ${count} AddressSanitizer references in ${BINARY}")
