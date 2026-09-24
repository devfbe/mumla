/* Host test for ../jni_opus.cpp, driven through the stand-in JNIEnv in jni_env_stub.h.
 *
 * libopus takes frame sizes, packet lengths and output capacities as numbers separate from the
 * buffers. The bridge must compare each against the Java array it came with; in the sanitized
 * build an overrun here is a trapped heap-buffer-overflow.
 */
#include "jni_env_stub.h"

#include <jni.h>
#include <opus.h>

#include <cstdio>

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

#define ENC(name) Java_se_lublin_humla_audio_native_OpusEncoderNative_##name
#define DEC(name) Java_se_lublin_humla_audio_native_OpusDecoderNative_##name

extern "C" {
JNIEXPORT jlong JNICALL ENC(create)(JNIEnv*, jobject, jint, jint, jint, jintArray);
JNIEXPORT jint JNICALL ENC(encode)(JNIEnv*, jobject, jlong, jshortArray, jint, jbyteArray, jint);
JNIEXPORT jint JNICALL ENC(ctlSetInt)(JNIEnv*, jobject, jlong, jint, jint);
JNIEXPORT jint JNICALL ENC(ctlGetInt)(JNIEnv*, jobject, jlong, jint, jintArray);
JNIEXPORT void JNICALL ENC(destroy)(JNIEnv*, jobject, jlong);
JNIEXPORT jlong JNICALL DEC(create)(JNIEnv*, jobject, jint, jint, jintArray);
JNIEXPORT jint JNICALL DEC(decodeFloat)(JNIEnv*, jobject, jlong, jbyteArray, jint, jfloatArray, jint, jint);
JNIEXPORT void JNICALL DEC(destroy)(JNIEnv*, jobject, jlong);
JNIEXPORT jint JNICALL DEC(packetGetNbFrames)(JNIEnv*, jobject, jbyteArray, jint);
JNIEXPORT jint JNICALL DEC(packetGetSamplesPerFrame)(JNIEnv*, jobject, jbyteArray, jint);
}

enum { kRate = 48000, kFrame = 480 };

/* Encodes one frame of a tone and returns the packet length, or a negative opus error. */
static int encode_frame(JNIEnv* e, jlong enc, Array<jbyte>& packet) {
    Array<jshort> pcm(kFrame);
    for (jsize i = 0; i < kFrame; i++) pcm[i] = jshort((i % 48) * 400 - 9600);
    return ENC(encode)(e, nullptr, enc, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(),
                       packet.length());
}

static void test_encoder(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC(create)(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    CHECK(enc != 0 && err[0] == OPUS_OK, "encoder create succeeds");
    if (enc == 0) return;

    {
        Array<jbyte> packet(512);
        CHECK(encode_frame(e, enc, packet) > 0, "an honest frame encodes");
        CHECK(jnistub::outstanding_copies() == 0, "encode releases its copies");
    }
    {
        Array<jshort> pcm(kFrame - 1);
        Array<jbyte> packet(512);
        CHECK(ENC(encode)(e, nullptr, enc, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG,
              "a pcm array shorter than the frame is refused, not over-read");
        CHECK(jnistub::outstanding_copies() == 0, "a refused frame is never pinned");
    }
    {
        Array<jshort> pcm(kFrame);
        Array<jbyte> packet(8);
        int result = ENC(encode)(e, nullptr, enc, pcm.as<jshortArray>(), kFrame,
                                 packet.as<jbyteArray>(), 4000);
        CHECK(result <= 8, "maxBytes is clamped to the output array");
    }
    {
        Array<jshort> pcm(kFrame);
        Array<jbyte> packet(512);
        CHECK(ENC(encode)(e, nullptr, enc, pcm.as<jshortArray>(), 0, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG, "a zero frame size is refused");
        CHECK(ENC(encode)(e, nullptr, 0, pcm.as<jshortArray>(), kFrame, packet.as<jbyteArray>(), 512)
                  == OPUS_BAD_ARG, "a null handle is refused");
    }

    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, 40000) == OPUS_OK, "set bitrate");
    Array<jint> value(1);
    CHECK(ENC(ctlGetInt)(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, value.as<jintArray>()) == OPUS_OK
              && value[0] == 40000, "get bitrate reads back what was set");
    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, 1234) == OPUS_BAD_ARG,
          "a GET request through the by-value setter is refused");
    CHECK(ENC(ctlGetInt)(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, value.as<jintArray>()) == OPUS_BAD_ARG,
          "a SET request through the pointer getter is refused");
    Array<jint> empty(0);
    CHECK(ENC(ctlGetInt)(e, nullptr, enc, OPUS_GET_BITRATE_REQUEST, empty.as<jintArray>()) == OPUS_BAD_ARG,
          "an empty value array is refused");

    ENC(destroy)(e, nullptr, enc);
    ENC(destroy)(e, nullptr, 0);
}

static void test_decoder(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC(create)(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    jlong dec = DEC(create)(e, nullptr, kRate, 1, err.as<jintArray>());
    CHECK(enc != 0 && dec != 0, "codec create succeeds");
    if (enc == 0 || dec == 0) return;

    Array<jbyte> packet(512);
    int len = encode_frame(e, enc, packet);
    CHECK(len > 0, "a packet to decode");

    {
        Array<jfloat> out(kFrame);
        CHECK(DEC(decodeFloat)(e, nullptr, dec, packet.as<jbyteArray>(), len, out.as<jfloatArray>(), kFrame, 0)
                  == kFrame, "float decode of an honest packet");
        CHECK(jnistub::outstanding_copies() == 0, "float decode releases its copies");
    }
    {
        /* The frame size claims more room than the array has: clamped, so opus reports the
         * buffer as too small instead of writing past the array. */
        Array<jfloat> out(kFrame / 2);
        int result = DEC(decodeFloat)(e, nullptr, dec, packet.as<jbyteArray>(), len,
                                      out.as<jfloatArray>(), 5760, 0);
        CHECK(result == OPUS_BUFFER_TOO_SMALL, "an oversized frame size is clamped to the float array");
    }
    {
        Array<jfloat> out(kFrame);
        CHECK(DEC(decodeFloat)(e, nullptr, dec, packet.as<jbyteArray>(), packet.length() + 1,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "a packet length beyond the array is refused");
        CHECK(DEC(decodeFloat)(e, nullptr, dec, packet.as<jbyteArray>(), -1,
                               out.as<jfloatArray>(), kFrame, 0) == OPUS_BAD_ARG,
              "a negative packet length is refused");
        CHECK(DEC(decodeFloat)(e, nullptr, dec, nullptr, 0, out.as<jfloatArray>(), kFrame, 0) == kFrame,
              "a null packet is concealment");
        CHECK(jnistub::outstanding_copies() == 0, "refusals are never pinned");
    }
    {
        Array<jbyte> small(2);
        small[0] = packet[0];
        small[1] = packet[1];
        CHECK(DEC(packetGetNbFrames)(e, nullptr, small.as<jbyteArray>(), 100) == OPUS_BAD_ARG,
              "nb_frames refuses a length beyond the array");
        CHECK(DEC(packetGetNbFrames)(e, nullptr, packet.as<jbyteArray>(), len) == 1,
              "nb_frames of an honest packet");
        Array<jbyte> empty(0);
        CHECK(DEC(packetGetSamplesPerFrame)(e, nullptr, empty.as<jbyteArray>(), kRate) == OPUS_BAD_ARG,
              "samples_per_frame refuses an empty array");
        CHECK(DEC(packetGetSamplesPerFrame)(e, nullptr, packet.as<jbyteArray>(), kRate) == kFrame,
              "samples_per_frame of an honest packet");
    }

    DEC(destroy)(e, nullptr, dec);
    DEC(destroy)(e, nullptr, 0);
    ENC(destroy)(e, nullptr, enc);
}

/* In-band FEC requested through the bridge ends up as LBRR data in the packets, which the
 * decoder can then use to rebuild a lost frame from the packet after it. */
static void test_inband_fec(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong enc = ENC(create)(e, nullptr, kRate, 1, OPUS_APPLICATION_VOIP, err.as<jintArray>());
    jlong dec = DEC(create)(e, nullptr, kRate, 1, err.as<jintArray>());
    CHECK(enc != 0 && dec != 0, "codec create succeeds");
    if (enc == 0 || dec == 0) return;

    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_VBR_REQUEST, 0) == OPUS_OK, "set cbr");
    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_BITRATE_REQUEST, 40000) == OPUS_OK, "set bitrate");
    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_INBAND_FEC_REQUEST, 1) == OPUS_OK, "enable fec");
    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_PACKET_LOSS_PERC_REQUEST, 10) == OPUS_OK, "set loss");
    CHECK(ENC(ctlSetInt)(e, nullptr, enc, OPUS_SET_DTX_REQUEST, 0) == OPUS_OK, "disable dtx");
    Array<jint> value(1);
    CHECK(ENC(ctlGetInt)(e, nullptr, enc, OPUS_GET_INBAND_FEC_REQUEST, value.as<jintArray>()) == OPUS_OK
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
    CHECK(DEC(decodeFloat)(e, nullptr, dec, packet.as<jbyteArray>(), len, out.as<jfloatArray>(), kFrame, 1)
              == kFrame, "a lost frame decodes from the next packet's fec data");

    DEC(destroy)(e, nullptr, dec);
    ENC(destroy)(e, nullptr, enc);
}

int main() {
    Env env;
    test_encoder(env);
    test_decoder(env);
    test_inband_fec(env);
    CHECK(jnistub::outstanding_copies() == 0, "no array copy outstanding at exit");
    if (failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::puts("jni_opus: all checks passed");
    return 0;
}
