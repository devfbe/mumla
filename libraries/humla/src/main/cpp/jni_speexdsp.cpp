#include <jni.h>
#include <speex/speex_jitter.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include "jni_handle.h"

#define RS(name) Java_se_lublin_humla_audio_native_SpeexResamplerNative_##name
#define JB(name) Java_se_lublin_humla_audio_native_SpeexJitterNative_##name
#define PP(name) Java_se_lublin_humla_audio_native_SpeexPreprocessNative_##name

extern "C" {

// ---- resampler ----

JNIEXPORT jlong JNICALL RS(init)(JNIEnv* env, jobject, jint channels, jint inRate, jint outRate, jint quality, jintArray error) {
    int err = 0;
    SpeexResamplerState* st = speex_resampler_init(channels, inRate, outRate, quality, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL RS(processInt)(JNIEnv* env, jobject, jlong state, jint channelIndex, jshortArray input, jintArray inLen, jshortArray out, jintArray outLen) {
    jint inCount = 0, outCount = 0;
    env->GetIntArrayRegion(inLen, 0, 1, &inCount);
    env->GetIntArrayRegion(outLen, 0, 1, &outCount);
    spx_uint32_t in = static_cast<spx_uint32_t>(inCount);
    spx_uint32_t outN = static_cast<spx_uint32_t>(outCount);
    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = speex_resampler_process_int(fromHandle<SpeexResamplerState>(state), channelIndex, inPtr, &in, outPtr, &outN);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
    writeInt(env, inLen, static_cast<jint>(in));
    writeInt(env, outLen, static_cast<jint>(outN));
    return result;
}

JNIEXPORT void JNICALL RS(destroy)(JNIEnv*, jobject, jlong state) {
    speex_resampler_destroy(fromHandle<SpeexResamplerState>(state));
}

// ---- jitter buffer ----

JNIEXPORT jlong JNICALL JB(init)(JNIEnv*, jobject, jint stepSize) {
    return toHandle(jitter_buffer_init(stepSize));
}

JNIEXPORT void JNICALL JB(destroy)(JNIEnv*, jobject, jlong handle) {
    jitter_buffer_destroy(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT void JNICALL JB(put)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jint timestamp, jint span, jint sequence, jint userData) {
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(dataPtr);
    packet.len = static_cast<spx_uint32_t>(len);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);
    jitter_buffer_put(fromHandle<JitterBuffer>(handle), &packet); // copies the payload
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT jint JNICALL JB(get)(JNIEnv* env, jobject, jlong handle, jbyteArray out, jint desiredSpan, jintArray meta) {
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(outPtr);
    packet.len = static_cast<spx_uint32_t>(env->GetArrayLength(out));
    packet.timestamp = 0;
    packet.span = 0;
    packet.sequence = 0;
    packet.user_data = 0;
    int status = jitter_buffer_get(fromHandle<JitterBuffer>(handle), &packet, desiredSpan, nullptr);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    jint values[5] = {
        static_cast<jint>(packet.len), static_cast<jint>(packet.timestamp), static_cast<jint>(packet.span),
        static_cast<jint>(packet.sequence), static_cast<jint>(packet.user_data)
    };
    env->SetIntArrayRegion(meta, 0, 5, values);
    return status;
}

JNIEXPORT jint JNICALL JB(pointerTimestamp)(JNIEnv*, jobject, jlong handle) {
    return jitter_buffer_get_pointer_timestamp(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT void JNICALL JB(tick)(JNIEnv*, jobject, jlong handle) {
    jitter_buffer_tick(fromHandle<JitterBuffer>(handle));
}

JNIEXPORT jint JNICALL JB(ctl)(JNIEnv* env, jobject, jlong handle, jint request, jintArray value) {
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = jitter_buffer_ctl(fromHandle<JitterBuffer>(handle), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL JB(updateDelay)(JNIEnv*, jobject, jlong handle) {
    // The packet and start_offset arguments are unused by libspeexdsp's implementation.
    return jitter_buffer_update_delay(fromHandle<JitterBuffer>(handle), nullptr, nullptr);
}

// ---- preprocessor ----

JNIEXPORT jlong JNICALL PP(init)(JNIEnv*, jobject, jint frameSize, jint sampleRate) {
    return toHandle(speex_preprocess_state_init(frameSize, sampleRate));
}

JNIEXPORT jint JNICALL PP(run)(JNIEnv* env, jobject, jlong state, jshortArray frame) {
    jshort* ptr = env->GetShortArrayElements(frame, nullptr);
    int result = speex_preprocess_run(fromHandle<SpeexPreprocessState>(state), ptr);
    env->ReleaseShortArrayElements(frame, ptr, 0);
    return result;
}

JNIEXPORT jint JNICALL PP(ctlInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = speex_preprocess_ctl(fromHandle<SpeexPreprocessState>(state), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL PP(destroy)(JNIEnv*, jobject, jlong state) {
    speex_preprocess_state_destroy(fromHandle<SpeexPreprocessState>(state));
}

} // extern "C"
