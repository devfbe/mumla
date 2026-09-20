/*
 * JNI bridge for libhumlaapm. The Kotlin declaration is WebRtcApmNative.kt; the two files are one
 * interface and have to be changed together.
 *
 * The three things that can go wrong here are all silent:
 *
 *   - A short Java array. humla_apm writes exactly humla_apm_frame_size() samples into the
 *     int16_t* it is given and cannot see how long the array really is (humla_apm.h says so
 *     explicitly). The frame size is therefore read back from the instance rather than computed
 *     from an assumed sample rate, and a shorter array is refused.
 *   - A handle used after release, or released twice. See jni_native_handle.h.
 *   - processRender and processCapture wired to the wrong C function. The APM returns 0 from
 *     every call either way and echo cancellation simply stops working, by about 21 dB. That is
 *     measured in tests/test_jni_bridges.cpp, driven through these very entry points, because
 *     nothing at compile time or run time can see it.
 *
 * Exceptions: every function is noexcept, and every humla_apm_* function it calls is noexcept
 * and catches (...) itself (humla_apm.cpp). No exception can reach the JVM's frames.
 *
 * Threads: processCapture and processRender take no lock and may run on the capture and playback
 * threads concurrently with each other; create and destroy take the handle table's mutex and
 * belong to the owning thread. humla_apm.h's ordering contract still holds -- render for the
 * frame about to be played, then capture for the frame just recorded.
 */
#include <jni.h>

#include <cstdint>

#include "jni_native_handle.h"
#include "webrtc_apm/humla_apm.h"

namespace {

/* webrtc::AudioProcessing::Error values, spelled out rather than included: pulling in
 * audio_processing.h would drag the C++ webrtc headers into the one layer that exists so that the
 * JVM never meets them. Checked against
 * third_party/webrtc-audio-processing/webrtc/api/audio/audio_processing.h:671-681. */
constexpr jint kNullPointerError = -5;
constexpr jint kBadDataLengthError = -8;

humla::HandleTable& handles() {
    // Intentionally never destroyed. The table has to outlive every handle it ever issued, and
    // static destruction order gives no such guarantee: an audio callback that has not been
    // joined yet would find a destroyed mutex. Leaving it alive also keeps every cell reachable
    // from a static root, which is what stops LeakSanitizer reporting the cells (a table
    // destroyed at exit frees its own nodes first and orphans them).
    static humla::HandleTable* table = new humla::HandleTable();
    return *table;
}

jint process(JNIEnv* env, jlong handle, jshortArray frame,
             int (*fn)(humla_apm*, int16_t*)) noexcept {
    auto* apm = static_cast<humla_apm*>(handles().get(handle));
    if (apm == nullptr || frame == nullptr) return kNullPointerError;
    if (env->GetArrayLength(frame) < humla_apm_frame_size(apm)) return kBadDataLengthError;
    jshort* data = env->GetShortArrayElements(frame, nullptr);
    if (data == nullptr) return kNullPointerError;  // OOM in the JVM, exception already pending
    jint err = fn(apm, reinterpret_cast<int16_t*>(data));
    // Mode 0 for both streams: humla_apm.h documents the render buffer as one the APM may
    // modify, so the Kotlin array sees what the APM produced either way.
    env->ReleaseShortArrayElements(frame, data, 0);
    return err;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_create(JNIEnv*, jobject, jint sampleRate,
                                                         jboolean aec, jboolean ns, jint nsLevel,
                                                         jboolean agc, jboolean highPass) noexcept {
    humla_apm_config cfg{aec ? 1 : 0, ns ? 1 : 0, nsLevel, agc ? 1 : 0, highPass ? 1 : 0};
    humla_apm* apm = humla_apm_create(sampleRate, &cfg);
    if (apm == nullptr) return 0;
    jlong handle = handles().add(apm);
    if (handle == 0) humla_apm_destroy(apm);  // the table could not take ownership
    return handle;
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_frameSize(JNIEnv*, jobject,
                                                            jlong handle) noexcept {
    return humla_apm_frame_size(static_cast<humla_apm*>(handles().get(handle)));
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_processCapture(JNIEnv* env, jobject, jlong handle,
                                                                 jshortArray frame) noexcept {
    return process(env, handle, frame, humla_apm_process_capture);
}

JNIEXPORT jint JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_processRender(JNIEnv* env, jobject, jlong handle,
                                                                jshortArray frame) noexcept {
    return process(env, handle, frame, humla_apm_process_render);
}

// humla_apm_set_stream_delay_ms is intentionally not bridged: AEC3 estimates the delay itself,
// and feeding it the true delay was measured to change the residual by less than 0.02 dB
// (tests/test_apm.c). An unused entry point would be dead surface.

JNIEXPORT jfloat JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_lastCaptureLevelDbfs(JNIEnv*, jobject,
                                                                       jlong handle) noexcept {
    return humla_apm_last_capture_level_dbfs(static_cast<humla_apm*>(handles().get(handle)));
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_native_WebRtcApmNative_destroy(JNIEnv*, jobject, jlong handle) noexcept {
    // release() hands the instance to exactly one caller, so destroying twice frees once.
    humla_apm_destroy(static_cast<humla_apm*>(handles().release(handle)));
}

}  // extern "C"
