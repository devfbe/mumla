#include <jni.h>
#include <opus.h>
#include "jni_handle.h"

#define ENC(name) Java_se_lublin_humla_audio_native_OpusEncoderNative_##name
#define DEC(name) Java_se_lublin_humla_audio_native_OpusDecoderNative_##name

extern "C" {

JNIEXPORT jlong JNICALL ENC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jint application, jintArray error) {
    int err = 0;
    OpusEncoder* st = opus_encoder_create(sampleRate, channels, application, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL ENC(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = opus_encode(fromHandle<OpusEncoder>(state), pcmPtr, frameSize,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL ENC(ctlSetInt)(JNIEnv*, jobject, jlong state, jint request, jint value) {
    return opus_encoder_ctl(fromHandle<OpusEncoder>(state), request, static_cast<opus_int32>(value));
}

JNIEXPORT jint JNICALL ENC(ctlGetInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    opus_int32 v = 0;
    int result = opus_encoder_ctl(fromHandle<OpusEncoder>(state), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL ENC(destroy)(JNIEnv*, jobject, jlong state) {
    opus_encoder_destroy(fromHandle<OpusEncoder>(state));
}

JNIEXPORT jlong JNICALL DEC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    OpusDecoder* st = opus_decoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL DEC(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize, jint decodeFec) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = opus_decode_float(fromHandle<OpusDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr, frameSize, decodeFec);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL DEC(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out, jint frameSize, jint decodeFec) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = opus_decode(fromHandle<OpusDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr, frameSize, decodeFec);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL DEC(destroy)(JNIEnv*, jobject, jlong state) {
    opus_decoder_destroy(fromHandle<OpusDecoder>(state));
}

JNIEXPORT jint JNICALL DEC(packetGetNbFrames)(JNIEnv* env, jobject, jbyteArray packet, jint len) {
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    int result = opus_packet_get_nb_frames(reinterpret_cast<const unsigned char*>(ptr), len);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL DEC(packetGetSamplesPerFrame)(JNIEnv* env, jobject, jbyteArray packet, jint sampleRate) {
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    int result = opus_packet_get_samples_per_frame(reinterpret_cast<const unsigned char*>(ptr), sampleRate);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

} // extern "C"
