/* Host test for the JNI bridges ../jni_rnnoise.cpp and ../jni_webrtc_apm.cpp.
 *
 * The bridges run for real through a stand-in JNIEnv (jni_env_stub.h) whose arrays are exact-size
 * heap blocks; the registered functions are called directly, which is what the JVM does. Three properties that are silent when broken:
 *
 *   1. Every buffer crossing the boundary is length-checked (the wrappers write a fixed number of
 *      samples into a bare int16_t*). The error code is the assertion; the sanitized build
 *      additionally traps the write where the writer is instrumented.
 *   2. A handle freed twice frees the native object once.
 *   3. processRender and processCapture are wired to the right C function.
 */
#include "jni_env_stub.h"

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include "jni_bridges.h"
#include "webrtc_apm/humla_apm.h"
#include "rnnoise/humla_rnnoise.h"

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

/* The entry points under test, looked up in what the bridges registered. jnistub::native() checks
 * each registered signature against the type it is called through. */
#define RN(name, type) (jnistub::native<type>("se/lublin/humla/audio/native/RnnoiseNative", name))
#define APM(name, type) (jnistub::native<type>("se/lublin/humla/audio/native/WebRtcApmNative", name))

#define RN_CREATE RN("create", jlong (*)(JNIEnv*, jobject) noexcept)
#define RN_PROCESS RN("processFrame", jfloat (*)(JNIEnv*, jobject, jlong, jshortArray) noexcept)
#define RN_DESTROY RN("destroy", void (*)(JNIEnv*, jobject, jlong) noexcept)
#define APM_CREATE \
    APM("create", jlong (*)(JNIEnv*, jobject, jint, jboolean, jboolean, jint, jboolean, jboolean) noexcept)
#define APM_FRAME_SIZE APM("frameSize", jint (*)(JNIEnv*, jobject, jlong) noexcept)
#define APM_CAPTURE APM("processCapture", jint (*)(JNIEnv*, jobject, jlong, jshortArray) noexcept)
#define APM_RENDER APM("processRender", jint (*)(JNIEnv*, jobject, jlong, jshortArray) noexcept)
#define APM_LEVEL APM("lastCaptureLevelDbfs", jfloat (*)(JNIEnv*, jobject, jlong) noexcept)
#define APM_DESTROY APM("destroy", void (*)(JNIEnv*, jobject, jlong) noexcept)

/* webrtc::AudioProcessing::Error values, spelled out to keep the webrtc C++ headers out. */
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
    }

    /* A longer frame is accepted; only the first FRAME_SIZE samples may be touched. The tail is
     * poisoned with a sentinel to detect a wrapper reading or writing past 480. */
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

    /* A frame one sample short must be refused, not processed (a heap overflow otherwise). */
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE - 1);
        CHECK(RN_PROCESS(e, nullptr, h, frame.as<jshortArray>()) < 0.0f,
              "rnnoise refuses a frame shorter than FRAME_SIZE");
    }

    CHECK(RN_PROCESS(e, nullptr, 0, nullptr) < 0.0f, "rnnoise processFrame(0, null) reports an error");
    {
        Array<jshort> frame(HUMLA_RNNOISE_FRAME_SIZE);
        CHECK(RN_PROCESS(e, nullptr, 0, frame.as<jshortArray>()) < 0.0f,
              "rnnoise processFrame with a null handle reports an error");
        CHECK(RN_PROCESS(e, nullptr, h, nullptr) < 0.0f,
              "rnnoise processFrame with a null array reports an error");
    }

    /* destroy() twice on purpose (explicit close plus a finaliser is an ordinary mistake):
     * without the guard this is a double free. */
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
        CHECK(APM_LEVEL(e, nullptr, h) <= -99.0f, "apm reports -100 dBFS for silence");
    }

    /* One sample short: humla_apm writes exactly frame_size samples and cannot see the length.
     * The return code pins this, not the sanitizer: the write happens inside uninstrumented
     * webrtc objects. */
    {
        Array<jshort> frame(kFrame - 1);
        CHECK(APM_CAPTURE(e, nullptr, h, frame.as<jshortArray>()) == kBadDataLengthError,
              "apm capture refuses a frame shorter than frameSize");
        CHECK(APM_RENDER(e, nullptr, h, frame.as<jshortArray>()) == kBadDataLengthError,
              "apm render refuses a frame shorter than frameSize");
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

/* Does processRender really feed the far-end stream, and processCapture process the near-end one?
 * Swapping them returns 0 from every call and silently disables echo cancellation.
 *
 * Same rig as test_apm.c: -6 dB echo path delayed by 30 ms, 10 s, energies over the last second;
 * shorter runs or a zero-delay path do not converge and would stop discriminating. `mismatched`
 * feeds unrelated far-end audio so the ordered arm's threshold means something. */
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
    CHECK(humla::registerRnnoiseNatives(env.get()), "the rnnoise bridge registers");
    CHECK(humla::registerWebRtcApmNatives(env.get()), "the apm bridge registers");
    test_rnnoise(env);
    test_apm_arguments(env);
    test_apm_wiring(env);
    std::printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
