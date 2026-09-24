/* Compatibility shim for RNNoise v0.2.
 *
 * At tag v0.2, src/vec.h and src/vec_neon.h include "os_support.h" and call OPUS_CLEAR(), but that
 * header only exists in libopus, so v0.2 fails to build for ARM and the scalar fallback. Upstream
 * fixed this in 372f7b4 (after v0.2, in no release tag) by using RNN_CLEAR() instead. This header
 * maps OPUS_CLEAR to RNN_CLEAR, which has the same memset expansion.
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
