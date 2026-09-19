# RNNoise model (pinned)

RNNoise v0.2 keeps its weights out of git; `autogen.sh`/`download_model.sh`
downloads `https://media.xiph.org/rnnoise/models/rnnoise_data-0b50c45.tar.gz`
(22 270 507 bytes,
sha256 `4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43`,
`model_version` = `0b50c45` at tag v0.2).

We build RNNoise with `-DUSE_WEIGHTS_FILE`, so the 29 MB `rnnoise_data.c`
arrays are compiled out, and embed the binary weight blob instead:

- `weights_blob.bin` (1 401 600 bytes,
  sha256 `47edcad7baeffb6442d9bfe8ea3b3ae728b50055c3abca1d69b4e31a806da27d`)
  produced from the tarball's `src/rnnoise_data.c` with

  ```sh
  gcc -O1 -Iinclude -Isrc -DDUMP_BINARY_WEIGHTS -DDISABLE_DEBUG_FLOAT \
      -o dump_weights_blob src/write_weights.c && ./dump_weights_blob
  ```

  in a checkout of rnnoise v0.2 that has the tarball's `src/rnnoise_data.[ch]`
  copied in. `-DDISABLE_DEBUG_FLOAT` is upstream's own default
  (`configure.ac:81-87`, `--enable-dnn-debug-float` defaults to `no`); it drops
  the seven float duplicates of the int8-quantised `conv2`/`gru*` weight
  matrices, which exist only for debugging. Without it the blob is 5 530 816
  bytes and does not match the hash above. `write_weights.c` serialises the
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
above, update the hashes here and in `docs/superpowers/plans/2026-09-19-b-audio.md`.
