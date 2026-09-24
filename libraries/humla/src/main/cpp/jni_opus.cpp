/*
 * JNI bridge for libopus.
 *
 * libopus takes sample and byte counts separately from the buffers they apply to, so every entry
 * point compares them against the Java array's real length before handing a pointer over. The
 * channel count is kept beside each state because libopus cannot be asked for it afterwards and
 * frame sizes are per channel.
 */
#include <jni.h>
#include <new>
#include <opus.h>
#include "jni_handle.h"

#define ENC(name) Java_se_lublin_humla_audio_native_OpusEncoderNative_##name
#define DEC(name) Java_se_lublin_humla_audio_native_OpusDecoderNative_##name

namespace {

struct EncoderHandle {
    OpusEncoder* state;
    int channels;
};

struct DecoderHandle {
    OpusDecoder* state;
    int channels;
};

void writeError(JNIEnv* env, jintArray error, jint value) {
    if (error != nullptr && env->GetArrayLength(error) >= 1) writeInt(env, error, value);
}

/* Requests whose single variadic argument is an opus_int32 passed by value. */
bool setIntRequestAllowed(jint request) {
    switch (request) {
        case OPUS_SET_BITRATE_REQUEST:
        case OPUS_SET_VBR_REQUEST:
        case OPUS_SET_COMPLEXITY_REQUEST:
        case OPUS_SET_INBAND_FEC_REQUEST:
        case OPUS_SET_PACKET_LOSS_PERC_REQUEST:
        case OPUS_SET_DTX_REQUEST:
        case OPUS_SET_SIGNAL_REQUEST:
            return true;
        default:
            return false;
    }
}

/* Requests whose single variadic argument is an opus_int32* written exactly once. */
bool getIntRequestAllowed(jint request) {
    switch (request) {
        case OPUS_GET_BITRATE_REQUEST:
        case OPUS_GET_VBR_REQUEST:
        case OPUS_GET_COMPLEXITY_REQUEST:
        case OPUS_GET_INBAND_FEC_REQUEST:
        case OPUS_GET_PACKET_LOSS_PERC_REQUEST:
        case OPUS_GET_DTX_REQUEST:
        case OPUS_GET_LOOKAHEAD_REQUEST:
            return true;
        default:
            return false;
    }
}

/* Validates an optional input packet: null means packet loss, otherwise len must fit the array. */
bool packetFits(JNIEnv* env, jbyteArray data, jint len) {
    if (data == nullptr) return true;
    return len >= 0 && len <= env->GetArrayLength(data);
}

/* The largest per-channel frame size the output array can hold, capped by the caller's request. */
jint clampFrameSize(JNIEnv* env, jarray out, jint frameSize, int channels) {
    jint capacity = static_cast<jint>(env->GetArrayLength(out)) / channels;
    return frameSize > capacity ? capacity : frameSize;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL ENC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jint application, jintArray error) {
    int err = 0;
    OpusEncoder* st = opus_encoder_create(sampleRate, channels, application, &err);
    writeError(env, error, err);
    if (st == nullptr) return 0;
    auto* h = new (std::nothrow) EncoderHandle{st, channels};
    if (h == nullptr) {
        opus_encoder_destroy(st);
        writeError(env, error, OPUS_ALLOC_FAIL);
        return 0;
    }
    return toHandle(h);
}

JNIEXPORT jint JNICALL ENC(encode)(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    auto* h = fromHandle<EncoderHandle>(state);
    if (h == nullptr || pcm == nullptr || out == nullptr || frameSize <= 0 || maxBytes <= 0)
        return OPUS_BAD_ARG;
    // opus_encode reads frameSize * channels samples; refusing a short array beats clamping it,
    // since a clamped frame size would no longer be a valid Opus frame duration anyway.
    if (static_cast<jlong>(frameSize) * h->channels > env->GetArrayLength(pcm)) return OPUS_BAD_ARG;
    jsize outCapacity = env->GetArrayLength(out);
    if (maxBytes > outCapacity) maxBytes = static_cast<jint>(outCapacity);
    if (maxBytes <= 0) return OPUS_BUFFER_TOO_SMALL;
    jshort* pcmPtr = env->GetShortArrayElements(pcm, nullptr);
    if (pcmPtr == nullptr) return OPUS_ALLOC_FAIL;
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }
    int result = opus_encode(h->state, pcmPtr, frameSize,
                             reinterpret_cast<unsigned char*>(outPtr), maxBytes);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(pcm, pcmPtr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL ENC(ctlSetInt)(JNIEnv*, jobject, jlong state, jint request, jint value) {
    auto* h = fromHandle<EncoderHandle>(state);
    if (h == nullptr || !setIntRequestAllowed(request)) return OPUS_BAD_ARG;
    return opus_encoder_ctl(h->state, request, static_cast<opus_int32>(value));
}

JNIEXPORT jint JNICALL ENC(ctlGetInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    auto* h = fromHandle<EncoderHandle>(state);
    if (h == nullptr || value == nullptr || env->GetArrayLength(value) < 1) return OPUS_BAD_ARG;
    if (!getIntRequestAllowed(request)) return OPUS_BAD_ARG;
    opus_int32 v = 0;
    int result = opus_encoder_ctl(h->state, request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL ENC(destroy)(JNIEnv*, jobject, jlong state) {
    auto* h = fromHandle<EncoderHandle>(state);
    if (h == nullptr) return;
    opus_encoder_destroy(h->state);
    delete h;
}

JNIEXPORT jlong JNICALL DEC(create)(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) {
    int err = 0;
    OpusDecoder* st = opus_decoder_create(sampleRate, channels, &err);
    writeError(env, error, err);
    if (st == nullptr) return 0;
    auto* h = new (std::nothrow) DecoderHandle{st, channels};
    if (h == nullptr) {
        opus_decoder_destroy(st);
        writeError(env, error, OPUS_ALLOC_FAIL);
        return 0;
    }
    return toHandle(h);
}

JNIEXPORT jint JNICALL DEC(decodeFloat)(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize, jint decodeFec) {
    auto* h = fromHandle<DecoderHandle>(state);
    if (h == nullptr || out == nullptr || frameSize <= 0 || !packetFits(env, data, len)) return OPUS_BAD_ARG;
    frameSize = clampFrameSize(env, out, frameSize, h->channels);
    if (frameSize <= 0) return OPUS_BUFFER_TOO_SMALL;
    jbyte* dataPtr = nullptr;
    if (data != nullptr) {
        dataPtr = env->GetByteArrayElements(data, nullptr);
        if (dataPtr == nullptr) return OPUS_ALLOC_FAIL;
    }
    jfloat* outPtr = env->GetFloatArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }
    int result = opus_decode_float(h->state,
                                   dataPtr != nullptr ? reinterpret_cast<const unsigned char*>(dataPtr) : nullptr,
                                   dataPtr != nullptr ? len : 0, outPtr, frameSize, decodeFec);
    env->ReleaseFloatArrayElements(out, outPtr, 0);
    if (dataPtr != nullptr) env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL DEC(destroy)(JNIEnv*, jobject, jlong state) {
    auto* h = fromHandle<DecoderHandle>(state);
    if (h == nullptr) return;
    opus_decoder_destroy(h->state);
    delete h;
}

JNIEXPORT jint JNICALL DEC(packetGetNbFrames)(JNIEnv* env, jobject, jbyteArray packet, jint len) {
    if (packet == nullptr || len <= 0 || len > env->GetArrayLength(packet)) return OPUS_BAD_ARG;
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    if (ptr == nullptr) return OPUS_ALLOC_FAIL;
    int result = opus_packet_get_nb_frames(reinterpret_cast<const unsigned char*>(ptr), len);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL DEC(packetGetSamplesPerFrame)(JNIEnv* env, jobject, jbyteArray packet, jint sampleRate) {
    if (packet == nullptr || env->GetArrayLength(packet) < 1) return OPUS_BAD_ARG;
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    if (ptr == nullptr) return OPUS_ALLOC_FAIL;
    int result = opus_packet_get_samples_per_frame(reinterpret_cast<const unsigned char*>(ptr), sampleRate);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

} // extern "C"
