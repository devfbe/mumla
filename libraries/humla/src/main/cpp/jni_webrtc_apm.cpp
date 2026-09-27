/*
 * JNI bridge for the WebRTC audio processing module; the Kotlin side is WebRtcApmNative.kt.
 *
 * humla_apm writes exactly humla_apm_frame_size() samples into the buffer it is given, so the
 * frame size is read from the instance and shorter arrays are refused. Swapping processRender and
 * processCapture fails silently (every call still returns 0, echo cancellation just stops);
 * tests/test_jni_bridges.cpp catches that.
 *
 * Every function is noexcept, and every humla_apm_* function catches (...) itself, so no
 * exception can reach the JVM.
 *
 * Threads: processCapture and processRender take no lock and may run concurrently on the capture
 * and playback threads; create and destroy take the handle table's mutex and belong to the owning
 * thread. Order per tick: render for the frame about to be played, then capture.
 */
#include <jni.h>

#include <cstdint>

#include "jni_bridges.h"
#include "jni_common.h"
#include "jni_native_handle.h"
#include "webrtc_apm/humla_apm.h"

namespace {

/* webrtc::AudioProcessing::Error values, spelled out so the webrtc C++ headers stay out of the
 * JNI layer (see audio_processing.h in third_party/webrtc-audio-processing). */
constexpr jint kNullPointerError = -5;
constexpr jint kBadDataLengthError = -8;

/* 10 ms at 48 kHz, the highest rate humla_apm accepts. */
constexpr std::size_t kMaxFrame = 480;

humla::HandleTable& processors() { return humla::handleTable<humla_apm>(); }

jint process(JNIEnv* env, jlong handle, jshortArray frame,
             int (*fn)(humla_apm*, int16_t*)) noexcept {
    auto* apm = static_cast<humla_apm*>(processors().get(handle));
    if (apm == nullptr || frame == nullptr) return kNullPointerError;
    if (env->GetArrayLength(frame) < humla_apm_frame_size(apm)) return kBadDataLengthError;
    jsize samples = humla_apm_frame_size(apm);
    humla::RegionBuffer<jshort, kMaxFrame> data(samples);
    if (data.data() == nullptr) return kNullPointerError;
    data.read(env, frame, 0, samples);
    jint err = fn(apm, reinterpret_cast<int16_t*>(data.data()));
    // Written back for both streams: the APM may modify the render buffer too.
    data.write(env, frame, 0, samples);
    return err;
}

/* aec3Tuning: null for webrtc's default AEC3 config, else exactly HUMLA_AEC3_PARAM_COUNT values
 * (Aec3Param in Aec3Tuning.kt); any other length is refused with 0 rather than read short. */
jlong create(JNIEnv* env, jobject, jint sampleRate, jboolean aec, jboolean ns, jint nsLevel, jboolean agc,
             jboolean highPass, jfloatArray aec3Tuning) noexcept {
    float tuning[HUMLA_AEC3_PARAM_COUNT];
    const float* tuningOrNull = nullptr;
    if (aec3Tuning != nullptr) {
        if (env->GetArrayLength(aec3Tuning) != HUMLA_AEC3_PARAM_COUNT) return 0;
        env->GetFloatArrayRegion(aec3Tuning, 0, HUMLA_AEC3_PARAM_COUNT, tuning);
        tuningOrNull = tuning;
    }
    humla_apm_config cfg{aec ? 1 : 0, ns ? 1 : 0, nsLevel, agc ? 1 : 0, highPass ? 1 : 0, tuningOrNull};
    humla_apm* apm = humla_apm_create(sampleRate, &cfg);
    if (apm == nullptr) return 0;
    jlong handle = processors().add(apm);
    if (handle == 0) humla_apm_destroy(apm);  // the table could not take ownership
    return handle;
}

jint frameSize(JNIEnv*, jobject, jlong handle) noexcept {
    return humla_apm_frame_size(static_cast<humla_apm*>(processors().get(handle)));
}

jint processCapture(JNIEnv* env, jobject, jlong handle, jshortArray frame) noexcept {
    return process(env, handle, frame, humla_apm_process_capture);
}

jint processRender(JNIEnv* env, jobject, jlong handle, jshortArray frame) noexcept {
    return process(env, handle, frame, humla_apm_process_render);
}

// humla_apm_set_stream_delay_ms is intentionally not bridged: AEC3 estimates the delay itself.

jfloat lastCaptureLevelDbfs(JNIEnv*, jobject, jlong handle) noexcept {
    return humla_apm_last_capture_level_dbfs(static_cast<humla_apm*>(processors().get(handle)));
}

void destroy(JNIEnv*, jobject, jlong handle) noexcept {
    // release() hands the instance to exactly one caller, so destroying twice frees once.
    humla_apm_destroy(static_cast<humla_apm*>(processors().release(handle)));
}

}  // namespace

bool humla::registerWebRtcApmNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 6> methods = {
        humla::nativeMethod("create", create),
        humla::nativeMethod("frameSize", frameSize),
        humla::nativeMethod("processCapture", processCapture),
        humla::nativeMethod("processRender", processRender),
        humla::nativeMethod("lastCaptureLevelDbfs", lastCaptureLevelDbfs),
        humla::nativeMethod("destroy", destroy),
    };
    return humla::registerNatives(env, "se/lublin/humla/audio/native/WebRtcApmNative", methods);
}
