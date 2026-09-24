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
#include "jni_bridges.h"
#include "jni_common.h"
#include "jni_native_handle.h"

namespace {

struct EncoderHandle {
    OpusEncoder* state;
    int channels;
};

struct DecoderHandle {
    OpusDecoder* state;
    int channels;
};

humla::HandleTable& encoders() { return humla::handleTable<EncoderHandle>(); }
humla::HandleTable& decoders() { return humla::handleTable<DecoderHandle>(); }

using humla::writeInt;

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


jlong encoderCreate(JNIEnv* env, jobject, jint sampleRate, jint channels, jint application, jintArray error) noexcept {
    int err = 0;
    OpusEncoder* st = opus_encoder_create(sampleRate, channels, application, &err);
    writeInt(env, error, err);
    if (st == nullptr) return 0;
    auto* h = new (std::nothrow) EncoderHandle{st, channels};
    jlong handle = h != nullptr ? encoders().add(h) : 0;
    if (handle == 0) {
        delete h;
        opus_encoder_destroy(st);
        writeInt(env, error, OPUS_ALLOC_FAIL);
    }
    return handle;
}

jint encoderEncode(JNIEnv* env, jobject, jlong state, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) noexcept {
    auto* h = static_cast<EncoderHandle*>(encoders().get(state));
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

jint encoderCtlSetInt(JNIEnv*, jobject, jlong state, jint request, jint value) noexcept {
    auto* h = static_cast<EncoderHandle*>(encoders().get(state));
    if (h == nullptr || !setIntRequestAllowed(request)) return OPUS_BAD_ARG;
    return opus_encoder_ctl(h->state, request, static_cast<opus_int32>(value));
}

jint encoderCtlGetInt(JNIEnv* env, jobject, jlong state, jint request, jintArray value) noexcept {
    auto* h = static_cast<EncoderHandle*>(encoders().get(state));
    if (h == nullptr || value == nullptr || env->GetArrayLength(value) < 1) return OPUS_BAD_ARG;
    if (!getIntRequestAllowed(request)) return OPUS_BAD_ARG;
    opus_int32 v = 0;
    int result = opus_encoder_ctl(h->state, request, &v);
    writeInt(env, value, v);
    return result;
}

void encoderDestroy(JNIEnv*, jobject, jlong state) noexcept {
    auto* h = static_cast<EncoderHandle*>(encoders().release(state));
    if (h == nullptr) return;
    opus_encoder_destroy(h->state);
    delete h;
}

jlong decoderCreate(JNIEnv* env, jobject, jint sampleRate, jint channels, jintArray error) noexcept {
    int err = 0;
    OpusDecoder* st = opus_decoder_create(sampleRate, channels, &err);
    writeInt(env, error, err);
    if (st == nullptr) return 0;
    auto* h = new (std::nothrow) DecoderHandle{st, channels};
    jlong handle = h != nullptr ? decoders().add(h) : 0;
    if (handle == 0) {
        delete h;
        opus_decoder_destroy(st);
        writeInt(env, error, OPUS_ALLOC_FAIL);
    }
    return handle;
}

jint decoderDecodeFloat(JNIEnv* env, jobject, jlong state, jbyteArray data, jint len, jfloatArray out, jint frameSize, jint decodeFec) noexcept {
    auto* h = static_cast<DecoderHandle*>(decoders().get(state));
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

void decoderDestroy(JNIEnv*, jobject, jlong state) noexcept {
    auto* h = static_cast<DecoderHandle*>(decoders().release(state));
    if (h == nullptr) return;
    opus_decoder_destroy(h->state);
    delete h;
}

jint packetGetNbFrames(JNIEnv* env, jobject, jbyteArray packet, jint len) noexcept {
    if (packet == nullptr || len <= 0 || len > env->GetArrayLength(packet)) return OPUS_BAD_ARG;
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    if (ptr == nullptr) return OPUS_ALLOC_FAIL;
    int result = opus_packet_get_nb_frames(reinterpret_cast<const unsigned char*>(ptr), len);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

jint packetGetSamplesPerFrame(JNIEnv* env, jobject, jbyteArray packet, jint sampleRate) noexcept {
    if (packet == nullptr || env->GetArrayLength(packet) < 1) return OPUS_BAD_ARG;
    jbyte* ptr = env->GetByteArrayElements(packet, nullptr);
    if (ptr == nullptr) return OPUS_ALLOC_FAIL;
    int result = opus_packet_get_samples_per_frame(reinterpret_cast<const unsigned char*>(ptr), sampleRate);
    env->ReleaseByteArrayElements(packet, ptr, JNI_ABORT);
    return result;
}

}  // namespace

bool humla::registerOpusNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 5> encoder = {
        humla::nativeMethod("create", encoderCreate),
        humla::nativeMethod("encode", encoderEncode),
        humla::nativeMethod("ctlSetInt", encoderCtlSetInt),
        humla::nativeMethod("ctlGetInt", encoderCtlGetInt),
        humla::nativeMethod("destroy", encoderDestroy),
    };
    const std::array<JNINativeMethod, 5> decoder = {
        humla::nativeMethod("create", decoderCreate),
        humla::nativeMethod("decodeFloat", decoderDecodeFloat),
        humla::nativeMethod("destroy", decoderDestroy),
        humla::nativeMethod("packetGetNbFrames", packetGetNbFrames),
        humla::nativeMethod("packetGetSamplesPerFrame", packetGetSamplesPerFrame),
    };
    return humla::registerNatives(env, "se/lublin/humla/audio/native/OpusEncoderNative", encoder) &&
           humla::registerNatives(env, "se/lublin/humla/audio/native/OpusDecoderNative", decoder);
}
