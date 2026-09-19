/* Embeds weights_blob.bin; HUMLA_RNNOISE_BLOB_PATH is passed by CMake. */
    .section .rodata
    .balign 16
    .globl humla_rnnoise_model_blob
humla_rnnoise_model_blob:
    .incbin HUMLA_RNNOISE_BLOB_PATH
humla_rnnoise_model_blob_end:
    .balign 4
    .globl humla_rnnoise_model_blob_size
humla_rnnoise_model_blob_size:
    .int humla_rnnoise_model_blob_end - humla_rnnoise_model_blob

/* Without this the linker marks the whole library as needing an executable stack. */
    .section .note.GNU-stack,"",%progbits
