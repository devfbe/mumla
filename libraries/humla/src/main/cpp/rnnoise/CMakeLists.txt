# RNNoise v0.2 (BSD-3) built with the model embedded from model/weights_blob.bin.
enable_language(ASM)
set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/rnnoise)
set(RNNOISE_MODEL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/model)

# Upstream's Makefile.am RNNOISE_SOURCES minus src/rnnoise_data.c (the 29 MB weight arrays,
# replaced by model/rnnoise_data_init.c + the embedded blob). The x86 RTCD sources are left
# out because upstream's --enable-x86-rtcd defaults to no; SSE2/NEON are still selected
# automatically by src/vec.h from __SSE2__ / __ARM_NEON.
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

target_include_directories(humla_rnnoise
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}
    PRIVATE ${RNNOISE_DIR}/include ${RNNOISE_DIR}/src ${RNNOISE_MODEL_DIR}
            ${CMAKE_CURRENT_SOURCE_DIR}/compat)   # os_support.h, see compat/os_support.h
# RNNOISE_BUILD: configure.ac:31, unconditional upstream.
# DISABLE_DEBUG_FLOAT: configure.ac:81-87, upstream's default (--enable-dnn-debug-float=no).
#   It changes nothing about THIS compilation -- the macro appears nowhere under src/ or
#   include/, only in the generated rnnoise_data.c that USE_WEIGHTS_FILE compiles out. It is
#   here to document the flag model/weights_blob.bin was dumped with, and it must stay in sync
#   with that blob: without it the blob also carries float copies of the int8 conv2/gru
#   weights, and compute_linear_ (src/nnet_arch.h:138-140) prefers float_weights whenever they
#   are present, so the network would run different arithmetic. Removing it here is harmless;
#   removing it from the dump command in model/README.md is not.
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
