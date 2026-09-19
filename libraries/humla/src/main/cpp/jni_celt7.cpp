#include <jni.h>
extern "C" {
#include <celt.h>
#include <celt_types.h>
}
#include "jni_handle.h"

#define C7(name) Java_se_lublin_humla_audio_native_Celt7Native_##name

extern "C" {

JNIEXPORT jlong JNICALL C7(modeCreate)(JNIEnv* env, jobject, jint sampleRate, jint frameSize, jintArray error) {
    int err = 0;
    CELTMode* mode = celt_mode_create(sampleRate, frameSize, &err);
    writeInt(env, error, err);
    return toHandle(mode);
}

JNIEXPORT jint JNICALL C7(modeInfo)(JNIEnv* env, jobject, jlong mode, jint request, jintArray value) {
    celt_int32 v = 0;
    int result = celt_mode_info(fromHandle<const CELTMode>(mode), request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL C7(modeDestroy)(JNIEnv*, jobject, jlong mode) {
    celt_mode_destroy(fromHandle<CELTMode>(mode));
}

JNIEXPORT jlong JNICALL C7(encoderCreate)(JNIEnv* env, jobject, jlong mode, jint channels, jintArray error) {
    int err = 0;
    CELTEncoder* st = celt_encoder_create(fromHandle<const CELTMode>(mode), channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C7(encoderCtlInt)(JNIEnv*, jobject, jlong state, jint request, jint value) {
    return celt_encoder_ctl(fromHandle<CELTEncoder>(state), request, static_cast<int>(value));
}

JNIEXPORT jint JNICALL C7(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jbyteArray out, jint maxBytes) {
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    int result = celt_encode(fromHandle<CELTEncoder>(state), pcmPtr, nullptr,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C7(encoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_encoder_destroy(fromHandle<CELTEncoder>(state));
}

JNIEXPORT jlong JNICALL C7(decoderCreate)(JNIEnv* env, jobject, jlong mode, jint channels, jintArray error) {
    int err = 0;
    CELTDecoder* st = celt_decoder_create(fromHandle<const CELTMode>(mode), channels, &err);
    writeInt(env, error, err);
    return toHandle(st);
}

JNIEXPORT jint JNICALL C7(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    int result = celt_decode_float(fromHandle<CELTDecoder>(state),
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL C7(decodeShort)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jshortArray out) {
    jbyte* dataPtr = data != nullptr ? env->GetByteArrayElements(data, nullptr) : nullptr;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    int result = celt_decode(fromHandle<CELTDecoder>(state),
                             dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                             dataPtr != nullptr ? len : 0, outPtr);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL C7(decoderDestroy)(JNIEnv*, jobject, jlong state) {
    celt_decoder_destroy(fromHandle<CELTDecoder>(state));
}

} // extern "C"
