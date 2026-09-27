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

/* Stack capacity of the per-call copies; larger requests fall back to the heap. 120 ms of mono
 * 48 kHz audio, and libopus's recommended maximum packet size. */
constexpr std::size_t kInlinePcm = 5760;
constexpr std::size_t kInlinePacket = 4000;

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

/* Validates an optional input packet: null means packet loss, otherwise offset + len must fit the
 * array. */
bool packetFits(JNIEnv* env, jbyteArray data, jint offset, jint len) {
    if (data == nullptr) return true;
    return offset >= 0 && len >= 0 && static_cast<jlong>(offset) + len <= env->GetArrayLength(data);
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
    jsize samples = frameSize * h->channels;
    humla::RegionBuffer<jshort, kInlinePcm> input(samples);
    humla::RegionBuffer<jbyte, kInlinePacket> packet(maxBytes);
    if (input.data() == nullptr || packet.data() == nullptr) return OPUS_ALLOC_FAIL;
    input.read(env, pcm, 0, samples);
    int result = opus_encode(h->state, input.data(), frameSize,
                             reinterpret_cast<unsigned char*>(packet.data()), maxBytes);
    if (result > 0) packet.write(env, out, 0, result);
    return result;
}

jint encoderCtlSetInt(JNIEnv*, jobject, jlong state, jint request, jint value) noexcept {
    auto* h = static_cast<EncoderHandle*>(encoders().get(state));
    if (h == nullptr || !setIntRequestAllowed(request)) return OPUS_BAD_ARG;
    return opus_encoder_ctl(h->state, request, static_cast<opus_int32>(value));
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

jint decoderDecodeFloat(JNIEnv* env, jobject, jlong state, jbyteArray data, jint offset, jint len,
                        jfloatArray out, jint frameSize, jint decodeFec) noexcept {
    auto* h = static_cast<DecoderHandle*>(decoders().get(state));
    if (h == nullptr || out == nullptr || frameSize <= 0 || !packetFits(env, data, offset, len)) return OPUS_BAD_ARG;
    frameSize = clampFrameSize(env, out, frameSize, h->channels);
    if (frameSize <= 0) return OPUS_BUFFER_TOO_SMALL;
    jsize packetBytes = data != nullptr ? len : 0;
    jsize samples = frameSize * h->channels;
    humla::RegionBuffer<jbyte, kInlinePacket> packet(packetBytes);
    humla::RegionBuffer<jfloat, kInlinePcm> pcm(samples);
    if (packet.data() == nullptr || pcm.data() == nullptr) return OPUS_ALLOC_FAIL;
    if (data != nullptr) packet.read(env, data, offset, packetBytes);
    int result = opus_decode_float(h->state,
                                   data != nullptr ? reinterpret_cast<const unsigned char*>(packet.data()) : nullptr,
                                   packetBytes, pcm.data(), frameSize, decodeFec);
    if (result > 0) pcm.write(env, out, 0, result * h->channels);
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
    humla::RegionBuffer<jbyte, kInlinePacket> bytes(len);
    if (bytes.data() == nullptr) return OPUS_ALLOC_FAIL;
    bytes.read(env, packet, 0, len);
    return opus_packet_get_nb_frames(reinterpret_cast<const unsigned char*>(bytes.data()), len);
}

jint packetGetSamplesPerFrame(JNIEnv* env, jobject, jbyteArray packet, jint sampleRate) noexcept {
    if (packet == nullptr || env->GetArrayLength(packet) < 1) return OPUS_BAD_ARG;
    // Only the TOC byte is read.
    jbyte toc = 0;
    env->GetByteArrayRegion(packet, 0, 1, &toc);
    return opus_packet_get_samples_per_frame(reinterpret_cast<const unsigned char*>(&toc), sampleRate);
}

}  // namespace

bool humla::registerOpusNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 4> encoder = {
        humla::nativeMethod("create", encoderCreate),
        humla::nativeMethod("encode", encoderEncode),
        humla::nativeMethod("ctlSetInt", encoderCtlSetInt),
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
