# Post-link assertions about one of this directory's JNI shared libraries: that it exports its
# Java_* entry points and nothing else, and that its segments are 16 KB aligned.
#
# Run as a POST_BUILD step of every shared library here (see humla_check_library). The libraries
# are libhumla_opus / _speex / _speexdsp / _celt7 / _celt11 and, without the underscore because
# the CMake target names humla_rnnoise and humla_apm are taken by the static libraries they wrap,
# libhumlarnnoise and libhumlaapm.
#
# Both properties are set by one linker flag each, both are invisible everywhere a test could
# look, and both fail only on a device:
#
#   -Wl,--exclude-libs,ALL with -fvisibility=hidden -- without them each library re-exports the
#   static libc++ it contains and the dynamic linker may bind one library's C++ runtime to
#   another's.
#
#   -Wl,-z,max-page-size=16384 -- without it the segments are aligned to whatever the NDK's
#   default happens to be, and Android 15+ refuses to load a library that is not 16 KB aligned.
#
# Invoked with -DNM=<llvm-nm> -DREADELF=<llvm-readelf> -DLIBRARY=<path to the .so>.

if(NOT NM OR NOT READELF OR NOT EXISTS "${LIBRARY}")
  message(FATAL_ERROR
      "check_library: need -DNM=<nm>, -DREADELF=<readelf> and an existing -DLIBRARY=<file>")
endif()

execute_process(COMMAND "${NM}" -D --defined-only "${LIBRARY}"
                OUTPUT_VARIABLE symbols RESULT_VARIABLE status
                OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "check_library: ${NM} failed on ${LIBRARY}")
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
      "check_library: ${LIBRARY} exports no Java_* entry point at all. System.loadLibrary would "
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
      "check_library: ${LIBRARY} exports ${n} symbol(s) that are not JNI entry points, e.g.\n"
      "    ${head}\n"
      "Every library here statically contains libc++ and its codec; exporting any of that lets the "
      "dynamic linker bind one library's C++ runtime to another's. Check that "
      "-Wl,--exclude-libs,ALL and -fvisibility=hidden are still on this target.")
endif()

# ---------------------------------------------------------------- 16 KB page alignment
#
# Every PT_LOAD segment's p_align has to be at least 16384. This is the property the export check
# above cannot see and the one that fails latest: an under-aligned library links, packages,
# installs, and then refuses to load on a 16 KB device, with no build output anywhere saying so.
#
# Measured on NDK 29.0.14206865, with -Wl,-z,max-page-size=16384 removed and the build directories
# recreated (no max-page-size anywhere in build.ninja): armeabi-v7a drops to 0x1000, arm64-v8a and
# x86_64 stay at 0x4000, because the NDK has aligned to 16 KB by default since r28. So today the
# flag changes one ABI, and that ABI has no 16 KB devices. It stays on all seven targets anyway --
# the alignment then does not depend on an NDK default that was different one release ago, and one
# rule applies to all of them -- and this check is what keeps that true, rather than a comment
# claiming it.
execute_process(COMMAND "${READELF}" -lW "${LIBRARY}"
                OUTPUT_VARIABLE program_headers RESULT_VARIABLE status
                OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT status EQUAL 0)
  message(FATAL_ERROR "check_library: ${READELF} failed on ${LIBRARY}")
endif()

# "  LOAD  0x000000 0x00000000 0x00000000 0x07b68 0x07b68 R   0x4000" -- p_align is the last
# field, in hex, which is how both llvm-readelf and binutils readelf print it.
string(REPLACE "\n" ";" ph_lines "${program_headers}")
set(load_count 0)
foreach(line IN LISTS ph_lines)
  if(line MATCHES "^[ \t]+LOAD[ \t].*[ \t](0x[0-9a-fA-F]+)$")
    math(EXPR load_count "${load_count} + 1")
    math(EXPR align "${CMAKE_MATCH_1}")
    if(align LESS 16384)
      message(FATAL_ERROR
          "check_library: ${LIBRARY} has a PT_LOAD segment aligned to ${align} bytes, not 16384.\n"
          "    ${line}\n"
          "Android 15+ refuses to load a shared library whose segments are not 16 KB aligned, and "
          "nothing before the device says so -- it links, packages and installs. Check that "
          "-Wl,-z,max-page-size=16384 is still on this target.")
    endif()
  endif()
endforeach()

if(load_count EQUAL 0)
  message(FATAL_ERROR
      "check_library: found no PT_LOAD segment in ${LIBRARY}. Either that is not the ELF shared "
      "object this expects, or ${READELF} prints program headers in a format the match above does "
      "not recognise -- in which case the alignment check has been passing without reading "
      "anything.")
endif()
