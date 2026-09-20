/*
 * C ABI wrapper around webrtc::AudioProcessing. See humla_apm.h for the contract.
 *
 * Exception safety is the point of this file, not a detail of it. Task 3 calls these functions
 * from JNI, and an exception unwinding out of a function with C linkage into a C caller, or into
 * the JVM's frames, is undefined behaviour rather than a crash with a stack trace. Two
 * independent measures are used:
 *
 *   - every entry point is declared noexcept, so the compiler is required to stop any escaping
 *     exception at this boundary (with std::terminate) instead of unwinding through it;
 *   - every entry point that can allocate wraps its body in catch (...) and returns a failure
 *     value, so the noexcept backstop is never actually reached.
 *
 * webrtc itself does not signal errors by throwing -- RTC_CHECK calls abort() -- so the only
 * realistic exception is std::bad_alloc from a container or from `new`. That an RTC_CHECK is
 * fatal rather than catchable is precisely why every argument is validated here, before webrtc
 * sees it: an unsupported sample rate or a null buffer must come back as a return value, not as
 * an abort inside the audio thread.
 */
#include "humla_apm.h"

#include <api/audio/audio_processing.h>

#include <cmath>
#include <cstddef>
#include <new>

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

}  // namespace

extern "C" humla_apm* humla_apm_create(int sample_rate_hz, const humla_apm_config* cfg) noexcept {
    if (!cfg) return nullptr;
    if (sample_rate_hz != 8000 && sample_rate_hz != 16000 && sample_rate_hz != 32000 &&
        sample_rate_hz != 48000) {
        return nullptr;
    }
    try {
        rtc::scoped_refptr<webrtc::AudioProcessing> apm = webrtc::AudioProcessingBuilder().Create();
        if (!apm) return nullptr;

        webrtc::AudioProcessing::Config c;
        c.pipeline.maximum_internal_processing_rate = 48000;
        c.high_pass_filter.enabled = cfg->high_pass != 0;
        c.echo_canceller.enabled = cfg->echo_cancellation != 0;
        c.echo_canceller.mobile_mode = false;
        c.noise_suppression.enabled = cfg->noise_suppression != 0;
        using NS = webrtc::AudioProcessing::Config::NoiseSuppression;
        // Clamped rather than rejected: the level comes from a user preference by way of JNI and
        // must never be able to reach the enum as an out-of-range value.
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
