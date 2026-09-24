/*
 * JNI bridge for libhumlarnnoise; the Kotlin side is RnnoiseNative.kt and must change with it.
 *
 * humla_rnnoise_process writes exactly HUMLA_RNNOISE_FRAME_SIZE samples into a bare int16_t*, so
 * shorter arrays are refused here. Handles may arrive after release() (see jni_native_handle.h).
 *
 * Every function is noexcept as a backstop: an exception unwinding into the JVM is undefined.
 *
 * Threads: processFrame is the audio-thread entry point and takes no lock. create and destroy take
 * the handle table's mutex and belong to the owning thread. One handle must not be processed from
 * two threads at once; separate handles may.
 */
#include <jni.h>

#include "jni_native_handle.h"
#include "rnnoise/humla_rnnoise.h"

namespace {

humla::HandleTable& handles() {
    // Intentionally never destroyed: the table must outlive every handle it issued (static
    // destruction order could hand a late audio callback a destroyed mutex), and staying
    // reachable keeps LeakSanitizer quiet about the cells.
    static humla::HandleTable* table = new humla::HandleTable();
    return *table;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_se_lublin_humla_audio_native_RnnoiseNative_create(JNIEnv*, jobject) noexcept {
    humla_rnnoise* denoiser = humla_rnnoise_create();
    if (denoiser == nullptr) return 0;
    jlong handle = handles().add(denoiser);
    if (handle == 0) humla_rnnoise_destroy(denoiser);  // the table could not take ownership
    return handle;
}

JNIEXPORT jfloat JNICALL
Java_se_lublin_humla_audio_native_RnnoiseNative_processFrame(JNIEnv* env, jobject, jlong handle,
                                                             jshortArray frame) noexcept {
    auto* denoiser = static_cast<humla_rnnoise*>(handles().get(handle));
    if (denoiser == nullptr || frame == nullptr) return -1.0f;
    // A short frame would be an out-of-bounds write inside rnnoise.
    if (env->GetArrayLength(frame) < HUMLA_RNNOISE_FRAME_SIZE) return -1.0f;
    jshort* data = env->GetShortArrayElements(frame, nullptr);
    if (data == nullptr) return -1.0f;  // OOM inside the JVM; an exception is already pending
    float probability = humla_rnnoise_process(denoiser, reinterpret_cast<int16_t*>(data));
    env->ReleaseShortArrayElements(frame, data, 0);  // mode 0: copy back and free
    return probability;
}

JNIEXPORT void JNICALL
Java_se_lublin_humla_audio_native_RnnoiseNative_destroy(JNIEnv*, jobject, jlong handle) noexcept {
    // release() hands the denoiser to exactly one caller, so destroying twice frees once.
    humla_rnnoise_destroy(static_cast<humla_rnnoise*>(handles().release(handle)));
}

}  // extern "C"
