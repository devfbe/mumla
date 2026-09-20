/* Host test for the webrtc-audio-processing C wrapper.
 *
 * Deliberately a .c file compiled by the C compiler. The whole point of humla_apm.h is that the
 * JNI layer never sees C++, so the test that proves it has to be built the way the JNI layer is:
 * if the header stopped being valid C, or if extern "C" were dropped from humla_apm.cpp, this
 * translation unit would fail to compile or fail to link. A .cpp test would not notice either.
 */
#include "humla_apm.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) {                                                \
            fprintf(stderr, "FAIL: %s\n", (msg));                     \
            failures++;                                               \
        }                                                             \
    } while (0)

enum { kRate = 48000, kFrame = kRate / 100 };

static double energy(const int16_t *f, int n) {
    double e = 0;
    for (int i = 0; i < n; i++) e += (double)f[i] * (double)f[i];
    return e / n;
}

static double to_db(double ratio) {
    if (ratio <= 0.0) return -200.0;
    return 10.0 * log10(ratio);
}

/* Deterministic white noise. A sine is a poor excitation for an adaptive filter (it is
 * rank-one, and it is periodic, so a reference that arrives at the wrong time still lines up
 * with the echo). Broadband noise makes both the convergence and the misalignment cases
 * unambiguous, and the fixed seed keeps the test reproducible. */
static uint32_t rng;
static void seed(uint32_t s) { rng = s; }
static int16_t noise_from(uint32_t *s) {
    *s = *s * 1664525u + 1013904223u;
    return (int16_t)(((int32_t)(*s >> 16) & 0x7fff) - 16384) / 2; /* +-4096 */
}
static int16_t noise(void) { return noise_from(&rng); }

/* Which of the two streams is fed, and in what relation to the near-end frame that carries its
 * echo. This is the property the JNI layer in Task 3 has to get right, and getting it wrong is
 * silent: the APM returns 0 from every call either way. */
typedef enum {
    kOrdered,     /* render(n) then capture(n): the reference precedes the echo. Correct. */
    kNoRender,    /* the far-end stream is never fed at all. */
    kMismatched,  /* the far-end stream is fed, but with audio that is not what was played. */
    kCaptureFirst,/* capture(n) then render(n): the reference arrives after its own echo. */
    kLateRender   /* the reference arrives 20 frames (200 ms) after its own echo. */
} order_t;

typedef struct {
    double input;   /* energy of the capture frame before processing */
    double output;  /* energy after processing */
    int err;
} aec_result;

/* Runs 10 s of a -6 dB echo path delayed by 30 ms and accumulates energies over the last
 * second, i.e. after the adaptive filter has had 9 s to converge.
 *
 * Both numbers are load-bearing and were measured, not guessed:
 *
 *   - A zero-delay echo path never converges. AEC3 is built around a delay estimator and a
 *     filter that models a real acoustic path; an echo that arrives in the same frame as its
 *     reference is not one. This test used to use one, and as a result no arm of the matrix
 *     below ever converged -- what it measured was AEC3's conservative initial attenuation,
 *     which suppresses everything alike, so all four arms with a far-end stream read the same
 *     -24.1 dB and looked like "AEC3 cannot tell a misaligned reference apart". They are not
 *     the same; nothing had converged. 30 ms is a plausible loudspeaker-to-microphone path on
 *     a handset; 10, 20, 50 and 100 ms all give the same separation to within 0.05 dB.
 *
 *   - 10 s, not 5. At 5 s nothing has converged. At 6 s the misaligned arms still read -23.9 dB
 *     (i.e. still just the initial attenuation); they fall to -0.9 dB at 7 s and settle by 10 s.
 *     The run is long enough that the separation is a plateau rather than a moment.
 *
 * set_stream_delay_ms is deliberately left at 0 even though the path is 30 ms: AEC3 runs its
 * own delay estimator, and feeding it the true delay changes none of the figures below by more
 * than 0.02 dB. The platform hint is an optimisation, not a correctness requirement, which is
 * worth knowing before Task 3 tries to compute one. */
static aec_result run_aec(order_t order) {
    aec_result r = {0, 0, 0};
    humla_apm_config cfg = {1, 0, 0, 0, 1};
    humla_apm *apm = humla_apm_create(kRate, &cfg);
    if (!apm) { r.err = -1; return r; }
    seed(12345u);
    uint32_t other = 777u;
    enum { kLag = 20, kEchoDelay = 3 };
    int16_t held[kLag][kFrame];
    int16_t echo[kEchoDelay][kFrame];
    memset(echo, 0, sizeof echo);
    for (int n = 0; n < 1000; n++) {
        int16_t render[kFrame], capture[kFrame], unrelated[kFrame];
        for (int i = 0; i < kFrame; i++) {
            render[i] = noise();
            unrelated[i] = noise_from(&other);
        }
        /* Echo path: -6 dB, delayed by kEchoDelay frames. The slot about to be overwritten
         * still holds the render frame from kEchoDelay ticks ago. */
        for (int i = 0; i < kFrame; i++) capture[i] = (int16_t)(echo[n % kEchoDelay][i] / 2);
        memcpy(echo[n % kEchoDelay], render, sizeof render);
        double ie = energy(capture, kFrame);

        if (order == kOrdered) {
            r.err |= humla_apm_process_render(apm, render);
        } else if (order == kMismatched) {
            r.err |= humla_apm_process_render(apm, unrelated);
        } else if (order == kLateRender) {
            if (n >= kLag) { int16_t old[kFrame];
                memcpy(old, held[n % kLag], sizeof old);
                r.err |= humla_apm_process_render(apm, old); }
            memcpy(held[n % kLag], render, sizeof render);
        }
        humla_apm_set_stream_delay_ms(apm, 0);

        if (order == kCaptureFirst) {
            r.err |= humla_apm_process_capture(apm, capture);
            r.err |= humla_apm_process_render(apm, render);
        } else {
            r.err |= humla_apm_process_capture(apm, capture);
        }

        if (n >= 900) { r.input += ie; r.output += energy(capture, kFrame); }
    }
    humla_apm_destroy(apm);
    return r;
}

int main(void) {
    /* ---- 1. Argument validation. ---- */
    humla_apm_config ns_only = {0, 1, 2, 0, 1};
    CHECK(humla_apm_create(kRate, NULL) == NULL, "a NULL config is rejected");
    CHECK(humla_apm_create(44100, &ns_only) == NULL, "44.1 kHz is rejected");
    CHECK(humla_apm_create(0, &ns_only) == NULL, "0 Hz is rejected");
    CHECK(humla_apm_create(-48000, &ns_only) == NULL, "a negative rate is rejected");
    static const int kRates[] = {8000, 16000, 32000, 48000};
    for (int i = 0; i < 4; i++) {
        humla_apm *h = humla_apm_create(kRates[i], &ns_only);
        CHECK(h != NULL, "every supported rate is accepted");
        if (h) {
            CHECK(humla_apm_frame_size(h) == kRates[i] / 100, "frame size is 10 ms worth of samples");
            humla_apm_destroy(h);
        }
    }
    /* Out-of-range NS levels are clamped, not rejected: the JNI layer passes through a user
     * preference and must not be able to hand the APM an invalid enum. */
    humla_apm_config all_on = {1, 1, 3, 1, 1};
    humla_apm *every = humla_apm_create(kRate, &all_on);
    CHECK(every != NULL, "AEC3 + NS + AGC2 + HPF all enabled at once succeeds");
    if (every) {
        int16_t f[kFrame];
        memset(f, 0, sizeof f);
        CHECK(humla_apm_process_render(every, f) == 0, "render succeeds with every stage on");
        CHECK(humla_apm_process_capture(every, f) == 0, "capture succeeds with every stage on");
        humla_apm_destroy(every);
    }

    humla_apm_config wild = {0, 1, 99, 0, 1};
    humla_apm *clamped = humla_apm_create(kRate, &wild);
    CHECK(clamped != NULL, "an out-of-range NS level is clamped, not rejected");
    humla_apm_destroy(clamped);

    /* ---- 2. Every entry point tolerates a NULL handle. ---- */
    int16_t dummy[kFrame];
    memset(dummy, 0, sizeof dummy);
    CHECK(humla_apm_process_capture(NULL, dummy) != 0, "process_capture(NULL) reports an error");
    CHECK(humla_apm_process_render(NULL, dummy) != 0, "process_render(NULL) reports an error");
    CHECK(humla_apm_set_stream_delay_ms(NULL, 0) != 0, "set_stream_delay_ms(NULL) reports an error");
    CHECK(humla_apm_last_capture_level_dbfs(NULL) <= -99.0f, "level(NULL) is -100 dBFS");
    CHECK(humla_apm_frame_size(NULL) == 0, "frame_size(NULL) is 0");
    humla_apm_destroy(NULL); /* must not crash */

    /* ---- 3. Noise suppression only: zeros in, zeros out, no error. ---- */
    humla_apm *ns = humla_apm_create(kRate, &ns_only);
    CHECK(ns != NULL, "create with NS+HPF succeeds at 48 kHz");
    if (ns) {
        int16_t frame[kFrame];
        int err = 0;
        CHECK(humla_apm_process_capture(ns, NULL) != 0, "a NULL frame reports an error");
        for (int n = 0; n < 20 && err == 0; n++) {
            memset(frame, 0, sizeof frame);
            err = humla_apm_process_capture(ns, frame);
        }
        CHECK(err == 0, "process_capture returns 0");
        CHECK(energy(frame, kFrame) == 0.0, "silence stays silent");
        CHECK(humla_apm_last_capture_level_dbfs(ns) <= -99.0f, "silence reports -100 dBFS");
        humla_apm_destroy(ns);
    }

    /* ---- 4. AEC3, and the relation between the near-end and far-end streams. ----
     *
     * The APM is stateful and the two streams have to be fed in a fixed relation:
     * process_render for the frame that is about to be played, then process_capture for the
     * frame that was just recorded and contains its echo. Getting that wrong returns 0 from
     * every call, which is why it is worth a test rather than a comment.
     *
     * Measured on this matrix, 10 s run, last 1 s, -6 dB echo path delayed by 30 ms:
     *
     *   - far-end fed correctly      : -22.32 dB residual
     *   - far-end never fed          :  -0.29 dB   <- echo cancellation does nothing
     *   - far-end fed, wrong audio   :  -0.62 dB   <- likewise
     *   - capture fed before render  : -22.30 dB   <- AEC3 absorbs this one; see below
     *   - far-end fed 200 ms late    :  -0.63 dB   <- likewise nothing
     *
     * Three of the four wrong wirings therefore separate from the correct one by ~21.7 dB, and
     * they are asserted, not merely printed. The margins are wide: the correct case attenuates
     * by a factor of 171 where 16 is required, and the broken cases attenuate by a factor of
     * 1.2 where anything above 2 would fail. The separation holds at echo-path delays of 10,
     * 20, 30, 50 and 100 ms and at echo-path gains of -6 dB (21.7 dB apart), -12 dB (15.8 dB)
     * and -20 dB (8.8 dB).
     *
     * This corrects what an earlier revision of this file asserted. It claimed that AEC3 gates
     * its nonlinear suppressor on far-end *activity* and therefore cannot tell a misaligned
     * reference from a correct one, and it declined to assert anything about the misaligned
     * cases on that basis. That was wrong, and it was wrong for an instructive reason: the test
     * that produced it ran 5 s over a zero-delay echo path, and in that setup no arm of the
     * matrix converges. The four identical -24.1 dB readings were AEC3's conservative initial
     * attenuation applied uniformly, not a suppressor decision. The gain of the echo path was
     * varied, and a near-end talker was tried; the two variables that actually mattered -- how
     * long the filter is given and whether the echo path has any delay at all -- were held
     * fixed at values where nothing can converge. Vary either and the cases separate.
     *
     * kCaptureFirst is the one wrong wiring that is deliberately NOT asserted as broken, and it
     * must stay that way. Feeding capture(n) before render(n) advances the reference by exactly
     * one 10 ms frame; with a real 30 ms echo path the reference still arrives 20 ms ahead of
     * the echo it belongs to, which is inside the range AEC3's delay estimator is built to
     * align. It cancels (-22.30 dB) because that is correct behaviour, not because the test is
     * blind. Do not "fix" this line into an assertion: it would be asserting that AEC3 fails at
     * something it is supposed to handle, and it would fail the moment the estimator improved.
     * The ordering still matters in production -- one frame of slack is all there is, and the
     * JNI layer has no reason to spend it -- but this test cannot be what pins it. */
    static const char *names[] = {"render then capture", "far-end never fed", "far-end mismatched",
                                  "capture before render", "far-end 200 ms late"};
    aec_result r[5];
    printf("AEC residual, last 1 s of 10 s, -6 dB echo path delayed 30 ms, no near-end talker:\n");
    for (int o = 0; o <= (int)kLateRender; o++) {
        r[o] = run_aec((order_t)o);
        CHECK(r[o].err == 0, "every AEC call returns 0 whatever the call order");
        printf("  %-22s : %+6.2f dB\n", names[o], to_db(r[o].output / r[o].input));
    }
    CHECK(r[kOrdered].output < r[kOrdered].input / 16.0,
          "render->capture in the right order attenuates the echo by at least 12 dB");
    CHECK(r[kNoRender].output > r[kNoRender].input / 2.0,
          "with the far-end stream never fed, the echo is NOT cancelled");
    CHECK(r[kMismatched].output > r[kMismatched].input / 2.0,
          "with the wrong audio on the far-end stream, the echo is NOT cancelled");
    CHECK(r[kLateRender].output > r[kLateRender].input / 2.0,
          "with the far-end stream 200 ms late, the echo is NOT cancelled");
    CHECK(r[kOrdered].output * 4.0 < r[kNoRender].output,
          "feeding the far-end stream is what buys the cancellation");
    CHECK(r[kOrdered].output * 4.0 < r[kMismatched].output,
          "feeding the *right* audio on the far-end stream is what buys the cancellation");

    /* process_render must not be mistaken for a capture frame: it feeds the reference, it does
     * not produce near-end output, and it must leave the reported capture level alone. */
    humla_apm_config aec = {1, 0, 0, 0, 1};
    humla_apm *a = humla_apm_create(kRate, &aec);
    CHECK(a != NULL, "create with AEC3 succeeds");
    if (a) {
        int16_t loud[kFrame], quiet[kFrame];
        seed(4242u);
        for (int i = 0; i < kFrame; i++) { loud[i] = (int16_t)(noise() * 4); quiet[i] = 0; }
        CHECK(humla_apm_process_capture(a, quiet) == 0, "capture of silence succeeds");
        float after_capture = humla_apm_last_capture_level_dbfs(a);
        CHECK(humla_apm_process_render(a, loud) == 0, "render of a loud frame succeeds");
        CHECK(humla_apm_last_capture_level_dbfs(a) == after_capture,
              "process_render does not change the reported capture level");
        humla_apm_destroy(a);
    }

    printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
