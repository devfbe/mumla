/* Host test for ../jni_opus.cpp, registered and driven through the stand-in JNIEnv in
 * jni_env_stub.h.
 *
 * libopus takes frame sizes, packet lengths and output capacities as numbers separate from the
 * buffers. The bridge must compare each against the Java array it came with; in the sanitized
 * build an overrun here is a trapped heap-buffer-overflow.
 */
#include "jni_env_stub.h"

#include <jni.h>
#include <opus.h>

#include <cstdio>

#include "jni_bridges.h"

using jnistub::Array;
using jnistub::Env;

static int failures = 0;
#define CHECK(cond, msg)                              \
    do {                                              \
        if (!(cond)) {                                \
            std::fprintf(stderr, "FAIL: %s\n", (msg));\
            failures++;                               \
        }                                             \
    } while (0)

#define ENC(name, type) (jnistub::native<type>("se/lublin/humla/audio/native/OpusEncoderNative", name))
#define DEC(name, type) (jnistub::native<type>("se/lublin/humla/audio/native/OpusDecoderNative", name))

#define ENC_CREATE ENC("create", jlong (*)(JNIEnv*, jobject, jint, jint, jint, jintArray) noexcept)
#define ENC_ENCODE ENC("encode", jint (*)(JNIEnv*, jobject, jlong, jshortArray, jint, jbyteArray, jint) noexcept)
#define ENC_CTL_SET ENC("ctlSetInt", jint (*)(JNIEnv*, jobject, jlong, jint, jint) noexcept)
#define ENC_CTL_GET ENC("ctlGetInt", jint (*)(JNIEnv*, jobject, jlong, jint, jintArray) noexcept)
#define ENC_DESTROY ENC("destroy", void (*)(JNIEnv*, jobject, jlong) noexcept)
#define DEC_CREATE DEC("create", jlong (*)(JNIEnv*, jobject, jint, jint, jintArray) noexcept)
#define DEC_DECODE_FLOAT \
    DEC("decodeFloat", jint (*)(JNIEnv*, jobject, jlong, jbyteArray, jint, jint, jfloatArray, jint, jint) noexcept)
#define DEC_DESTROY DEC("destroy", void (*)(JNIEnv*, jobject, jlong) noexcept)
#define DEC_NB_FRAMES DEC("packetGetNbFrames", jint (*)(JNIEnv*, jobject, jbyteArray, jint) noexcept)
#define DEC_SAMPLES_PER_FRAME DEC("packetGetSamplesPerFrame", jint (*)(JNIEnv*, jobject, jbyteArray, jint) noexcept)

enum { kRate = 48000, kFrame = 480 };

/* Encodes one frame of a tone and returns the packet length, or a negative opus error. */
static int encode_frame(JNIEnv* e, jlong enc, Array<jbyte>& packet) {
    Array<jshort> pcm(kFrame);
    for (jsize i = 0; i < kFrame; i++) pcm[i] = jshort((i % 48) * 400 - 9600);
    return ENC_ENCODE(e, nullptr, enc, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(),
                       packet.length());
}

static void test_encoder(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC_CREATE(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    CHECK(enc != 0 && err[0] == OPUS_OK, "encoder create succeeds");
    if (enc == 0) return;

    {
        Array<jbyte> packet(512);
        CHECK(encode_frame(e, enc, packet) > 0, "an honest frame encodes");
    }
    {
        Array<jshort> pcm(kFrame - 1);
        Array<jbyte> packet(512);
        CHECK(ENC_ENCODE(e, nullptr, enc, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG,
              "a pcm array shorter than the frame is refused, not over-read");
    }
    {
        Array<jshort> pcm(kFrame);
        Array<jbyte> packet(8);
        int result = ENC_ENCODE(e, nullptr, enc, pcm.as<jshortArray>(), kFrame,
                                 packet.as<jbyteArray>(), 4000);
        CHECK(result <= 8, "maxBytes is clamped to the output array");
    }
    {
        Array<jshort> pcm(kFrame);
        Array<jbyte> packet(512);
        CHECK(ENC_ENCODE(e, nullptr, enc, pcm.as<jshortArray>(), 0, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG, "a zero frame size is refused");
        CHECK(ENC_ENCODE(e, nullptr, 0, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG, "a null handle is refused");
    }

    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, 40000) == OPUS_OK, "set bitrate");
    Array<jint> value(1);
    CHECK(ENC_CTL_GET(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, value.as<jintArray>()) == OPUS_OK
              && value[0] == 40000, "get bitrate reads back what was set");
    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, 1234) == OPUS_BAD_ARG,
          "a GET request through the by-value setter is refused");
    CHECK(ENC_CTL_GET(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, value.as<jintArray>()) == OPUS_BAD_ARG,
          "a SET request through the pointer getter is refused");
    Array<jint> empty(0);
    CHECK(ENC_CTL_GET(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, empty.as<jintArray>()) == OPUS_BAD_ARG,
          "an empty value array is refused");

    ENC_DESTROY(e, nullptr, enc);
    ENC_DESTROY(e, nullptr, 0);
}

static void test_decoder(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC_CREATE(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    jlong dec = DEC_CREATE(e, nullptr, kRate, 1, err.as<jintArray>());
    CHECK(enc != 0 && dec != 0, "codec create succeeds");
    if (enc == 0 || dec == 0) return;

    Array<jbyte> packet(512);
    int len = encode_frame(e, enc, packet);
    CHECK(len > 0, "a packet to decode");

    {
        Array<jfloat> out(kFrame);
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, len, out.as<jfloatArray>(), kFrame, 0)
                  == kFrame, "float decode of an honest packet");
    }
    {
        /* The same packet three bytes into a larger array decodes from its offset. */
        Array<jbyte> framed(len + 5);
        for (int i = 0; i < len; i++) framed[3 + i] = packet[i];
        Array<jfloat> direct(kFrame), offset(kFrame);
        /* Two fresh decoders, so both start from the same state. */
        jlong first = DEC_CREATE(e, nullptr, kRate, 1, err.as<jintArray>());
        jlong second = DEC_CREATE(e, nullptr, kRate, 1, err.as<jintArray>());
        CHECK(DEC_DECODE_FLOAT(e, nullptr, first, packet.as<jbyteArray>(), 0, len, direct.as<jfloatArray>(), kFrame, 0)
                  == kFrame, "decode at offset 0");
        CHECK(DEC_DECODE_FLOAT(e, nullptr, second, framed.as<jbyteArray>(), 3, len, offset.as<jfloatArray>(), kFrame, 0)
                  == kFrame, "decode at offset 3");
        bool same = true;
        for (int i = 0; i < kFrame; i++) same = same && direct[i] == offset[i];
        CHECK(same, "a packet decodes the same from an offset");
        DEC_DESTROY(e, nullptr, first);
        DEC_DESTROY(e, nullptr, second);
    }
    {
        /* Room for two frames, one decoded: only the decoded samples are written back. */
        Array<jfloat> out(2 * kFrame);
        for (jsize i = kFrame; i < out.length(); i++) out[i] = 42.0f;
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, len, out.as<jfloatArray>(),
                               2 * kFrame, 0) == kFrame, "float decode into a larger array");
        bool tail_intact = true;
        for (jsize i = kFrame; i < out.length(); i++)
            if (out[i] != 42.0f) tail_intact = false;
        CHECK(tail_intact, "decode leaves the output beyond the decoded samples untouched");
    }
    {
        /* The frame size claims more room than the array has: clamped, so opus reports the
         * buffer as too small instead of writing past the array. */
        Array<jfloat> out(kFrame / 2);
        int result = DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, len,
                                      out.as<jfloatArray>(), 5760, 0);
        CHECK(result == OPUS_BUFFER_TOO_SMALL, "an oversized frame size is clamped to the float array");
    }
    {
        Array<jfloat> out(kFrame);
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, packet.length() + 1,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "a packet length beyond the array is refused");
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, -1,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "a negative packet length is refused");
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), -1, len,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "a negative offset is refused");
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), packet.length() - len + 1, len,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "an offset that pushes the packet past the array is refused");
        CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, nullptr, 0, 0, out.as<jfloatArray>(), kFrame, 0) == kFrame,
              "a null packet is concealment");
    }
    {
        Array<jbyte> small(2);
        small[0] = packet[0];
        small[1] = packet[1];
        CHECK(DEC_NB_FRAMES(e, nullptr, small.as<jbyteArray>(), 100) == OPUS_BAD_ARG,
              "nb_frames refuses a length beyond the array");
        CHECK(DEC_NB_FRAMES(e, nullptr, packet.as<jbyteArray>(), len) == 1,
              "nb_frames of an honest packet");
        Array<jbyte> empty(0);
        CHECK(DEC_SAMPLES_PER_FRAME(e, nullptr, empty.as<jbyteArray>(), kRate) == OPUS_BAD_ARG,
              "samples_per_frame refuses an empty array");
        CHECK(DEC_SAMPLES_PER_FRAME(e, nullptr, packet.as<jbyteArray>(), kRate) == kFrame,
              "samples_per_frame of an honest packet");
    }

    DEC_DESTROY(e, nullptr, dec);
    DEC_DESTROY(e, nullptr, 0);
    ENC_DESTROY(e, nullptr, enc);
}

/* In-band FEC requested through the bridge ends up as LBRR data in the packets, which the
 * decoder can then use to rebuild a lost frame from the packet after it. */
static void test_inband_fec(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC_CREATE(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    jlong dec = DEC_CREATE(e, nullptr, kRate, 1, err.as<jintArray>());
    CHECK(enc != 0 && dec != 0, "codec create succeeds");
    if (enc == 0 || dec == 0) return;

    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_VBR_REQUEST, 0) == OPUS_OK, "set cbr");
    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, 40000) == OPUS_OK, "set bitrate");
    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_INBAND_FEC_REQUEST, 1) == OPUS_OK, "enable fec");
    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_PACKET_LOSS_PERC_REQUEST, 10) == OPUS_OK, "set loss");
    CHECK(ENC_CTL_SET(e, nullptr, enc, OPUS_SET_DTX_REQUEST, 0) == OPUS_OK, "disable dtx");
    Array<jint> value(1);
    CHECK(ENC_CTL_GET(e, nullptr, enc, OPUS_GET_INBAND_FEC_REQUEST, value.as<jintArray>()) == OPUS_OK
              && value[0] == 1, "fec reads back as enabled");

    int withLbrr = 0;
    Array<jbyte> packet(512);
    int len = 0;
    for (int i = 0; i < 50; i++) {
        len = encode_frame(e, enc, packet);
        CHECK(len > 0, "a frame encodes with fec on");
        if (len > 0 && opus_packet_has_lbrr(reinterpret_cast<const unsigned char*>(packet.data()), len) == 1)
            withLbrr++;
    }
    CHECK(withLbrr > 0, "packets carry lbrr data once fec is on");

    Array<jfloat> out(kFrame);
    CHECK(DEC_DECODE_FLOAT(e, nullptr, dec, packet.as<jbyteArray>(), 0, len, out.as<jfloatArray>(), kFrame, 1)
              == kFrame, "a lost frame decodes from the next packet's fec data");

    DEC_DESTROY(e, nullptr, dec);
    ENC_DESTROY(e, nullptr, enc);
}

int main() {
    Env env;
    CHECK(humla::registerOpusNatives(env.get()), "the opus bridge registers");
    test_encoder(env);
    test_decoder(env);
    test_inband_fec(env);
    if (failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::puts("jni_opus: all checks passed");
    return 0;
}
