/*
 * JNI bridge for libspeexdsp: the resampler, the jitter buffer and the preprocessor.
 *
 * Every entry point here has the same hazard. libspeexdsp takes the number of samples or bytes to
 * touch from somewhere other than the buffer -- speex_preprocess_run from the frame size the
 * state was created with, speex_resampler_process_int and jitter_buffer_put from counts the
 * caller supplies separately -- so nothing under this layer can tell whether the Java array is
 * long enough. The binding this file replaced did not check, and at ultra-wideband speex wrote
 * 640 samples into a 480-element array on every frame; that was a crash in the field, not a
 * theoretical one. Each function below therefore reads the array's real length and clamps or
 * refuses. tests/test_jni_speexdsp.cpp reproduces the original overrun and fails without them.
 */
#include <jni.h>
#include <new>
#include <speex/speex_jitter.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include "jni_handle.h"

#define RS(name) Java_se_lublin_humla_audio_native_SpeexResamplerNative_##name
#define JB(name) Java_se_lublin_humla_audio_native_SpeexJitterNative_##name
#define PP(name) Java_se_lublin_humla_audio_native_SpeexPreprocessNative_##name

namespace {

/* The preprocessor writes the frame size it was CREATED with, and libspeexdsp has no ctl to ask
 * it afterwards, so the size is kept beside the state and compared against the array in PP(run).
 * The handle Kotlin holds is this struct, not the SpeexPreprocessState. */
struct PreprocessHandle {
    SpeexPreprocessState* state;
    int frameSize;
};

/* Clamps a caller-supplied element count to what the array can actually hold. */
jint clampToArray(JNIEnv* env, jint count, jarray array) {
    if (count < 0) return 0;
    jsize capacity = env->GetArrayLength(array);
    return count > capacity ? static_cast<jint>(capacity) : count;
}

}  // namespace

extern "C" {

// ---- resampler ----

JNIEXPORT jlong JNICALL RS(init)(JNIEnv* env, jobject, jint channels, jint inRate, jint outRate, jint quality, jintArray error) {
    int err = 0;
    SpeexResamplerState* st = speex_resampler_init(channels, inRate, outRate, quality, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL RS(processInt)(JNIEnv* env, jobject, jlong state, jint channelIndex, jshortArray input, jintArray inLen, jshortArray out, jintArray outLen) {
    auto* st = fromHandle<SpeexResamplerState>(state);
    if (st == nullptr || input == nullptr || out == nullptr || inLen == nullptr || outLen == nullptr)
        return RESAMPLER_ERR_INVALID_ARG;
    if (env->GetArrayLength(inLen) < 1 || env->GetArrayLength(outLen) < 1)
        return RESAMPLER_ERR_INVALID_ARG;
    jint inCount = 0, outCount = 0;
    env->GetIntArrayRegion(inLen, 0, 1, &inCount);
    env->GetIntArrayRegion(outLen, 0, 1, &outCount);
    // The two counts arrive in their own int[] and say nothing about how long the sample arrays
    // are: speex reads inCount samples from input and writes up to outCount into out, so a count
    // larger than its array is an out-of-bounds access rather than an error code. A negative
    // count would become an enormous spx_uint32_t two lines further down.
    inCount = clampToArray(env, inCount, input);
    outCount = clampToArray(env, outCount, out);
    spx_uint32_t in = static_cast<spx_uint32_t>(inCount);
    spx_uint32_t outN = static_cast<spx_uint32_t>(outCount);
    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    if (inPtr == nullptr) return RESAMPLER_ERR_ALLOC_FAILED;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
        return RESAMPLER_ERR_ALLOC_FAILED;
    }
    int result = speex_resampler_process_int(st, channelIndex, inPtr, &in, outPtr, &outN);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
    writeInt(env, inLen, static_cast<jint>(in));
    writeInt(env, outLen, static_cast<jint>(outN));
    return result;
}

JNIEXPORT void JNICALL RS(destroy)(JNIEnv*, jobject, jlong state) {
    auto* st = fromHandle<SpeexResamplerState>(state);
    if (st != nullptr) speex_resampler_destroy(st);
}

// ---- jitter buffer ----

JNIEXPORT jlong JNICALL JB(init)(JNIEnv*, jobject, jint stepSize) {
    return toHandle(jitter_buffer_init(stepSize));
}

JNIEXPORT void JNICALL JB(destroy)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb != nullptr) jitter_buffer_destroy(jb);
}

JNIEXPORT void JNICALL JB(put)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jint timestamp, jint span, jint sequence, jint userData) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr || data == nullptr) return;
    // jitter_buffer_put copies packet.len bytes out of packet.data, and len is the caller's own
    // number, not the array's.
    len = clampToArray(env, len, data);
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    if (dataPtr == nullptr) return;
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(dataPtr);
    packet.len = static_cast<spx_uint32_t>(len);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);
    jitter_buffer_put(jb, &packet); // copies the payload
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT jint JNICALL JB(get)(JNIEnv* env, jobject, jlong handle, jbyteArray out, jint desiredSpan, jintArray meta) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    // meta receives five values below; a shorter array would be written past its end.
    if (jb == nullptr || out == nullptr || meta == nullptr || env->GetArrayLength(meta) < 5)
        return JITTER_BUFFER_BAD_ARGUMENT;
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    if (outPtr == nullptr) return JITTER_BUFFER_INTERNAL_ERROR;
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(outPtr);
    packet.len = static_cast<spx_uint32_t>(env->GetArrayLength(out));
    packet.timestamp = 0;
    packet.span = 0;
    packet.sequence = 0;
    packet.user_data = 0;
    int status = jitter_buffer_get(jb, &packet, desiredSpan, nullptr);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    jint values[5] = {
        static_cast<jint>(packet.len), static_cast<jint>(packet.timestamp), static_cast<jint>(packet.span),
        static_cast<jint>(packet.sequence), static_cast<jint>(packet.user_data)
    };
    env->SetIntArrayRegion(meta, 0, 5, values);
    return status;
}

JNIEXPORT jint JNICALL JB(pointerTimestamp)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    return jb != nullptr ? jitter_buffer_get_pointer_timestamp(jb) : 0;
}

JNIEXPORT void JNICALL JB(tick)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb != nullptr) jitter_buffer_tick(jb);
}

JNIEXPORT jint JNICALL JB(ctl)(JNIEnv* env, jobject, jlong handle, jint request, jintArray value) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr || value == nullptr || env->GetArrayLength(value) < 1)
        return JITTER_BUFFER_BAD_ARGUMENT;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = jitter_buffer_ctl(jb, request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL JB(updateDelay)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr) return JITTER_BUFFER_BAD_ARGUMENT;
    // The packet and start_offset arguments are unused by libspeexdsp's implementation.
    return jitter_buffer_update_delay(jb, nullptr, nullptr);
}

// ---- preprocessor ----

// Returns 0 on failure, which the Kotlin wrapper turns into a disabled preprocessor. A
// non-positive frame size is rejected here rather than passed on: speex would accept it and then
// run with a frame size PP(run) could never satisfy.
JNIEXPORT jlong JNICALL PP(init)(JNIEnv*, jobject, jint frameSize, jint sampleRate) {
    if (frameSize <= 0) return 0;
    SpeexPreprocessState* state = speex_preprocess_state_init(frameSize, sampleRate);
    if (state == nullptr) return 0;
    auto* h = new (std::nothrow) PreprocessHandle{state, frameSize};
    if (h == nullptr) {
        speex_preprocess_state_destroy(state);
        return 0;
    }
    return toHandle(h);
}

// Returns the speex VAD decision (1 = speech, 0 = not), or -1 when the frame cannot be processed
// at all. The length check is the whole point: speex_preprocess_run writes frameSize samples into
// whatever pointer it is given, so a shorter array is an overrun on every frame -- the original
// bug this file exists to prevent.
JNIEXPORT jint JNICALL PP(run)(JNIEnv* env, jobject, jlong state, jshortArray frame) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr || frame == nullptr) return -1;
    if (env->GetArrayLength(frame) < h->frameSize) return -1;
    jshort* ptr = env->GetShortArrayElements(frame, nullptr);
    if (ptr == nullptr) return -1;
    int result = speex_preprocess_run(h->state, ptr);
    env->ReleaseShortArrayElements(frame, ptr, 0);
    return result;
}

JNIEXPORT jint JNICALL PP(ctlInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr || value == nullptr || env->GetArrayLength(value) < 1) return -1;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = speex_preprocess_ctl(h->state, request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL PP(destroy)(JNIEnv*, jobject, jlong state) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr) return;
    speex_preprocess_state_destroy(h->state);
    delete h;
}

} // extern "C"
