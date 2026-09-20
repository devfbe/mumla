/*
 * C interface to webrtc-audio-processing (AEC3, noise suppression, AGC2, high-pass filter).
 *
 * Everything below has C linkage and C types only. The JNI layer that calls it must not have to
 * deal with C++ name mangling, C++ types or exceptions: no function here can throw or let an
 * exception from webrtc or the C++ runtime escape (see humla_apm.cpp), so a caller compiled
 * without exception support, or a caller that is plain C, is safe.
 *
 * The module is stateful and the two audio streams have to be fed in a fixed relation:
 *
 *   for every 10 ms tick:
 *       humla_apm_process_render(h, far_end_frame_about_to_be_played);
 *       humla_apm_process_capture(h, near_end_frame_just_recorded);
 *
 * The far-end frame must be handed over before the near-end frame that will contain its echo.
 * Getting this wrong does not produce an error: every call still returns 0 and echo
 * cancellation simply stops working. tests/test_apm.c pins the difference.
 */
#ifndef HUMLA_APM_H
#define HUMLA_APM_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Every function below is noexcept in C++ (see humla_apm.cpp): no exception can cross this
 * boundary. The macro keeps the header valid C. */
#ifdef __cplusplus
#  define HUMLA_APM_NOEXCEPT noexcept
#else
#  define HUMLA_APM_NOEXCEPT
#endif

typedef struct humla_apm humla_apm;

typedef struct {
    int echo_cancellation;        /* AEC3, full (non-mobile) mode */
    int noise_suppression;
    int noise_suppression_level;  /* 0 low, 1 moderate, 2 high, 3 very high; clamped */
    int gain_control;             /* AGC2 adaptive digital */
    int high_pass;
} humla_apm_config;

/* sample_rate_hz must be 8000, 16000, 32000 or 48000. Returns NULL on any other rate, on a NULL
 * config and on allocation failure. */
humla_apm *humla_apm_create(int sample_rate_hz, const humla_apm_config *cfg) HUMLA_APM_NOEXCEPT;

/* Samples per 10 ms frame at the rate this instance was created with, i.e. exactly how many
 * int16_t humla_apm_process_capture and humla_apm_process_render read and write. Returns 0 for
 * a NULL handle. Callers that receive a buffer from somewhere else -- the JNI layer receiving a
 * Java short[] -- must check the buffer really is this long before passing it in: neither this
 * wrapper nor webrtc can see the length, and a short buffer is an out-of-bounds read and write,
 * not an error code. */
int humla_apm_frame_size(const humla_apm *h) HUMLA_APM_NOEXCEPT;

/* Process one 10 ms mono near-end frame in place. Returns 0 on success
 * (webrtc::AudioProcessing::kNoError), a negative webrtc::AudioProcessing::Error otherwise. */
int humla_apm_process_capture(humla_apm *h, int16_t *frame) HUMLA_APM_NOEXCEPT;

/* Feed one 10 ms mono far-end (playback) frame; the buffer may be modified. Returns 0 on
 * success. Must be called before the capture frame that carries its echo. */
int humla_apm_process_render(humla_apm *h, int16_t *frame) HUMLA_APM_NOEXCEPT;

/* Optional hint about the capture-to-render round trip. AEC3 estimates the delay itself, so
 * this exists for the host test and is deliberately not exported through JNI. */
int humla_apm_set_stream_delay_ms(humla_apm *h, int delay_ms) HUMLA_APM_NOEXCEPT;

/* RMS level in dBFS of the last frame returned by humla_apm_process_capture, -100 for digital
 * silence and for a NULL handle. */
float humla_apm_last_capture_level_dbfs(const humla_apm *h) HUMLA_APM_NOEXCEPT;

/* Releases the instance. A NULL handle is accepted and ignored. */
void humla_apm_destroy(humla_apm *h) HUMLA_APM_NOEXCEPT;

#ifdef __cplusplus
}
#endif

#endif /* HUMLA_APM_H */
