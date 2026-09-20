#include "humla_rnnoise.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { fprintf(stderr, "FAIL: %s\n", msg); failures++; } } while (0)

static double energy(const int16_t *f, int n) {
    double e = 0; for (int i = 0; i < n; i++) e += (double)f[i] * f[i]; return e / n;
}

/* Deterministic LCG so the test is reproducible. */
static uint32_t seed = 12345;
static int16_t noise_sample(void) { seed = seed * 1664525u + 1013904223u; return (int16_t)((seed >> 16) % 8000) - 4000; }

int main(void) {
    humla_rnnoise *h = humla_rnnoise_create();
    CHECK(h != NULL, "create returns a state (embedded model loads)");
    if (!h) return 1;
    CHECK(HUMLA_RNNOISE_FRAME_SIZE == 480, "frame size is 480 samples (10 ms @ 48 kHz)");

    int16_t frame[HUMLA_RNNOISE_FRAME_SIZE];
    memset(frame, 0, sizeof frame);
    float p = humla_rnnoise_process(h, frame);
    CHECK(p >= 0.0f && p <= 1.0f, "probability is within [0,1]");
    CHECK(p < 0.5f, "silence is not classified as voice");
    CHECK(energy(frame, HUMLA_RNNOISE_FRAME_SIZE) == 0.0, "silence in gives silence out");

    /* 1 s of white noise: RNNoise must attenuate it. Compare the last 50 frames (after warm-up). */
    double in_e = 0, out_e = 0;
    for (int n = 0; n < 100; n++) {
        for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) frame[i] = noise_sample();
        double ie = energy(frame, HUMLA_RNNOISE_FRAME_SIZE);
        humla_rnnoise_process(h, frame);
        if (n >= 50) { in_e += ie; out_e += energy(frame, HUMLA_RNNOISE_FRAME_SIZE); }
    }
    /* Measured on the host: 82.9 dB (input energy 2.74e8, output 1.4) -- RNNoise gates
       stationary white noise almost completely. 20 dB is the threshold so that this fails on a
       badly degraded model and not only on a no-op, while still leaving ~63 dB of headroom for
       architecture-dependent DSP differences. */
    printf("noise attenuation: %.1f dB\n", 10.0 * log10(in_e / out_e));
    CHECK(out_e * 100.0 < in_e, "stationary white noise is attenuated by at least 20 dB");

    humla_rnnoise_destroy(h);

    /* Every state shares the one static blob (rnnoise_model_from_buffer keeps a pointer into
       it), and the Kotlin layer runs the capture pipeline and the settings loopback test at
       the same time. Two live states fed identical input must stay bit-identical. */
    humla_rnnoise *a = humla_rnnoise_create();
    humla_rnnoise *b = humla_rnnoise_create();
    CHECK(a != NULL && b != NULL, "two states can be live at once on the shared blob");
    if (a && b) {
        int16_t fa[HUMLA_RNNOISE_FRAME_SIZE], fb[HUMLA_RNNOISE_FRAME_SIZE];
        int identical = 1;
        seed = 999;
        for (int n = 0; n < 20; n++) {
            for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) fa[i] = fb[i] = noise_sample();
            float pa = humla_rnnoise_process(a, fa);
            float pb = humla_rnnoise_process(b, fb);
            if (pa != pb || memcmp(fa, fb, sizeof fa) != 0) identical = 0;
        }
        CHECK(identical, "two live states give identical output (no shared mutable state)");
    }
    humla_rnnoise_destroy(a);
    humla_rnnoise_destroy(b);

    /* Repeated teardown. What this loop actually catches is a hard double free or a corrupted
       allocator state, which glibc aborts on immediately, plus -- in the sanitized ctest entry
       (see tests/CMakeLists.txt) -- any per-cycle leak of the model or the denoise state.
       It does NOT catch the rnnoise_model_free() trap documented in humla_rnnoise_destroy:
       that is a read of an uninitialised FILE*, and a plain build leaves it to whatever the
       allocator happened to leave behind. Mutation-tested: a wrapper changed to call
       rnnoise_model_free() passes this loop 60 times out of 60 in a plain build, and fails 5
       times out of 5 under -fsanitize=address, which fills fresh heap with 0xbe and so turns
       the garbage FILE* into a deterministic SEGV inside fclose() -- at the first destroy
       above, without needing this loop at all. The loop is kept for the double-free and leak
       cases; the sanitized entry is what guards the uninitialised read. */
    for (int n = 0; n < 200; n++) {
        humla_rnnoise *cycle = humla_rnnoise_create();
        CHECK(cycle != NULL, "create succeeds on every cycle");
        if (!cycle) break;
        int16_t f[HUMLA_RNNOISE_FRAME_SIZE];
        memset(f, 0, sizeof f);
        humla_rnnoise_process(cycle, f);
        humla_rnnoise_destroy(cycle);
    }
    humla_rnnoise_destroy(NULL);   /* must be a no-op */

    printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
