/*
 * C ABI wrapper around webrtc::AudioProcessing. See humla_apm.h for the contract.
 *
 * An exception unwinding out of a C-linkage function into the JVM is undefined behaviour, so
 * every entry point is noexcept, and every entry point that can allocate also catches (...) and
 * returns a failure value.
 *
 * webrtc does not throw -- RTC_CHECK calls abort() -- so every argument is validated here before
 * webrtc sees it: an unsupported sample rate or a null buffer must be a return value, not an abort
 * on the audio thread.
 */
#include "humla_apm.h"

#include <api/audio/echo_canceller3_config.h>
#include <api/audio/echo_control.h>
#include <api/audio/audio_processing.h>
#include <modules/audio_processing/aec3/echo_canceller3.h>

#include <cmath>
#include <cstddef>
#include <memory>
#include <new>
#include <optional>

struct humla_apm {
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig config;
    float last_level_dbfs;
};

namespace {

float level_dbfs(const int16_t* frame, size_t n) {
    double acc = 0;
    for (size_t i = 0; i < n; i++) acc += double(frame[i]) * double(frame[i]);
    double rms = std::sqrt(acc / double(n));
    if (rms < 1.0) return -100.f;  // below one LSB: report digital silence
    float db = float(20.0 * std::log10(rms / 32768.0));
    return db < -100.f ? -100.f : db;
}

/* Every tunable field, once, by its index in humla_apm.h. Reading and writing both go through
 * this, so the index-to-field mapping cannot differ between humla_apm_aec3_defaults and create. */
template <typename Visit>
void visit_aec3(webrtc::EchoCanceller3Config& c, Visit& v) {
    auto& s = c.suppressor;
    v(HUMLA_AEC3_NORMAL_LF_ENR_TRANSPARENT, s.normal_tuning.mask_lf.enr_transparent);
    v(HUMLA_AEC3_NORMAL_LF_ENR_SUPPRESS, s.normal_tuning.mask_lf.enr_suppress);
    v(HUMLA_AEC3_NORMAL_LF_EMR_TRANSPARENT, s.normal_tuning.mask_lf.emr_transparent);
    v(HUMLA_AEC3_NORMAL_HF_ENR_TRANSPARENT, s.normal_tuning.mask_hf.enr_transparent);
    v(HUMLA_AEC3_NORMAL_HF_ENR_SUPPRESS, s.normal_tuning.mask_hf.enr_suppress);
    v(HUMLA_AEC3_NORMAL_HF_EMR_TRANSPARENT, s.normal_tuning.mask_hf.emr_transparent);
    v(HUMLA_AEC3_NORMAL_MAX_INC_FACTOR, s.normal_tuning.max_inc_factor);
    v(HUMLA_AEC3_NORMAL_MAX_DEC_FACTOR_LF, s.normal_tuning.max_dec_factor_lf);
    v(HUMLA_AEC3_NEAREND_LF_ENR_TRANSPARENT, s.nearend_tuning.mask_lf.enr_transparent);
    v(HUMLA_AEC3_NEAREND_LF_ENR_SUPPRESS, s.nearend_tuning.mask_lf.enr_suppress);
    v(HUMLA_AEC3_NEAREND_LF_EMR_TRANSPARENT, s.nearend_tuning.mask_lf.emr_transparent);
    v(HUMLA_AEC3_NEAREND_HF_ENR_TRANSPARENT, s.nearend_tuning.mask_hf.enr_transparent);
    v(HUMLA_AEC3_NEAREND_HF_ENR_SUPPRESS, s.nearend_tuning.mask_hf.enr_suppress);
    v(HUMLA_AEC3_NEAREND_HF_EMR_TRANSPARENT, s.nearend_tuning.mask_hf.emr_transparent);
    v(HUMLA_AEC3_NEAREND_MAX_INC_FACTOR, s.nearend_tuning.max_inc_factor);
    v(HUMLA_AEC3_NEAREND_MAX_DEC_FACTOR_LF, s.nearend_tuning.max_dec_factor_lf);
    v(HUMLA_AEC3_NEAREND_AVERAGE_BLOCKS, s.nearend_average_blocks);
    v(HUMLA_AEC3_LAST_LF_BAND, s.last_lf_band);
    v(HUMLA_AEC3_FIRST_HF_BAND, s.first_hf_band);
    v(HUMLA_AEC3_FLOOR_FIRST_INCREASE, s.floor_first_increase);
    v(HUMLA_AEC3_CONSERVATIVE_HF_SUPPRESSION, s.conservative_hf_suppression);
    auto& dne = s.dominant_nearend_detection;
    v(HUMLA_AEC3_DNE_ENR_THRESHOLD, dne.enr_threshold);
    v(HUMLA_AEC3_DNE_ENR_EXIT_THRESHOLD, dne.enr_exit_threshold);
    v(HUMLA_AEC3_DNE_SNR_THRESHOLD, dne.snr_threshold);
    v(HUMLA_AEC3_DNE_HOLD_DURATION, dne.hold_duration);
    v(HUMLA_AEC3_DNE_TRIGGER_THRESHOLD, dne.trigger_threshold);
    v(HUMLA_AEC3_DNE_USE_DURING_INITIAL_PHASE, dne.use_during_initial_phase);
    v(HUMLA_AEC3_DNE_USE_UNBOUNDED_ECHO_SPECTRUM, dne.use_unbounded_echo_spectrum);
    auto& sub = s.subband_nearend_detection;
    v(HUMLA_AEC3_USE_SUBBAND_NEAREND_DETECTION, s.use_subband_nearend_detection);
    v(HUMLA_AEC3_SUBBAND_NEAREND_AVERAGE_BLOCKS, sub.nearend_average_blocks);
    v(HUMLA_AEC3_SUBBAND1_LOW, sub.subband1.low);
    v(HUMLA_AEC3_SUBBAND1_HIGH, sub.subband1.high);
    v(HUMLA_AEC3_SUBBAND2_LOW, sub.subband2.low);
    v(HUMLA_AEC3_SUBBAND2_HIGH, sub.subband2.high);
    v(HUMLA_AEC3_SUBBAND_NEAREND_THRESHOLD, sub.nearend_threshold);
    v(HUMLA_AEC3_SUBBAND_SNR_THRESHOLD, sub.snr_threshold);
    auto& hb = s.high_bands_suppression;
    v(HUMLA_AEC3_HIGH_BANDS_ENR_THRESHOLD, hb.enr_threshold);
    v(HUMLA_AEC3_HIGH_BANDS_MAX_GAIN_DURING_ECHO, hb.max_gain_during_echo);
    v(HUMLA_AEC3_ANTI_HOWLING_ACTIVATION_THRESHOLD, hb.anti_howling_activation_threshold);
    v(HUMLA_AEC3_ANTI_HOWLING_GAIN, hb.anti_howling_gain);
    auto& ep = c.ep_strength;
    v(HUMLA_AEC3_EP_DEFAULT_GAIN, ep.default_gain);
    v(HUMLA_AEC3_EP_DEFAULT_LEN, ep.default_len);
    v(HUMLA_AEC3_EP_NEAREND_LEN, ep.nearend_len);
    v(HUMLA_AEC3_EP_ECHO_CAN_SATURATE, ep.echo_can_saturate);
    v(HUMLA_AEC3_EP_BOUNDED_ERL, ep.bounded_erl);
    v(HUMLA_AEC3_EP_ERLE_ONSET_COMPENSATION_IN_DOMINANT_NEAREND,
      ep.erle_onset_compensation_in_dominant_nearend);
    v(HUMLA_AEC3_EP_USE_CONSERVATIVE_TAIL_FREQUENCY_RESPONSE,
      ep.use_conservative_tail_frequency_response);
    v(HUMLA_AEC3_ERLE_MIN, c.erle.min);
    v(HUMLA_AEC3_ERLE_MAX_L, c.erle.max_l);
    v(HUMLA_AEC3_ERLE_MAX_H, c.erle.max_h);
    v(HUMLA_AEC3_ERLE_ONSET_DETECTION, c.erle.onset_detection);
    v(HUMLA_AEC3_ERLE_NUM_SECTIONS, c.erle.num_sections);
    auto& au = c.echo_audibility;
    v(HUMLA_AEC3_AUDIBILITY_LOW_RENDER_LIMIT, au.low_render_limit);
    v(HUMLA_AEC3_AUDIBILITY_NORMAL_RENDER_LIMIT, au.normal_render_limit);
    v(HUMLA_AEC3_AUDIBILITY_FLOOR_POWER, au.floor_power);
    v(HUMLA_AEC3_AUDIBILITY_THRESHOLD_LF, au.audibility_threshold_lf);
    v(HUMLA_AEC3_AUDIBILITY_THRESHOLD_MF, au.audibility_threshold_mf);
    v(HUMLA_AEC3_AUDIBILITY_THRESHOLD_HF, au.audibility_threshold_hf);
    v(HUMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES, au.use_stationarity_properties);
    v(HUMLA_AEC3_AUDIBILITY_USE_STATIONARITY_PROPERTIES_AT_INIT,
      au.use_stationarity_properties_at_init);
    v(HUMLA_AEC3_FILTER_REFINED_LENGTH_BLOCKS, c.filter.refined.length_blocks);
    v(HUMLA_AEC3_FILTER_COARSE_LENGTH_BLOCKS, c.filter.coarse.length_blocks);
    v(HUMLA_AEC3_FILTER_REFINED_INITIAL_LENGTH_BLOCKS, c.filter.refined_initial.length_blocks);
    v(HUMLA_AEC3_FILTER_COARSE_INITIAL_LENGTH_BLOCKS, c.filter.coarse_initial.length_blocks);
    v(HUMLA_AEC3_COMFORT_NOISE_FLOOR_DBFS, c.comfort_noise.noise_floor_dbfs);
}

struct Aec3Reader {
    float* out;
    int seen = 0;
    template <typename T>
    void operator()(int i, const T& field) {
        out[i] = static_cast<float>(field);
        seen++;
    }
};

/* Integer fields: counts and lengths up to a few thousand; anything past this is a caller bug. */
constexpr float kMaxIntegerParam = 1e6f;

struct Aec3Writer {
    const float* in;
    bool ok = true;
    int seen = 0;
    void operator()(int i, float& field) { field = in[i]; seen++; }
    void operator()(int i, bool& field) { field = in[i] != 0.f; seen++; }
    void operator()(int i, int& field) {
        if (std::fabs(in[i]) > kMaxIntegerParam) ok = false;
        else field = static_cast<int>(std::lround(in[i]));
        seen++;
    }
    void operator()(int i, size_t& field) {
        if (in[i] < 0.f || in[i] > kMaxIntegerParam) ok = false;
        else field = static_cast<size_t>(std::lround(in[i]));
        seen++;
    }
};

bool masks_ordered(const webrtc::EchoCanceller3Config::Suppressor::Tuning& t) {
    // suppression_gain.cc divides by (enr_suppress - enr_transparent).
    return t.mask_lf.enr_transparent < t.mask_lf.enr_suppress &&
           t.mask_hf.enr_transparent < t.mask_hf.enr_suppress;
}

/* Builds the tuned config, or returns false for a tuning create must refuse. */
bool aec3_config_from(const float* tuning, webrtc::EchoCanceller3Config* out) {
    for (int i = 0; i < HUMLA_AEC3_PARAM_COUNT; i++) {
        if (!std::isfinite(tuning[i])) return false;
    }
    webrtc::EchoCanceller3Config c;
    Aec3Writer writer{tuning};
    visit_aec3(c, writer);
    if (!writer.ok || writer.seen != HUMLA_AEC3_PARAM_COUNT) return false;
    if (!masks_ordered(c.suppressor.normal_tuning) || !masks_ordered(c.suppressor.nearend_tuning)) {
        return false;
    }
    webrtc::EchoCanceller3Config::Validate(&c);  // clamps the rest into webrtc's own ranges
    *out = c;
    return true;
}

/* What AudioProcessingImpl::InitializeEchoController builds by itself when no factory is set
 * (EchoCanceller3 with no multichannel config), with our config in place of the default one.
 * Create runs whenever the APM reinitialises, as the built-in path does. */
class TunedAec3Factory final : public webrtc::EchoControlFactory {
  public:
    explicit TunedAec3Factory(const webrtc::EchoCanceller3Config& config) : config_(config) {}

    std::unique_ptr<webrtc::EchoControl> Create(int sample_rate_hz, int num_render_channels,
                                                int num_capture_channels) override {
        return std::make_unique<webrtc::EchoCanceller3>(
            config_, std::nullopt, sample_rate_hz, static_cast<size_t>(num_render_channels),
            static_cast<size_t>(num_capture_channels));
    }

  private:
    const webrtc::EchoCanceller3Config config_;
};

}  // namespace

extern "C" void humla_apm_aec3_defaults(float* out) noexcept {
    if (!out) return;
    webrtc::EchoCanceller3Config c;
    Aec3Reader reader{out};
    visit_aec3(c, reader);
}

extern "C" humla_apm* humla_apm_create(int sample_rate_hz, const humla_apm_config* cfg) noexcept {
    if (!cfg) return nullptr;
    if (sample_rate_hz != 8000 && sample_rate_hz != 16000 && sample_rate_hz != 32000 &&
        sample_rate_hz != 48000) {
        return nullptr;
    }
    try {
        webrtc::AudioProcessingBuilder builder;
        // Without a tuning the builder is left alone: webrtc's own default path, unchanged.
        if (cfg->echo_cancellation != 0 && cfg->aec3_tuning != nullptr) {
            webrtc::EchoCanceller3Config aec3;
            if (!aec3_config_from(cfg->aec3_tuning, &aec3)) return nullptr;
            builder.SetEchoControlFactory(std::make_unique<TunedAec3Factory>(aec3));
        }
        rtc::scoped_refptr<webrtc::AudioProcessing> apm = builder.Create();
        if (!apm) return nullptr;

        webrtc::AudioProcessing::Config c;
        c.pipeline.maximum_internal_processing_rate = 48000;
        c.high_pass_filter.enabled = cfg->high_pass != 0;
        c.echo_canceller.enabled = cfg->echo_cancellation != 0;
        c.echo_canceller.mobile_mode = false;
        c.noise_suppression.enabled = cfg->noise_suppression != 0;
        using NS = webrtc::AudioProcessing::Config::NoiseSuppression;
        // Clamped rather than rejected: the level comes from a user preference via JNI.
        int lvl = cfg->noise_suppression_level < 0
                      ? 0
                      : (cfg->noise_suppression_level > 3 ? 3 : cfg->noise_suppression_level);
        c.noise_suppression.level = static_cast<NS::Level>(lvl);
        c.gain_controller2.enabled = cfg->gain_control != 0;
        c.gain_controller2.adaptive_digital.enabled = cfg->gain_control != 0;
        c.gain_controller2.fixed_digital.gain_db = 0.0f;
        apm->ApplyConfig(c);

        return new humla_apm{apm, webrtc::StreamConfig(sample_rate_hz, 1), -100.f};
    } catch (...) {
        return nullptr;
    }
}

extern "C" int humla_apm_frame_size(const humla_apm* h) noexcept {
    return h ? int(h->config.num_frames()) : 0;
}

extern "C" int humla_apm_process_capture(humla_apm* h, int16_t* frame) noexcept {
    if (!h || !frame) return webrtc::AudioProcessing::kNullPointerError;
    try {
        int err = h->apm->ProcessStream(frame, h->config, h->config, frame);
        if (err == webrtc::AudioProcessing::kNoError) {
            h->last_level_dbfs = level_dbfs(frame, h->config.num_frames());
        }
        return err;
    } catch (...) {
        return webrtc::AudioProcessing::kUnspecifiedError;
    }
}

extern "C" int humla_apm_process_render(humla_apm* h, int16_t* frame) noexcept {
    if (!h || !frame) return webrtc::AudioProcessing::kNullPointerError;
    try {
        return h->apm->ProcessReverseStream(frame, h->config, h->config, frame);
    } catch (...) {
        return webrtc::AudioProcessing::kUnspecifiedError;
    }
}

extern "C" int humla_apm_set_stream_delay_ms(humla_apm* h, int delay_ms) noexcept {
    if (!h) return webrtc::AudioProcessing::kNullPointerError;
    try {
        return h->apm->set_stream_delay_ms(delay_ms);
    } catch (...) {
        return webrtc::AudioProcessing::kUnspecifiedError;
    }
}

extern "C" float humla_apm_last_capture_level_dbfs(const humla_apm* h) noexcept {
    return h ? h->last_level_dbfs : -100.f;
}

extern "C" void humla_apm_destroy(humla_apm* h) noexcept {
    delete h;  // releases the scoped_refptr; deleting a null pointer is a no-op
}
