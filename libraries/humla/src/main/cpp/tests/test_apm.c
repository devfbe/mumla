/* Host test for the webrtc-audio-processing C wrapper.
 *
 * Deliberately C: if humla_apm.h stopped being valid C, or extern "C" were dropped from
 * humla_apm.cpp, this would fail to compile or link. A .cpp test would not notice.
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

/* Deterministic white noise. A sine is a poor excitation for an adaptive filter (rank-one and
 * periodic, so a misaligned reference still lines up with the echo). */
static uint32_t rng;
static void seed(uint32_t s) { rng = s; }
static int16_t noise_from(uint32_t *s) {
    *s = *s * 1664525u + 1013904223u;
    return (int16_t)(((int32_t)(*s >> 16) & 0x7fff) - 16384) / 2; /* +-4096 */
}
static int16_t noise(void) { return noise_from(&rng); }

/* Which of the two streams is fed, and in what relation to the near-end frame that carries its
 * echo. Getting it wrong is silent: the APM returns 0 from every call either way. */
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

/* Runs 10 s of a -6 dB echo path delayed by 30 ms and accumulates energies over the last second.
 *
 * A zero-delay echo path never converges (AEC3 models a real acoustic path), and nothing converges
 * within 5 s; both would leave only AEC3's uniform initial attenuation to measure.
 * set_stream_delay_ms stays 0: AEC3 estimates the delay itself. */
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
    /* Out-of-range NS levels are clamped, not rejected: the level comes from a user preference. */
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
     * process_render for the frame about to be played, then process_capture for the frame just
     * recorded. Getting that wrong still returns 0 from every call. Wrong wirings leave the echo
     * almost untouched (about -0.5 dB) while the correct one attenuates it by about -22 dB; the
     * thresholds below have wide margins.
     *
     * kCaptureFirst is deliberately NOT asserted as broken: it advances the reference by one
     * 10 ms frame, and with a 30 ms echo path the reference still precedes the echo, which AEC3's
     * delay estimator is built to handle. Asserting failure there would break as soon as the
     * estimator improved. */
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
