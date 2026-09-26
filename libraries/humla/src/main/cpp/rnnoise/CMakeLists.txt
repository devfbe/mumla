# RNNoise (BSD-3) built with the model embedded from model/weights_blob.bin.
enable_language(ASM)
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/rnnoise)
set(RNNOISE_MODEL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/model)

# Upstream's Makefile.am RNNOISE_SOURCES minus src/rnnoise_data.c (the weight arrays, replaced
# by model/rnnoise_data_init.c + the embedded blob). NEON (armeabi-v7a, arm64-v8a) and SSE2 are
# picked by src/vec.h from the target's predefined macros.
add_library(humla_rnnoise STATIC
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
    ${RNNOISE_MODEL_DIR}/rnnoise_data_init.c
    ${RNNOISE_MODEL_DIR}/rnnoise_model_blob.S
    ${CMAKE_CURRENT_SOURCE_DIR}/humla_rnnoise.c)

# x86: upstream's --enable-x86-rtcd (recommended by its README). SSE4.1 and AVX2 kernels are
# built with their own flags and chosen at runtime from CPUID.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64|i[3-6]86)$")
  set(RNNOISE_SSE4_1_SOURCE ${RNNOISE_DIR}/src/x86/nnet_sse4_1.c)
  set(RNNOISE_AVX2_SOURCE ${RNNOISE_DIR}/src/x86/nnet_avx2.c)
  target_sources(humla_rnnoise PRIVATE
      ${RNNOISE_DIR}/src/x86/x86_dnn_map.c
      ${RNNOISE_DIR}/src/x86/x86cpu.c
      ${RNNOISE_SSE4_1_SOURCE}
      ${RNNOISE_AVX2_SOURCE})
  target_compile_definitions(humla_rnnoise PRIVATE RNN_ENABLE_X86_RTCD CPU_INFO_BY_ASM)
  # Source properties are per directory; the top-level one is where the host tests build their
  # sanitized copy of this target.
  set_source_files_properties(${RNNOISE_SSE4_1_SOURCE}
      DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_SOURCE_DIR}
      PROPERTIES COMPILE_OPTIONS "-msse4.1")
  set_source_files_properties(${RNNOISE_AVX2_SOURCE}
      DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_SOURCE_DIR}
      PROPERTIES COMPILE_OPTIONS "-mavx;-mfma;-mavx2")
endif()

target_include_directories(humla_rnnoise
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${RNNOISE_DIR}/include ${RNNOISE_DIR}/src ${RNNOISE_MODEL_DIR})
# RNNOISE_BUILD: configure.ac:31, unconditional upstream.
# DISABLE_DEBUG_FLOAT: configure.ac:81-87, upstream's default. Has no effect on this compilation
#   but documents the flag model/weights_blob.bin was dumped with; the dump command in
#   model/README.md must keep it, or the blob gains float weights that change the arithmetic.
# USE_WEIGHTS_FILE: our choice (denoise.c:298) — no built-in arrays, model comes from the blob.
# HUMLA_RNNOISE_BLOB_PATH: consumed by the .incbin in model/rnnoise_model_blob.S.
target_compile_definitions(humla_rnnoise PRIVATE
    RNNOISE_BUILD
    DISABLE_DEBUG_FLOAT
    USE_WEIGHTS_FILE
    HUMLA_RNNOISE_BLOB_PATH="${RNNOISE_MODEL_DIR}/weights_blob.bin")
target_compile_options(humla_rnnoise PRIVATE -O2 -fvisibility=hidden -Wno-unused-function)
set_property(TARGET humla_rnnoise PROPERTY POSITION_INDEPENDENT_CODE ON)
# Rebuild the blob object when the weights change.
set_source_files_properties(${RNNOISE_MODEL_DIR}/rnnoise_model_blob.S PROPERTIES
    OBJECT_DEPENDS ${RNNOISE_MODEL_DIR}/weights_blob.bin)
if(NOT ANDROID)
  target_link_libraries(humla_rnnoise PUBLIC m)
endif()
