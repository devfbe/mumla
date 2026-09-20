/*
 * JNI bridge for libhumlarnnoise. The Kotlin declaration is RnnoiseNative.kt; the two files are
 * one interface and have to be changed together.
 *
 * Everything here is a boundary check. humla_rnnoise_process takes a bare int16_t* and writes
 * exactly HUMLA_RNNOISE_FRAME_SIZE samples into it, on the documented precondition that the
 * caller passes a buffer that long; a Java short[] that is shorter is an out-of-bounds write on
 * every frame, not an error. Handles come back from Kotlin as opaque longs and may arrive after
 * release(). Neither is visible to the wrapper underneath, so both are stopped here.
 *
 * Exceptions: every function is noexcept. Nothing below can throw -- the C wrapper is noexcept
 * and catches (...) itself, and HandleTable::add/release swallow their own allocation failures --
 * so the specification is a backstop that turns a future mistake into std::terminate at this
 * boundary rather than an exception unwinding into the JVM's frames, which is undefined.
 *
 * Threads: processFrame is the audio-thread entry point and takes no lock. create and destroy
 * take the handle table's mutex and belong to whichever thread owns the instance. One handle is
 * not safe to process from two threads at once (humla_rnnoise.h), but separate handles are.
 */
#include <jni.h>

#include "jni_native_handle.h"
#include "rnnoise/humla_rnnoise.h"

namespace {

humla::HandleTable& handles() {
    // Intentionally never destroyed. The table has to outlive every handle it ever issued, and
    // static destruction order gives no such guarantee: an audio callback that has not been
    // joined yet would find a destroyed mutex. Leaving it alive also keeps every cell reachable
    // from a static root, which is what stops LeakSanitizer reporting the cells (a table
    // destroyed at exit frees its own nodes first and orphans them).
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
    // The length check is the reason this function is not a one-liner: a short frame is an
    // out-of-bounds write inside rnnoise, and neither rnnoise nor Kotlin can see it coming.
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
