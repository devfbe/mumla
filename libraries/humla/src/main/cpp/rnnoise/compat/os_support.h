/* Compatibility shim for RNNoise v0.2.
 *
 * At tag v0.2 both src/vec.h (scalar path) and src/vec_neon.h (ARM NEON path)
 * do `#include "os_support.h"` and call `OPUS_CLEAR()`, but no such header
 * exists in the rnnoise tree -- it only exists in libopus.  As a result v0.2
 * compiles for x86 (which takes the vec_avx.h path) and fails for every ARM
 * ABI and for the scalar fallback.
 *
 * Upstream fixed this in commit 372f7b4b76cde4ca1ec4605353dd17898a99de38
 * ("Fix compilation errors.", GitHub #222), landed after v0.2 and not part of
 * any release tag: it drops the erroneous includes and rewrites the four
 * `OPUS_CLEAR()` call sites as `RNN_CLEAR()`.  The spec keeps third-party
 * submodules on upstream release tags, so instead of moving the submodule off
 * v0.2 we put this header on the include path, which makes those four call
 * sites (src/vec.h:53, :87, :127 and src/vec_neon.h:305) resolve to exactly
 * the macro upstream's fix uses.  `RNN_CLEAR` is
 * `memset(dst, 0, n * sizeof(*dst))` (src/common.h), byte-for-byte the same
 * expansion as libopus's `OPUS_CLEAR` (celt/os_support.h:90).
 *
 * Delete this file when the submodule moves to a tag that contains 372f7b4.
 */
#ifndef HUMLA_RNNOISE_OS_SUPPORT_SHIM_H
#define HUMLA_RNNOISE_OS_SUPPORT_SHIM_H

#include "opus_types.h"
#include "common.h"

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(dst, n) RNN_CLEAR(dst, n)
#endif

#endif /* HUMLA_RNNOISE_OS_SUPPORT_SHIM_H */
