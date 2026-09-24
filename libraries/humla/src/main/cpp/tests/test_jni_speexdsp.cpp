/* Host test for ../jni_speexdsp.cpp, driven through the stand-in JNIEnv in jni_env_stub.h.
 *
 * libspeexdsp takes the length from somewhere other than the buffer:
 *
 *   - speex_preprocess_run writes the frame size the state was created with. Without a check,
 *     speex once wrote 640 samples into a 480-element Java array on every frame.
 *   - speex_resampler_process_int takes the input and output counts from a separate int[].
 *   - jitter_buffer_put takes the payload length as a separate argument.
 *
 * The bridge is the only place that sees both, so it has to check. The arrays here are exact-size
 * heap blocks, so in the sanitized build an overrun is a trapped heap-buffer-overflow.
 */
#include "jni_env_stub.h"

#include <jni.h>
#include <speex/speex_resampler.h>

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

extern "C" {
JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_SpeexPreprocessNative_init(JNIEnv*, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexPreprocessNative_run(JNIEnv*, jobject, jlong, jshortArray);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexPreprocessNative_destroy(JNIEnv*, jobject, jlong);

JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_SpeexResamplerNative_init(JNIEnv*, jobject, jint, jint, jint, jint, jintArray);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexResamplerNative_processInt(JNIEnv*, jobject, jlong, jint, jshortArray, jintArray, jshortArray, jintArray);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexResamplerNative_destroy(JNIEnv*, jobject, jlong);

JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexPreprocessNative_ctlInt(JNIEnv*, jobject, jlong, jint, jintArray);

JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_init(JNIEnv*, jobject, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_ctl(JNIEnv*, jobject, jlong, jint, jintArray);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_put(JNIEnv*, jobject, jlong, jbyteArray, jint, jint, jint, jint, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_get(JNIEnv*, jobject, jlong, jbyteArray, jint, jintArray);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_pointerTimestamp(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_tick(JNIEnv*, jobject, jlong);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_updateDelay(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_destroy(JNIEnv*, jobject, jlong);
}

#define PP_INIT Java_se_lublin_humla_audio_native_SpeexPreprocessNative_init
#define PP_RUN Java_se_lublin_humla_audio_native_SpeexPreprocessNative_run
#define PP_CTL Java_se_lublin_humla_audio_native_SpeexPreprocessNative_ctlInt
#define PP_DESTROY Java_se_lublin_humla_audio_native_SpeexPreprocessNative_destroy
#define RS_INIT Java_se_lublin_humla_audio_native_SpeexResamplerNative_init
#define RS_PROCESS Java_se_lublin_humla_audio_native_SpeexResamplerNative_processInt
#define RS_DESTROY Java_se_lublin_humla_audio_native_SpeexResamplerNative_destroy
#define JB_INIT Java_se_lublin_humla_audio_native_SpeexJitterNative_init
#define JB_PUT Java_se_lublin_humla_audio_native_SpeexJitterNative_put
#define JB_GET Java_se_lublin_humla_audio_native_SpeexJitterNative_get
#define JB_CTL Java_se_lublin_humla_audio_native_SpeexJitterNative_ctl
#define JB_DESTROY Java_se_lublin_humla_audio_native_SpeexJitterNative_destroy
#define JB_POINTER_TIMESTAMP Java_se_lublin_humla_audio_native_SpeexJitterNative_pointerTimestamp
#define JB_TICK Java_se_lublin_humla_audio_native_SpeexJitterNative_tick
#define JB_UPDATE_DELAY Java_se_lublin_humla_audio_native_SpeexJitterNative_updateDelay

enum { kJitterBadArgument = -2 };  /* JITTER_BUFFER_BAD_ARGUMENT */

/* A state created for 640-sample frames (ultra-wideband) handed a 480-element array. */
static void test_preprocessor(Env& env) {
    JNIEnv* e = env.get();
    enum { kWideband = 640, kNarrow = 480 };

    jlong state = PP_INIT(e, nullptr, kWideband, 48000);
    CHECK(state != 0, "speex preprocess init succeeds");
    if (state == 0) return;

    {
        Array<jshort> frame(kWideband);
        CHECK(PP_RUN(e, nullptr, state, frame.as<jshortArray>()) >= 0,
              "a frame of exactly the configured size is processed");
        CHECK(jnistub::outstanding_copies() == 0, "the array copy is released");
    }
    {
        Array<jshort> frame(kNarrow);
        CHECK(PP_RUN(e, nullptr, state, frame.as<jshortArray>()) < 0,
              "a 480-sample frame is refused by a 640-sample preprocessor, not overrun");
        CHECK(jnistub::outstanding_copies() == 0, "a refused frame is never pinned");
    }
    {
        /* Longer than configured is fine; speex only touches the first frameSize samples. */
        Array<jshort> frame(kWideband + 16);
        for (jsize i = kWideband; i < frame.length(); i++) frame[i] = 0x5a5a;
        CHECK(PP_RUN(e, nullptr, state, frame.as<jshortArray>()) >= 0, "an over-long frame is accepted");
        bool tail_intact = true;
        for (jsize i = kWideband; i < frame.length(); i++)
            if (frame[i] != jshort(0x5a5a)) tail_intact = false;
        CHECK(tail_intact, "speex does not write past the configured frame size");
    }

    Array<jshort> frame(kWideband);
    CHECK(PP_RUN(e, nullptr, 0, frame.as<jshortArray>()) < 0, "run with a null state reports an error");
    CHECK(PP_RUN(e, nullptr, state, nullptr) < 0, "run with a null frame reports an error");
    CHECK(PP_INIT(e, nullptr, 0, 48000) == 0, "a zero frame size is rejected at init");
    CHECK(PP_INIT(e, nullptr, -8, 48000) == 0, "a negative frame size is rejected at init");

    PP_DESTROY(e, nullptr, state);
    PP_DESTROY(e, nullptr, 0);  /* must not crash */
}

/* speex_resampler_process_int reads *in samples and writes up to *out samples. Both counts come
 * from the caller's int[], not from the arrays. */
static void test_resampler(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong st = RS_INIT(e, nullptr, 1, 48000, 16000, 3, err.as<jintArray>());
    CHECK(st != 0, "speex resampler init succeeds");
    CHECK(err[0] == 0, "speex resampler init reports no error");
    if (st == 0) return;

    {
        /* Honest call: 480 in, room for 160 out. */
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 480;
        outLen[0] = 160;
        CHECK(RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                         out.as<jshortArray>(), outLen.as<jintArray>()) == 0,
              "a correctly sized resample succeeds");
        CHECK(inLen[0] <= 480 && outLen[0] <= 160, "the counts written back stay within the arrays");
        CHECK(jnistub::outstanding_copies() == 0, "both array copies are released");
    }
    {
        /* Lying caller: counts far beyond both arrays. Clamped, not obeyed. */
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 48000;
        outLen[0] = 16000;
        RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                   out.as<jshortArray>(), outLen.as<jintArray>());
        CHECK(inLen[0] <= 480, "an input count larger than the input array is clamped to it");
        CHECK(outLen[0] <= 160, "an output count larger than the output array is clamped to it");
    }
    /* The lying call above cannot pin either clamp on its own: speex stops as soon as the first
     * budget runs out, so each clamp hides the other's overrun. The two cases below each lie about
     * exactly one count, so deleting either clamp alone fails both builds. */
    {
        /* Only the input clamp carries: out really can hold the 16000 samples asked for. */
        Array<jshort> in(480), out(16000);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 48000;
        outLen[0] = 16000;
        RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                   out.as<jshortArray>(), outLen.as<jintArray>());
        CHECK(inLen[0] <= 480, "an input count is clamped even when the output array has room");
        CHECK(jnistub::outstanding_copies() == 0, "both array copies are released");
    }
    {
        /* Only the output clamp carries: inLen is honest, outLen is 100x the output array. */
        Array<jshort> in(4800), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 4800;
        outLen[0] = 16000;
        RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                   out.as<jshortArray>(), outLen.as<jintArray>());
        CHECK(outLen[0] <= 160, "an output count is clamped even when the input count is honest");
        CHECK(jnistub::outstanding_copies() == 0, "both array copies are released");
    }
    {
        /* inLen and outLen may be empty; GetIntArrayRegion on an empty array throws on a real JVM
         * (the stub aborts). */
        Array<jshort> in(480), out(160);
        Array<jint> empty(0), outLen(1);
        outLen[0] = 160;
        CHECK(RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), empty.as<jintArray>(),
                         out.as<jshortArray>(), outLen.as<jintArray>()) != RESAMPLER_ERR_SUCCESS,
              "an empty input-count array is refused, not read");
        Array<jint> inLen2(1);
        inLen2[0] = 480;
        CHECK(RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen2.as<jintArray>(),
                         out.as<jshortArray>(), empty.as<jintArray>()) != RESAMPLER_ERR_SUCCESS,
              "an empty output-count array is refused, not read");
        CHECK(jnistub::outstanding_copies() == 0, "neither refusal pins an array");
    }
    {
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = -1;
        outLen[0] = -1;
        RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                   out.as<jshortArray>(), outLen.as<jintArray>());
        CHECK(inLen[0] >= 0 && outLen[0] >= 0, "negative counts do not become huge unsigned ones");
    }

    Array<jshort> in(480), out(160);
    Array<jint> inLen(1), outLen(1);
    CHECK(RS_PROCESS(e, nullptr, 0, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                     out.as<jshortArray>(), outLen.as<jintArray>()) != 0,
          "processInt with a null state reports an error");

    RS_DESTROY(e, nullptr, st);
    RS_DESTROY(e, nullptr, 0);
}

/* channelIndex is an index, not a count: speex_resampler_process_native uses it on three
 * per-channel arrays sized for the state's channel count and never range-checks it. Out of range
 * is a heap read and write, not an error code. */
static void test_resampler_channel_index(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);

    jlong mono = RS_INIT(e, nullptr, 1, 48000, 16000, 3, err.as<jintArray>());
    CHECK(mono != 0, "mono resampler init succeeds");
    if (mono == 0) return;
    {
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 480;
        outLen[0] = 160;
        jshortArray i = in.as<jshortArray>(), o = out.as<jshortArray>();
        jintArray il = inLen.as<jintArray>(), ol = outLen.as<jintArray>();
        CHECK(RS_PROCESS(e, nullptr, mono, 0, i, il, o, ol) == RESAMPLER_ERR_SUCCESS,
              "channel 0 of a one-channel resampler is processed");
        CHECK(RS_PROCESS(e, nullptr, mono, 1, i, il, o, ol) == RESAMPLER_ERR_INVALID_ARG,
              "a channel index equal to the channel count is refused");
        CHECK(RS_PROCESS(e, nullptr, mono, 7, i, il, o, ol) == RESAMPLER_ERR_INVALID_ARG,
              "a channel index far beyond the channel count is refused");
        CHECK(RS_PROCESS(e, nullptr, mono, -1, i, il, o, ol) == RESAMPLER_ERR_INVALID_ARG,
              "a negative channel index is refused");
        CHECK(jnistub::outstanding_copies() == 0, "a refused channel index never pins an array");
    }
    RS_DESTROY(e, nullptr, mono);

    /* The check is against the state's own channel count: a two-channel state has a channel 1. */
    jlong stereo = RS_INIT(e, nullptr, 2, 48000, 16000, 3, err.as<jintArray>());
    CHECK(stereo != 0, "stereo resampler init succeeds");
    if (stereo != 0) {
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        jshortArray i = in.as<jshortArray>(), o = out.as<jshortArray>();
        jintArray il = inLen.as<jintArray>(), ol = outLen.as<jintArray>();
        for (jint channel = 0; channel < 2; channel++) {
            inLen[0] = 480;
            outLen[0] = 160;
            CHECK(RS_PROCESS(e, nullptr, stereo, channel, i, il, o, ol) == RESAMPLER_ERR_SUCCESS,
                  "both channels of a two-channel resampler are processed");
        }
        inLen[0] = 480;
        outLen[0] = 160;
        CHECK(RS_PROCESS(e, nullptr, stereo, 2, i, il, o, ol) == RESAMPLER_ERR_INVALID_ARG,
              "channel 2 of a two-channel resampler is refused");
        RS_DESTROY(e, nullptr, stereo);
    }

    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, 0, 48000, 16000, 3, err.as<jintArray>()) == 0,
          "a zero channel count is rejected at init");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, -2, 48000, 16000, 3, err.as<jintArray>()) == 0,
          "a negative channel count is rejected at init");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
    /* The error array is optional on the Kotlin side (IntArray?) and may also be too short. */
    Array<jint> empty(0);
    CHECK(RS_INIT(e, nullptr, 0, 48000, 16000, 3, nullptr) == 0, "init tolerates a null error array");
    CHECK(RS_INIT(e, nullptr, 0, 48000, 16000, 3, empty.as<jintArray>()) == 0,
          "init tolerates an empty error array");
}

/* speex_resampler_init also returns NULL for a zero sample rate or an out-of-range quality, which
 * the channels <= 0 guard does not see. Wrapping that in a handle would give Kotlin a live handle
 * to a null state and the next processInt would crash in native code. The channel-index test
 * cannot pin this guard: its init cases make speex return NULL too. */
static void test_resampler_init_failure(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);

    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, 1, 0, 16000, 3, err.as<jintArray>()) == 0,
          "an input rate of 0 is refused rather than wrapped in a handle");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, 1, 48000, 0, 3, err.as<jintArray>()) == 0,
          "an output rate of 0 is refused rather than wrapped in a handle");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
    /* speex_resampler_init takes quality 0..10 only; the Kotlin side does not validate it. */
    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, 1, 48000, 16000, 99, err.as<jintArray>()) == 0,
          "a quality above 10 is refused rather than wrapped in a handle");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
    err[0] = 0;
    CHECK(RS_INIT(e, nullptr, 1, 48000, 16000, -1, err.as<jintArray>()) == 0,
          "a negative quality is refused rather than wrapped in a handle");
    CHECK(err[0] == RESAMPLER_ERR_INVALID_ARG, "and reported as an invalid argument");
}

/* jitter_buffer_put copies packet.len bytes out of packet.data. */
static void test_jitter(Env& env) {
    JNIEnv* e = env.get();
    jlong jb = JB_INIT(e, nullptr, 480);
    CHECK(jb != 0, "jitter buffer init succeeds");
    if (jb == 0) return;

    {
        Array<jbyte> payload(16);
        for (jsize i = 0; i < payload.length(); i++) payload[i] = jbyte(i);
        /* A length far beyond the array: clamped to the array, not read past it. */
        JB_PUT(e, nullptr, jb, payload.as<jbyteArray>(), 4096, 0, 480, 0, 0);
        CHECK(jnistub::outstanding_copies() == 0, "put releases the payload copy");

        Array<jbyte> out(4096);
        Array<jint> meta(5);
        JB_GET(e, nullptr, jb, out.as<jbyteArray>(), 480, meta.as<jintArray>());
        CHECK(meta[0] <= 16, "the packet that comes back is no longer than what was really put in");
    }

    JB_PUT(e, nullptr, jb, nullptr, 16, 0, 480, 0, 0);  /* a null payload must not crash */
    JB_PUT(e, nullptr, 0, nullptr, 0, 0, 480, 0, 0);    /* nor a null handle */
    {
        /* get() writes five ints into meta. The stub aborts on an out-of-range write, as a real
         * JVM would throw, so reaching the next line is the assertion. */
        Array<jbyte> out(64);
        Array<jint> meta(2);
        JB_GET(e, nullptr, jb, out.as<jbyteArray>(), 480, meta.as<jintArray>());
        CHECK(true, "get with a short meta array does not write past it");
        CHECK(jnistub::outstanding_copies() == 0, "get releases the output copy");
    }

    /* pointerTimestamp, tick and updateDelay all dereference the handle in libspeexdsp; 0 (a
     * failed init or an unassigned Kotlin field) must be answered, not followed. */
    CHECK(JB_POINTER_TIMESTAMP(e, nullptr, 0) == 0, "pointerTimestamp with a null handle answers 0");
    JB_TICK(e, nullptr, 0);
    CHECK(true, "tick with a null handle does not dereference it");
    CHECK(JB_UPDATE_DELAY(e, nullptr, 0) == kJitterBadArgument,
          "updateDelay with a null handle is refused");

    JB_DESTROY(e, nullptr, jb);
    JB_DESTROY(e, nullptr, 0);
}

/* Both ctl entry points hand the ctl function the address of a 4-byte stack spx_int32_t, with the
 * request number straight from Kotlin. Several requests do something else with that address (see
 * the allow list comment in ../jni_speexdsp.cpp): store it as a callback, write 8 bytes or
 * ps_size ints into it, read it as a float, or divide by it. These tests pin the refusals. */
static void test_jitter_ctl(Env& env) {
    JNIEnv* e = env.get();
    jlong jb = JB_INIT(e, nullptr, 480);
    CHECK(jb != 0, "jitter buffer init succeeds");
    if (jb == 0) return;
    Array<jint> value(1);
    jintArray v = value.as<jintArray>();

    value[0] = 20;
    CHECK(JB_CTL(e, nullptr, jb, 0 /* SET_MARGIN */, v) == 0, "SET_MARGIN is allowed");
    value[0] = 0;
    CHECK(JB_CTL(e, nullptr, jb, 1 /* GET_MARGIN */, v) == 0, "GET_MARGIN is allowed");
    CHECK(value[0] == 20, "GET_MARGIN reads back what SET_MARGIN wrote");
    value[0] = -7;
    CHECK(JB_CTL(e, nullptr, jb, 3 /* GET_AVAILABLE_COUNT */, v) == 0,
          "GET_AVAILABLE_COUNT is allowed");
    CHECK(value[0] >= 0, "GET_AVAILABLE_COUNT writes a count back");

    /* The handle and the array are checked before the request: an allowed request reaches the
     * jitter buffer and value[0]. */
    CHECK(JB_CTL(e, nullptr, 0, 0 /* SET_MARGIN */, v) == kJitterBadArgument,
          "ctl with a null handle is refused");
    CHECK(JB_CTL(e, nullptr, jb, 0 /* SET_MARGIN */, nullptr) == kJitterBadArgument,
          "ctl with a null value array is refused");
    {
        Array<jint> empty(0);
        CHECK(JB_CTL(e, nullptr, jb, 0 /* SET_MARGIN */, empty.as<jintArray>()) == kJitterBadArgument,
              "ctl with an empty value array is refused, not read");
    }

    /* Everything else, whether libspeexdsp knows it or not. 4 and 5 are the dangerous pair;
     * 12345/-1 are not requests. */
    const jint refused[] = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 2, 14, -1, 12345, 0x7fffffff};
    for (jint request : refused) {
        value[0] = 0x5a5a5a5a;
        CHECK(JB_CTL(e, nullptr, jb, request, v) == kJitterBadArgument,
              "a jitter request outside the allow list is refused");
        CHECK(value[0] == 0x5a5a5a5a, "a refused jitter request leaves the caller's value alone");
    }

    /* SET_DESTROY_CALLBACK stores ptr itself as the callback jitter_buffer_reset calls for every
     * queued packet. Reaching the line after the destroy below is the assertion. */
    {
        Array<jbyte> payload(16);
        JB_PUT(e, nullptr, jb, payload.as<jbyteArray>(), 16, 0, 480, 0, 0);
        JB_DESTROY(e, nullptr, jb);
        CHECK(true, "a buffer with a queued packet is destroyed without calling a stack address");
    }
}

static void test_preprocess_ctl(Env& env) {
    JNIEnv* e = env.get();
    enum { kFrame = 640 };
    jlong st = PP_INIT(e, nullptr, kFrame, 48000);
    CHECK(st != 0, "speex preprocess init succeeds");
    if (st == 0) return;
    Array<jint> value(1);
    jintArray v = value.as<jintArray>();

    /* The requests SpeexPreprocessNative names as constants and libspeexdsp implements here. */
    const jint implemented[] = {0, 4, 8, 14, 15, 18, 45};
    for (jint request : implemented) {
        value[0] = 1;
        CHECK(PP_CTL(e, nullptr, st, request, v) == 0, "a named preprocess request is allowed");
    }
    /* SET_AGC (2) and SET_AGC_TARGET (46) are allowed, but speex answers -1 to both: its AGC
     * block sits behind #ifndef FIXED_POINT and this library is built with FIXED_POINT. That -1
     * is libspeexdsp's, not the bridge's; only a native test can observe it. */
    const jint agc[] = {2, 46};
    for (jint request : agc) {
        value[0] = 1;
        CHECK(PP_CTL(e, nullptr, st, request, v) == -1,
              "the AGC requests are allowed through and answered by speex, which lacks them here");
    }
    value[0] = 42;
    CHECK(PP_CTL(e, nullptr, st, 14 /* SET_PROB_START */, v) == 0, "SET_PROB_START is allowed");
    value[0] = 0;
    CHECK(PP_CTL(e, nullptr, st, 15 /* GET_PROB_START */, v) == 0, "GET_PROB_START is allowed");
    /* Stored as Q15 and scaled back by 100, so the round trip is within a percentage point. */
    CHECK(value[0] >= 41 && value[0] <= 43, "GET_PROB_START reads back what SET_PROB_START wrote");

    /* Same three as JB(ctl): the state is dereferenced, and value[0] is read and written. */
    CHECK(PP_CTL(e, nullptr, 0, 0 /* SET_DENOISE */, v) == -1, "ctlInt with a null state is refused");
    CHECK(PP_CTL(e, nullptr, st, 0 /* SET_DENOISE */, nullptr) == -1,
          "ctlInt with a null value array is refused");
    {
        Array<jint> empty(0);
        CHECK(PP_CTL(e, nullptr, st, 0 /* SET_DENOISE */, empty.as<jintArray>()) == -1,
              "ctlInt with an empty value array is refused, not read");
    }

    /* 24/25 store or write a pointer, 39/43 write ps_size ints, 6/7 read and write a float, and
     * the rest are not part of this interface. */
    const jint refused[] = {24, 25, 39, 43, 6, 7, 10, 12, 33, 35, 37, 41, 47, -1, 12345};
    for (jint request : refused) {
        value[0] = 0x5a5a5a5a;
        CHECK(PP_CTL(e, nullptr, st, request, v) == -1,
              "a preprocess request outside the allow list is refused");
        CHECK(value[0] == 0x5a5a5a5a,
              "a refused preprocess request leaves the caller's value alone");
    }

    PP_DESTROY(e, nullptr, st);
}

/* Get*ArrayElements returns NULL when the JVM cannot allocate the copy. Every entry point must
 * return an error instead of dereferencing it, and release anything it already holds.
 * RS(processInt) is the only one holding two copies at once, so it has the only cleanup path. */
static void test_allocation_failure(Env& env) {
    JNIEnv* e = env.get();
    Array<jint> err(1);
    jlong rs = RS_INIT(e, nullptr, 1, 48000, 16000, 3, err.as<jintArray>());
    jlong pp = PP_INIT(e, nullptr, 640, 48000);
    jlong jb = JB_INIT(e, nullptr, 480);
    CHECK(rs != 0 && pp != 0 && jb != 0, "the three states for the allocation-failure run exist");
    if (rs == 0 || pp == 0 || jb == 0) return;

    {
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 480;
        outLen[0] = 160;
        jshortArray i = in.as<jshortArray>(), o = out.as<jshortArray>();
        jintArray il = inLen.as<jintArray>(), ol = outLen.as<jintArray>();

        /* The input copy fails: nothing is held yet. */
        jnistub::fail_get_after(0);
        CHECK(RS_PROCESS(e, nullptr, rs, 0, i, il, o, ol) != RESAMPLER_ERR_SUCCESS,
              "processInt survives the input array copy failing");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "no copy is outstanding after the input failure");

        /* The OUTPUT copy fails, with the input copy already held. This is the leak path. */
        jnistub::fail_get_after(1);
        CHECK(RS_PROCESS(e, nullptr, rs, 0, i, il, o, ol) != RESAMPLER_ERR_SUCCESS,
              "processInt survives the output array copy failing");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0,
              "the input copy is released when the output copy fails");
    }
    {
        Array<jshort> frame(640);
        jnistub::fail_get_after(0);
        CHECK(PP_RUN(e, nullptr, pp, frame.as<jshortArray>()) < 0,
              "run survives GetShortArrayElements returning NULL");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "no copy is leaked on the run failure path");
    }
    {
        Array<jbyte> payload(16);
        jnistub::fail_get_after(0);
        JB_PUT(e, nullptr, jb, payload.as<jbyteArray>(), 16, 0, 480, 0, 0);
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "put survives GetByteArrayElements returning NULL");

        Array<jbyte> out(64);
        Array<jint> meta(5);
        jnistub::fail_get_after(0);
        CHECK(JB_GET(e, nullptr, jb, out.as<jbyteArray>(), 480, meta.as<jintArray>()) != 0,
              "get survives GetByteArrayElements returning NULL");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "no copy is leaked on the get failure path");
    }

    RS_DESTROY(e, nullptr, rs);
    PP_DESTROY(e, nullptr, pp);
    JB_DESTROY(e, nullptr, jb);
}

int main() {
    Env env;
    test_preprocessor(env);
    test_resampler(env);
    test_resampler_channel_index(env);
    test_resampler_init_failure(env);
    test_jitter(env);
    test_jitter_ctl(env);
    test_preprocess_ctl(env);
    test_allocation_failure(env);
    CHECK(jnistub::outstanding_copies() == 0, "no array copy is outstanding at the end of the run");
    std::printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
