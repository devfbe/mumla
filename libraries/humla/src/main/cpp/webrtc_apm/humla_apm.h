/*
 * C interface to webrtc-audio-processing (AEC3, noise suppression, AGC2, high-pass filter).
 *
 * C linkage and C types only; no function here can throw or let an exception escape (see
 * humla_apm.cpp).
 *
 * The two audio streams have to be fed in a fixed relation:
 *
 *   for every 10 ms tick:
 *       humla_apm_process_render(h, far_end_frame_about_to_be_played);
 *       humla_apm_process_capture(h, near_end_frame_just_recorded);
 *
 * The far-end frame must be handed over before the near-end frame that will contain its echo.
 * Getting this wrong produces no error: every call still returns 0 and echo cancellation silently
 * stops working. tests/test_apm.c covers this.
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

/* Indices into humla_apm_config.aec3_tuning: the webrtc::EchoCanceller3Config fields a caller may
 * set. Each value is a float; integer fields are rounded, boolean fields are true unless 0.
 * Aec3Param in Aec3Tuning.kt lists the same names in the same order (Aec3ParamHeaderTest pins it). */
enum {
    /* suppressor.normal_tuning: used while the dominant near-end detector says "echo". */
    HUMLA_AEC3_NORMAL_LF_ENR_TRANSPARENT,
    HUMLA_AEC3_NORMAL_LF_ENR_SUPPRESS,
    HUMLA_AEC3_NORMAL_LF_EMR_TRANSPARENT,
    HUMLA_AEC3_NORMAL_HF_ENR_TRANSPARENT,
    HUMLA_AEC3_NORMAL_HF_ENR_SUPPRESS,
    HUMLA_AEC3_NORMAL_HF_EMR_TRANSPARENT,
    HUMLA_AEC3_NORMAL_MAX_INC_FACTOR,
    HUMLA_AEC3_NORMAL_MAX_DEC_FACTOR_LF,
    /* suppressor.nearend_tuning: used while it says "near end dominates". */
    HUMLA_AEC3_NEAREND_LF_ENR_TRANSPARENT,
    HUMLA_AEC3_NEAREND_LF_ENR_SUPPRESS,
    HUMLA_AEC3_NEAREND_LF_EMR_TRANSPARENT,
    HUMLA_AEC3_NEAREND_HF_ENR_TRANSPARENT,
    HUMLA_AEC3_NEAREND_HF_ENR_SUPPRESS,
    HUMLA_AEC3_NEAREND_HF_EMR_TRANSPARENT,
    HUMLA_AEC3_NEAREND_MAX_INC_FACTOR,
    HUMLA_AEC3_NEAREND_MAX_DEC_FACTOR_LF,
    /* suppressor, other */
    HUMLA_AEC3_NEAREND_AVERAGE_BLOCKS,
    HUMLA_AEC3_LAST_LF_BAND,
    HUMLA_AEC3_FIRST_HF_BAND,
    HUMLA_AEC3_FLOOR_FIRST_INCREASE,
    HUMLA_AEC3_CONSERVATIVE_HF_SUPPRESSION,
    /* suppressor.dominant_nearend_detection */
    HUMLA_AEC3_DNE_ENR_THRESHOLD,
    HUMLA_AEC3_DNE_ENR_EXIT_THRESHOLD,
    HUMLA_AEC3_DNE_SNR_THRESHOLD,
    HUMLA_AEC3_DNE_HOLD_DURATION,
    HUMLA_AEC3_DNE_TRIGGER_THRESHOLD,
    HUMLA_AEC3_DNE_USE_DURING_INITIAL_PHASE,
    HUMLA_AEC3_DNE_USE_UNBOUNDED_ECHO_SPECTRUM,
    /* suppressor.use_subband_nearend_detection and suppressor.subband_nearend_detection */
    HUMLA_AEC3_USE_SUBBAND_NEAREND_DETECTION,
    HUMLA_AEC3_SUBBAND_NEAREND_AVERAGE_BLOCKS,
    HUMLA_AEC3_SUBBAND1_LOW,
    HUMLA_AEC3_SUBBAND1_HIGH,
    HUMLA_AEC3_SUBBAND2_LOW,
    HUMLA_AEC3_SUBBAND2_HIGH,
    HUMLA_AEC3_SUBBAND_NEAREND_THRESHOLD,
    HUMLA_AEC3_SUBBAND_SNR_THRESHOLD,
    /* suppressor.high_bands_suppression */
    HUMLA_AEC3_HIGH_BANDS_ENR_THRESHOLD,
    HUMLA_AEC3_HIGH_BANDS_MAX_GAIN_DURING_ECHO,
    HUMLA_AEC3_ANTI_HOWLING_ACTIVATION_THRESHOLD,
    HUMLA_AEC3_ANTI_HOWLING_GAIN,
    /* ep_strength */
    HUMLA_AEC3_EP_DEFAULT_GAIN,
    HUMLA_AEC3_EP_DEFAULT_LEN,
    HUMLA_AEC3_EP_NEAREND_LEN,
    HUMLA_AEC3_EP_ECHO_CAN_SATURATE,
    HUMLA_AEC3_EP_BOUNDED_ERL,
    HUMLA_AEC3_EP_ERLE_ONSET_COMPENSATION_IN_DOMINANT_NEAREND,
    HUMLA_AEC3_EP_USE_CONSERVATIVE_TAIL_FREQUENCY_RESPONSE,
    /* erle */
    HUMLA_AEC3_ERLE_MIN,
    HUMLA_AEC3_ERLE_MAX_L,
    HUMLA_AEC3_ERLE_MAX_H,
    HUMLA_AEC3_ERLE_ONSET_DETECTION,
    HUMLA_AEC3_ERLE_NUM_SECTIONS,
    /* echo_audibility */
    HUMLA_AEC3_AUDIBILITY_LOW_RENDER_LIMIT,
    HUMLA_AEC3_AUDIBILITY_NORMAL_RENDER_LIMIT,
    HUMLA_AEC3_AUDIBILITY_FLOOR_POWER,
    HUMLA_AEC3_AUDIBILITY_THRESHOLD_LF,
    HUMLA_AEC3_AUDIBILITY_THRESHOLD_MF,
    HUMLA_AEC3_AUDIBILITY_THRESHOLD_HF,
    HUMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES,
    HUMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES_AT_INIT,
    /* filter lengths, in 64-sample blocks at 16 kHz (4 ms each) */
    HUMLA_AEC3_FILTER_REFINED_LENGTH_BLOCKS,
    HUMLA_AEC3_FILTER_COARSE_LENGTH_BLOCKS,
    HUMLA_AEC3_FILTER_REFINED_INITIAL_LENGTH_BLOCKS,
    HUMLA_AEC3_FILTER_COARSE_INITIAL_LENGTH_BLOCKS,
    /* comfort_noise */
    HUMLA_AEC3_COMFORT_NOISE_FLOOR_DBFS,
    HUMLA_AEC3_PARAM_COUNT
};

typedef struct {
    int echo_cancellation;        /* AEC3, full (non-mobile) mode */
    int noise_suppression;
    int noise_suppression_level;  /* 0 low, 1 moderate, 2 high, 3 very high; clamped */
    int gain_control;             /* AGC2 adaptive digital */
    int high_pass;
    /* NULL: AEC3 with webrtc's built-in default config, exactly as before tuning existed.
     * Otherwise HUMLA_AEC3_PARAM_COUNT values indexed by the enum above; only read by
     * humla_apm_create, and ignored unless echo_cancellation is set. */
    const float *aec3_tuning;
} humla_apm_config;

/* Writes webrtc's default value of every tunable AEC3 field into out[HUMLA_AEC3_PARAM_COUNT].
 * Passing these back as aec3_tuning gives the same echo canceller as passing NULL. */
void humla_apm_aec3_defaults(float *out) HUMLA_APM_NOEXCEPT;

/* sample_rate_hz must be 8000, 16000, 32000 or 48000. Returns NULL on any other rate, on a NULL
 * config, on an aec3_tuning with a non-finite value, a negative count or length, or a masking
 * threshold pair whose enr_transparent is not below its enr_suppress, and on allocation failure.
 * Other out-of-range tuning values are clamped by EchoCanceller3Config::Validate. */
humla_apm *humla_apm_create(int sample_rate_hz, const humla_apm_config *cfg) HUMLA_APM_NOEXCEPT;

/* Samples per 10 ms frame at the rate this instance was created with, i.e. exactly how many
 * int16_t humla_apm_process_capture and humla_apm_process_render read and write. Returns 0 for
 * a NULL handle. Callers must make sure their buffer is this long: a short buffer is an
 * out-of-bounds read and write, not an error code. */
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
