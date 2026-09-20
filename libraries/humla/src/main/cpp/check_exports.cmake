# Asserts that a humla_*.so exports its JNI entry points and nothing else.
#
# Run as a POST_BUILD step of every shared library in this directory (see humla_check_exports).
# It is the pin for -Wl,--exclude-libs,ALL and -fvisibility=hidden: without those, each library
# re-exports the static libc++ it contains, the dynamic linker may bind one library's C++ runtime
# to another's, and nothing fails until something odd happens on a device. Removing a flag is a
# one-line change that no test would otherwise notice.
#
# Invoked with -DNM=<llvm-nm> -DLIBRARY=<path to the .so>.

if(NOT NM OR NOT EXISTS "${LIBRARY}")
  message(FATAL_ERROR "check_exports: need -DNM=<nm> and an existing -DLIBRARY=<file>")
endif()

execute_process(COMMAND "${NM}" -D --defined-only "${LIBRARY}"
                OUTPUT_VARIABLE symbols RESULT_VARIABLE status
                OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "check_exports: ${NM} failed on ${LIBRARY}")
endif()

# Linker-generated symbols that exist in every ELF shared object and are not ours to hide.
set(allowed "_init" "_fini" "__bss_start" "_edata" "_end" "__bss_start__" "_bss_end__"
            "__bss_end__" "__end__" "__data_start" "__dso_handle")

string(REPLACE "\n" ";" lines "${symbols}")
set(unexpected "")
set(jni_count 0)
foreach(line IN LISTS lines)
  # "<address> <type> <name>", or "         <type> <name>" for absolute/undefined-value symbols.
  if(line MATCHES "[ \t]([A-Za-z])[ \t]+([^ \t]+)$")
    set(name "${CMAKE_MATCH_2}")
    if(name MATCHES "^Java_")
      math(EXPR jni_count "${jni_count} + 1")
    elseif(NOT name IN_LIST allowed)
      list(APPEND unexpected "${name}")
    endif()
  endif()
endforeach()

if(jni_count EQUAL 0)
  message(FATAL_ERROR
      "check_exports: ${LIBRARY} exports no Java_* entry point at all. System.loadLibrary would "
      "succeed and every external fun would then fail with UnsatisfiedLinkError.")
endif()

list(LENGTH unexpected n)
if(NOT n EQUAL 0)
  set(show ${n})
  if(show GREATER 10)
    set(show 10)
  endif()
  list(SUBLIST unexpected 0 ${show} head)
  string(REPLACE ";" "\n    " head "${head}")
  message(FATAL_ERROR
      "check_exports: ${LIBRARY} exports ${n} symbol(s) that are not JNI entry points, e.g.\n"
      "    ${head}\n"
      "Every humla_*.so statically contains libc++ and its codec; exporting any of that lets the "
      "dynamic linker bind one library's C++ runtime to another's. Check that "
      "-Wl,--exclude-libs,ALL and -fvisibility=hidden are still on this target.")
endif()
