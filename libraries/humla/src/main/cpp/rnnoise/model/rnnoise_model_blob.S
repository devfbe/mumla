/* Embeds weights_blob.bin; HUMLA_RNNOISE_BLOB_PATH is passed by CMake.
 *
 * .hidden is spelled out because -fvisibility=hidden is a compiler flag and does not reach the
 * assembler: without it these two symbols land in .dynsym of every shared library that links
 * this static target, although nothing outside humla_rnnoise.c may reference them. */
    .section .rodata
    .balign 16
    .globl humla_rnnoise_model_blob
    .hidden humla_rnnoise_model_blob
humla_rnnoise_model_blob:
    .incbin HUMLA_RNNOISE_BLOB_PATH
humla_rnnoise_model_blob_end:
    .balign 4
    .globl humla_rnnoise_model_blob_size
    .hidden humla_rnnoise_model_blob_size
humla_rnnoise_model_blob_size:
    .int humla_rnnoise_model_blob_end - humla_rnnoise_model_blob

/* Without this the linker marks the whole library as needing an executable stack. */
    .section .note.GNU-stack,"",%progbits
