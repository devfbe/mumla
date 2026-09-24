# Asserts that a named function in a linked test binary really carries AddressSanitizer
# instrumentation.
#
# test_apm_asan links humla_apm.cpp twice (instrumented from humla_apm_asan, uninstrumented from
# humla_apm), and link order alone decides which copy wins. If it flips, everything still passes
# while instrumenting nothing. Neither __SANITIZE_ADDRESS__ nor anything observable at run time
# can tell, so the linked image is checked.
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
