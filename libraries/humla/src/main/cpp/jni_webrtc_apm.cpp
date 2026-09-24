/*
 * JNI bridge for libhumlaapm; the Kotlin side is WebRtcApmNative.kt and must change with it.
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

#include "jni_native_handle.h"
#include "webrtc_apm/humla_apm.h"

namespace {

/* webrtc::AudioProcessing::Error values, spelled out so the webrtc C++ headers stay out of the
 * JNI layer (see audio_processing.h in third_party/webrtc-audio-processing). */
constexpr jint kNullPointerError = -5;
constexpr jint kBadDataLengthError = -8;

humla::HandleTable& handles() {
    // Intentionally never destroyed: the table must outlive every handle it issued (static
    // destruction order could hand a late audio callback a destroyed mutex), and staying
    // reachable keeps LeakSanitizer quiet about the cells.
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
    // Mode 0 for both streams: the APM may modify the render buffer too.
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

// humla_apm_set_stream_delay_ms is intentionally not bridged: AEC3 estimates the delay itself.

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
