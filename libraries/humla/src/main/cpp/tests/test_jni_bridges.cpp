/* Host test for the two hand-written JNI bridges, ../jni_rnnoise.cpp and ../jni_webrtc_apm.cpp.
 *
 * The bridges are executed for real, through a stand-in JNIEnv (jni_env_stub.h) whose arrays are
 * exact-size heap blocks. There is no JVM: the entry points are plain C functions and calling
 * them directly is exactly what the JVM does.
 *
 * Three properties are held down here, all of which are silent when broken:
 *
 *   1. Every buffer that crosses the boundary is length-checked. The wrappers underneath take a
 *      bare int16_t* and write a fixed number of samples into it; a Java array that is shorter is
 *      an out-of-bounds write, not an error code. This project has shipped exactly that bug
 *      before (speex writing 640 samples into a 480-element array at ultra-wideband, on every
 *      frame). The short-buffer cases below assert the error code, which is what holds the
 *      property down; the sanitized build additionally traps the write itself, but only where
 *      the code doing the writing is instrumented, so it is evidence and not the assertion.
 *   2. A handle freed twice frees the native object once. Kotlin owns these handles; a release()
 *      that runs twice, or an adapter that is closed and then finalised, is an ordinary mistake
 *      and it corrupts the heap rather than throwing.
 *   3. processRender and processCapture are wired to the *right* C function. Swapping them
 *      returns 0 from every call and simply turns echo cancellation off. The AEC section
 *      measures that, because nothing else can see it.
 */
#include "jni_env_stub.h"

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "humla_apm.h"
#include "humla_rnnoise.h"

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

/* The entry points under test. Declared rather than included, for the same reason test_apm.c is
 * a .c file: if a signature in the bridge ever drifts from what the JVM will call, or from what
 * the Kotlin `external fun` declares, this file stops linking. */
extern "C" {
JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_RnnoiseNative_create(JNIEnv*, jobject) noexcept;
JNIEXPORT jfloat JNICALL Java_se_lublin_humla_audio_native_RnnoiseNative_processFrame(JNIEnv*, jobject, jlong, jshortArray) noexcept;
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_RnnoiseNative_destroy(JNIEnv*, jobject, jlong) noexcept;

JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_create(JNIEnv*, jobject, jint, jboolean, jboolean, jint, jboolean, jboolean) noexcept;
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_frameSize(JNIEnv*, jobject, jlong) noexcept;
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_processCapture(JNIEnv*, jobject, jlong, jshortArray) noexcept;
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_processRender(JNIEnv*, jobject, jlong, jshortArray) noexcept;
JNIEXPORT jfloat JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_lastCaptureLevelDbfs(JNIEnv*, jobject, jlong) noexcept;
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_WebRtcApmNative_destroy(JNIEnv*, jobject, jlong) noexcept;
}

#define RN_CREATE Java_se_lublin_humla_audio_native_RnnoiseNative_create
#define RN_PROCESS Java_se_lublin_humla_audio_native_RnnoiseNative_processFrame
#define RN_DESTROY Java_se_lublin_humla_audio_native_RnnoiseNative_destroy
#define APM_CREATE Java_se_lublin_humla_audio_native_WebRtcApmNative_create
#define APM_FRAME_SIZE Java_se_lublin_humla_audio_native_WebRtcApmNative_frameSize
#define APM_CAPTURE Java_se_lublin_humla_audio_native_WebRtcApmNative_processCapture
#define APM_RENDER Java_se_lublin_humla_audio_native_WebRtcApmNative_processRender
#define APM_LEVEL Java_se_lublin_humla_audio_native_WebRtcApmNative_lastCaptureLevelDbfs
#define APM_DESTROY Java_se_lublin_humla_audio_native_WebRtcApmNative_destroy

/* webrtc::AudioProcessing::Error values the bridges pass through or produce. Spelled out rather
 * than included, because including audio_processing.h here would drag the C++ webrtc headers
 * into a translation unit whose whole subject is that the JNI layer never sees them. */
enum { kNullPointerError = -5, kBadDataLengthError = -8 };

enum { kRate = 48000, kFrame = kRate / 100 };

static double energy(const jshort* f, int n) {
    double e = 0;
    for (int i = 0; i < n; i++) e += double(f[i]) * double(f[i]);
    return e / n;
}

static double to_db(double ratio) { return ratio <= 0.0 ? -200.0 : 10.0 * std::log10(ratio); }

static std::uint32_t rng;
static jshort noise_from(std::uint32_t* s) {
    *s = *s * 1664525u + 1013904223u;
    return jshort(((std::int32_t)(*s >> 16) & 0x7fff) - 16384) / 2; /* +-4096 */
}
static jshort noise() { return noise_from(&rng); }

/* ------------------------------------------------------------------ rnnoise */

static void test_rnnoise(Env& env) {
    JNIEnv* e = env.get();

    jlong h = RN_CREATE(e, nullptr);
    CHECK(h != 0, "rnnoise create returns a handle");
    if (h == 0) return;

    /* A frame of exactly FRAME_SIZE is processed and the VAD probability is in range. */
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE);
        rng = 9u;
        for (jsize i = 0; i < frame.length(); i++) frame[i] = noise();
        jfloat p = RN_PROCESS(e, nullptr, h, frame.as<jshortArray>());
        CHECK(p >= 0.0f && p <= 1.0f, "rnnoise processFrame returns a probability in [0,1]");
        CHECK(jnistub::outstanding_copies() == 0, "rnnoise processFrame releases the array copy");
    }

    /* A longer frame is accepted; only the first FRAME_SIZE samples may be touched. The tail is
     * poisoned with a sentinel so a wrapper that started reading or writing past 480 shows up. */
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE + 64);
        for (jsize i = HUMLA_RNNOISE_FRAME_SIZE; i < frame.length(); i++) frame[i] = 0x5a5a;
        jfloat p = RN_PROCESS(e, nullptr, h, frame.as<jshortArray>());
        CHECK(p >= 0.0f, "rnnoise accepts an over-long frame");
        bool tail_intact = true;
        for (jsize i = HUMLA_RNNOISE_FRAME_SIZE; i < frame.length(); i++)
            if (frame[i] != jshort(0x5a5a)) tail_intact = false;
        CHECK(tail_intact, "rnnoise does not write past FRAME_SIZE");
    }

    /* A frame that is one sample short must be refused, not processed. Without the check this is
     * a 2-byte heap overflow per frame; the sanitized build turns it into a report. */
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE - 1);
        CHECK(RN_PROCESS(e, nullptr, h, frame.as<jshortArray>()) < 0.0f,
              "rnnoise refuses a frame shorter than FRAME_SIZE");
        CHECK(jnistub::outstanding_copies() == 0, "a refused short frame is never pinned");
    }

    CHECK(RN_PROCESS(e, nullptr, 0, nullptr) < 0.0f, "rnnoise processFrame(0, null) reports an error");
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE);
        CHECK(RN_PROCESS(e, nullptr, 0, frame.as<jshortArray>()) < 0.0f,
              "rnnoise processFrame with a null handle reports an error");
        CHECK(RN_PROCESS(e, nullptr, h, nullptr) < 0.0f,
              "rnnoise processFrame with a null array reports an error");

        /* The JVM returns NULL from GetShortArrayElements when it cannot allocate the copy. */
        jnistub::fail_get_after(0);
        CHECK(RN_PROCESS(e, nullptr, h, frame.as<jshortArray>()) < 0.0f,
              "rnnoise survives GetShortArrayElements returning NULL");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "no array copy is leaked on the failure path");
    }

    /* Lifetime. destroy() is called twice on purpose: Kotlin owns this handle, and a release()
     * that runs twice (explicit close plus a finaliser, two adapters sharing one handle) is an
     * ordinary mistake. Without the guard this is a double free -- heap corruption in the plain
     * build, an ASan "attempting double-free" report in the sanitized one. */
    RN_DESTROY(e, nullptr, h);
    RN_DESTROY(e, nullptr, h);

    /* And a handle that was destroyed must not be processed. */
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE);
        CHECK(RN_PROCESS(e, nullptr, h, frame.as<jshortArray>()) < 0.0f,
              "rnnoise processFrame on a destroyed handle reports an error");
    }

    RN_DESTROY(e, nullptr, 0);           /* a zero handle is a no-op */
    RN_DESTROY(e, nullptr, 0xdeadbeef);  /* so is a handle that was never created */
}

/* ------------------------------------------------------------------ webrtc apm */

static void test_apm_arguments(Env& env) {
    JNIEnv* e = env.get();

    CHECK(APM_CREATE(e, nullptr, 44100, JNI_TRUE, JNI_FALSE, 0, JNI_FALSE, JNI_TRUE) == 0,
          "apm create rejects 44.1 kHz");
    CHECK(APM_FRAME_SIZE(e, nullptr, 0) == 0, "apm frameSize of a null handle is 0");

    jlong h = APM_CREATE(e, nullptr, kRate, JNI_TRUE, JNI_TRUE, 2, JNI_TRUE, JNI_TRUE);
    CHECK(h != 0, "apm create succeeds at 48 kHz with every stage on");
    if (h == 0) return;
    CHECK(APM_FRAME_SIZE(e, nullptr, h) == kFrame, "apm frameSize is 10 ms worth of samples");

    {
        Array<jshort> frame(kFrame);
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) == 0, "apm capture of silence succeeds");
        CHECK(APM_RENDER(e, nullptr, h, frame.as<jshortArray>()) == 0, "apm render of silence succeeds");
        CHECK(jnistub::outstanding_copies() == 0, "apm releases both array copies");
        CHECK(APM_LEVEL(e, nullptr, h) <= -99.0f, "apm reports -100 dBFS for silence");
    }

    /* The short-buffer case. humla_apm writes exactly frame_size samples and cannot see the
     * length, so the bridge is the only place this can be caught. One sample short is enough:
     * without the check webrtc writes 480 int16 into 479 elements on every single frame.
     *
     * The return code is what pins this, deliberately, and not the sanitizer. Removing the check
     * was tried: ASan does report it, but from humla_apm.cpp's own level_dbfs -- the one
     * instrumented translation unit that happens to read the buffer afterwards. The write inside
     * webrtc's ~250 uninstrumented objects is invisible, so a rearrangement that stopped
     * level_dbfs touching the frame would take the sanitizer's half of the evidence with it. */
    {
        Array<jshort> frame(kFrame - 1);
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) == kBadDataLengthError,
              "apm capture refuses a frame shorter than frameSize");
        CHECK(APM_RENDER(e, nullptr, h, frame.as<jshortArray>()) == kBadDataLengthError,
              "apm render refuses a frame shorter than frameSize");
        CHECK(jnistub::outstanding_copies() == 0, "a refused short frame is never pinned");
    }

    /* An over-long frame is fine, but only the first frameSize samples may be touched. */
    {
        Array<jshort> frame(kFrame + 32);
        for (jsize i = kFrame; i < frame.length(); i++) frame[i] = 0x5a5a;
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) == 0, "apm accepts an over-long frame");
        bool tail_intact = true;
        for (jsize i = kFrame; i < frame.length(); i++)
            if (frame[i] != jshort(0x5a5a)) tail_intact = false;
        CHECK(tail_intact, "apm does not write past frameSize");
    }

    CHECK(APM_CAPTURE(e, nullptr, h, nullptr) == kNullPointerError, "apm capture(null array) reports an error");
    CHECK(APM_RENDER(e, nullptr, h, nullptr) == kNullPointerError, "apm render(null array) reports an error");
    {
        Array<jshort> frame(kFrame);
        CHECK(APM_CAPTURE(e, nullptr, 0, frame.as<jshortArray>()) == kNullPointerError,
              "apm capture with a null handle reports an error");
        CHECK(APM_RENDER(e, nullptr, 0, frame.as<jshortArray>()) == kNullPointerError,
              "apm render with a null handle reports an error");
        CHECK(APM_LEVEL(e, nullptr, 0) <= -99.0f, "apm level of a null handle is -100 dBFS");

        jnistub::fail_get_after(0);
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) != 0,
              "apm survives GetShortArrayElements returning NULL");
        jnistub::fail_get_never();
        CHECK(jnistub::outstanding_copies() == 0, "no array copy is leaked on the failure path");
    }

    APM_DESTROY(e, nullptr, h);
    APM_DESTROY(e, nullptr, h);  /* exactly-once, see the rnnoise comment */
    {
        Array<jshort> frame(kFrame);
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) != 0,
              "apm capture on a destroyed handle reports an error");
        CHECK(APM_RENDER(e, nullptr, h, frame.as<jshortArray>()) != 0,
              "apm render on a destroyed handle reports an error");
        CHECK(APM_FRAME_SIZE(e, nullptr, h) == 0, "apm frameSize of a destroyed handle is 0");
    }
    APM_DESTROY(e, nullptr, 0);
}

/* Does processRender really feed the far-end stream, and processCapture really process the
 * near-end one?
 *
 * Swapping the two C functions inside the bridge -- or bridging processRender to
 * humla_apm_process_capture by a copy-paste slip -- returns 0 from every call and leaves the
 * capture path with no echo cancellation whatsoever. No error code, no log line.
 *
 * The rig is the one test_apm.c established and whose thresholds it measured: a -6 dB echo path
 * delayed by three frames (30 ms), 1000 frames (10 s), energies accumulated over the last 100.
 * Both numbers are load-bearing, not padding: over a zero-delay path AEC3 never converges, and
 * at 600 frames a deliberately misaligned reference still reads -23.9 dB and is indistinguishable
 * from a correct one. Shorten either and this test stops discriminating while still passing.
 *
 * `mismatched` runs the same rig with unrelated audio on the far-end stream. It is not testing
 * webrtc -- test_apm.c does that -- it is what makes the ordered arm's threshold meaningful: if
 * both arms came back attenuated, the measurement would be proving nothing about the wiring. */
struct AecResult {
    double input = 0, output = 0;
    jint err = 0;
};

static AecResult run_aec_through_jni(Env& env, bool mismatched) {
    JNIEnv* e = env.get();
    AecResult r;
    jlong h = APM_CREATE(e, nullptr, kRate, JNI_TRUE, JNI_FALSE, 0, JNI_FALSE, JNI_TRUE);
    if (h == 0) {
        r.err = -1;
        return r;
    }
    rng = 12345u;
    std::uint32_t other = 777u;
    enum { kEchoDelay = 3 };
    jshort echo[kEchoDelay][kFrame];
    std::memset(echo, 0, sizeof echo);

    Array<jshort> render(kFrame), capture(kFrame), unrelated(kFrame);
    for (int n = 0; n < 1000; n++) {
        for (int i = 0; i < kFrame; i++) {
            render[i] = noise();
            unrelated[i] = noise_from(&other);
        }
        for (int i = 0; i < kFrame; i++) capture[i] = jshort(echo[n % kEchoDelay][i] / 2);
        std::memcpy(echo[n % kEchoDelay], render.data(), sizeof(jshort) * kFrame);
        double in = energy(capture.data(), kFrame);

        r.err |= APM_RENDER(e, nullptr, h,
                            (mismatched ? unrelated : render).as<jshortArray>());
        r.err |= APM_CAPTURE(e, nullptr, h, capture.as<jshortArray>());

        if (n >= 900) {
            r.input += in;
            r.output += energy(capture.data(), kFrame);
        }
    }
    APM_DESTROY(e, nullptr, h);
    return r;
}

static void test_apm_wiring(Env& env) {
    AecResult ordered = run_aec_through_jni(env, false);
    AecResult mismatched = run_aec_through_jni(env, true);

    std::printf("JNI-driven AEC residual, last 1 s of 10 s, -6 dB echo path delayed 30 ms:\n");
    std::printf("  %-22s : %+6.2f dB\n", "render then capture", to_db(ordered.output / ordered.input));
    std::printf("  %-22s : %+6.2f dB\n", "far-end mismatched", to_db(mismatched.output / mismatched.input));

    CHECK(ordered.err == 0, "every JNI AEC call returns 0");
    CHECK(mismatched.err == 0, "every JNI AEC call returns 0 with a mismatched reference too");
    /* Kills a bridge whose processRender calls humla_apm_process_capture (or the other way
     * round): with the streams crossed nothing is cancelled and this ratio stays near 1. */
    CHECK(ordered.output < ordered.input / 16.0,
          "driven through JNI in the right order, the echo is attenuated by at least 12 dB");
    CHECK(mismatched.output > mismatched.input / 2.0,
          "with unrelated audio on the far-end stream, the echo is NOT cancelled");
    CHECK(ordered.output * 4.0 < mismatched.output,
          "it is processRender feeding the real far-end frame that buys the cancellation");
}

int main() {
    Env env;
    test_rnnoise(env);
    test_apm_arguments(env);
    test_apm_wiring(env);
    CHECK(jnistub::outstanding_copies() == 0, "no array copy is outstanding at the end of the run");
    std::printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
