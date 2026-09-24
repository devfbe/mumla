# RNNoise model (pinned)

RNNoise keeps its weights out of git; `autogen.sh`/`download_model.sh`
downloads `https://media.xiph.org/rnnoise/models/rnnoise_data-<model_version>.tar.gz`
and checks that its sha256 equals `model_version`. The submodule is at 70f1d25
(2025-02-22), whose `model_version` is
`0a8755f8e2d834eff6a54714ecc7d75f9932e845df35f8b59bc52a7cfe6e8b37`
(58 603 099 bytes).

The tarball ships two models with the same layer sizes: the default one
(`src/rnnoise_data.c`, dense) and a sparser "little" one
(`src/rnnoise_data_little.c`, blob 1 553 664 bytes). We embed the default.

We build RNNoise with `-DUSE_WEIGHTS_FILE`, so the 78 MB `rnnoise_data.c`
arrays are compiled out, and embed the binary weight blob instead:

- `weights_blob.bin` (3 544 320 bytes,
  sha256 `1ad07b428be34c74d9678d05b3c387b73ccf0e6203a1a5f9ce58689b30b303c8`)
  produced from the tarball's `src/rnnoise_data.c` with

  ```sh
  gcc -O1 -Iinclude -Isrc -DDUMP_BINARY_WEIGHTS -DDISABLE_DEBUG_FLOAT \
      -o dump_weights_blob src/write_weights.c && ./dump_weights_blob
  ```

  in a checkout of the submodule's commit with the tarball extracted into it.
  `-DDISABLE_DEBUG_FLOAT` is upstream's own default
  (`configure.ac:81-87`, `--enable-dnn-debug-float` defaults to `no`); it drops
  the seven float duplicates of the int8-quantised `conv2`/`gru*` weight
  matrices, which exist only for debugging. Without it the blob is 14 751 424
  bytes and does not match the hash above — and, more importantly, it is not the
  same network. `compute_linear_` (`src/nnet_arch.h:138-140`) tests
  `linear->float_weights != NULL` *before* the int8 path, and `linear_init`
  loads the float arrays whenever the blob offers them
  (`src/parse_lpcnet_weights.c:154-165`, via `opt_array_check`). A blob dumped
  without this flag therefore silently runs `conv2` and `gru1`-`gru3` through
  float arithmetic instead of the quantised path upstream ships by default. This
  flag is a correctness setting, not a size setting. `write_weights.c` serialises the
  arrays field by field into a fully initialised, padding-free 64-byte
  `WeightHead` (`src/nnet.h:54-61`, `celt_assert(sizeof(h) == WEIGHT_BLOCK_SIZE)`),
  so the output is byte-identical regardless of compiler or optimisation level
  — verified at `-O0`, `-O1` and `-O2`.
- `rnnoise_data.h`: verbatim copy of the tarball's `src/rnnoise_data.h`.
- `rnnoise_data_init.c`: the `init_rnnoise()` function copied verbatim from the
  tarball's `src/rnnoise_data.c` (where it sits under `#ifndef
  DUMP_BINARY_WEIGHTS`), plus that file's `config.h`/`rnnoise_data.h`
  preamble. The weight arrays around it are compiled out by
  `USE_WEIGHTS_FILE`.
- `rnnoise_model_blob.S`: `.incbin`s `weights_blob.bin` as
  `humla_rnnoise_model_blob` / `humla_rnnoise_model_blob_size`.

Nothing here is fetched at build time: the blob is checked in, so a fresh clone
plus `git submodule update --init` reproduces the same binary byte for byte.

To upgrade: bump the submodule, read its `model_version`, repeat the steps
above and update the hashes here.
