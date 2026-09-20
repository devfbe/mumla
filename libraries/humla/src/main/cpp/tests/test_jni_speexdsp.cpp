/* Host test for ../jni_speexdsp.cpp, driven through the stand-in JNIEnv in jni_env_stub.h.
 *
 * This file exists for one specific bug and its relatives. libspeexdsp's API takes bare pointers
 * and takes the length from somewhere other than the buffer:
 *
 *   - speex_preprocess_run writes the frame size the state was CREATED with, not the length of
 *     the array it is handed. The javacpp binding this JNI layer replaced passed a raw pointer,
 *     and at ultra-wideband speex wrote 640 samples into a 480-element Java array -- 640 bytes
 *     past the end of a pinned array, on every single frame. That was a real crash in the field.
 *   - speex_resampler_process_int takes the input and output counts as in/out parameters that
 *     the Kotlin caller supplies, in a separate int[], with no relation to the arrays' lengths.
 *   - jitter_buffer_put takes the payload length as a separate argument.
 *
 * In each case the number that decides how far native code reads or writes arrives independently
 * of the buffer it applies to. The bridge is the only place that can see both, so the bridge is
 * where it has to be checked, and this is where that is held down.
 *
 * The arrays here are exact-size heap blocks, so in the sanitized build an overrun is a trapped
 * heap-buffer-overflow rather than a return value nobody looks at.
 */
#include "jni_env_stub.h"

#include <jni.h>

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

JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_init(JNIEnv*, jobject, jint);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_put(JNIEnv*, jobject, jlong, jbyteArray, jint, jint, jint, jint, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_get(JNIEnv*, jobject, jlong, jbyteArray, jint, jintArray);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexJitterNative_destroy(JNIEnv*, jobject, jlong);
}

#define PP_INIT Java_se_lublin_humla_audio_native_SpeexPreprocessNative_init
#define PP_RUN Java_se_lublin_humla_audio_native_SpeexPreprocessNative_run
#define PP_DESTROY Java_se_lublin_humla_audio_native_SpeexPreprocessNative_destroy
#define RS_INIT Java_se_lublin_humla_audio_native_SpeexResamplerNative_init
#define RS_PROCESS Java_se_lublin_humla_audio_native_SpeexResamplerNative_processInt
#define RS_DESTROY Java_se_lublin_humla_audio_native_SpeexResamplerNative_destroy
#define JB_INIT Java_se_lublin_humla_audio_native_SpeexJitterNative_init
#define JB_PUT Java_se_lublin_humla_audio_native_SpeexJitterNative_put
#define JB_GET Java_se_lublin_humla_audio_native_SpeexJitterNative_get
#define JB_DESTROY Java_se_lublin_humla_audio_native_SpeexJitterNative_destroy

/* The historical crash, reproduced exactly: a state created for 640-sample frames (ultra-wideband,
 * what Mumble's highest quality setting used) handed a 480-element array. */
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
        /* Lying caller: counts far beyond both arrays. Clamped, not obeyed.
         *
         * This one arm is pinned by the SANITIZED build alone, and the distinction was measured,
         * not assumed: with the clamp removed, ASan reports a heap-buffer-overflow at
         * resample.c:947, but the plain binary passes -- speex writes back a consumed count that
         * happens to stay under 480, so the assertions below see nothing wrong. If the sanitized
         * half of this directory is ever switched off, this case stops testing anything. The
         * preprocessor and jitter arms do fail in both builds. */
        Array<jshort> in(480), out(160);
        Array<jint> inLen(1), outLen(1);
        inLen[0] = 48000;
        outLen[0] = 16000;
        RS_PROCESS(e, nullptr, st, 0, in.as<jshortArray>(), inLen.as<jintArray>(),
                   out.as<jshortArray>(), outLen.as<jintArray>());
        CHECK(inLen[0] <= 480, "an input count larger than the input array is clamped to it");
        CHECK(outLen[0] <= 160, "an output count larger than the output array is clamped to it");
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
        /* get() writes five ints into meta. A shorter array must not be written past: the stub's
         * SetIntArrayRegion aborts the process on an out-of-range write, the way a real JVM
         * would throw, so reaching the next line at all is the assertion. */
        Array<jbyte> out(64);
        Array<jint> meta(2);
        JB_GET(e, nullptr, jb, out.as<jbyteArray>(), 480, meta.as<jintArray>());
        CHECK(true, "get with a short meta array does not write past it");
        CHECK(jnistub::outstanding_copies() == 0, "get releases the output copy");
    }

    JB_DESTROY(e, nullptr, jb);
    JB_DESTROY(e, nullptr, 0);
}

int main() {
    Env env;
    test_preprocessor(env);
    test_resampler(env);
    test_jitter(env);
    CHECK(jnistub::outstanding_copies() == 0, "no array copy is outstanding at the end of the run");
    std::printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
