#include <jni.h>
extern "C" {
#include <celt.h>
#include <celt_types.h>
}
#include "jni_handle.h"

#define C11(name) Java_se_lublin_humla_audio_native_Celt11Native_##name

extern "C" {

JNIEXPORT jlong JNICALL C11(encoderCreate)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    CELTEncoder* st = celt_encoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C11(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = celt_encode(fromHandle<CELTEncoder>(state), pcmPtr, frameSize,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C11(encoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_encoder_destroy(fromHandle<CELTEncoder>(state));
}

JNIEXPORT jlong JNICALL C11(decoderCreate)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    CELTDecoder* st = celt_decoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C11(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = celt_decode_float(fromHandle<CELTDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr, frameSize);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL C11(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out, jint frameSize) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = celt_decode(fromHandle<CELTDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr, frameSize);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C11(decoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_decoder_destroy(fromHandle<CELTDecoder>(state));
}

} // extern "C"
