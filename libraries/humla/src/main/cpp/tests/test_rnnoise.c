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

/* Speech-like input: a 140 Hz harmonic series gated on and off at 2 Hz ("syllables" of 250 ms),
   plus white noise about 10 dB below the voiced parts. After warm-up, the voiced frames must keep
   most of their energy, the noise-only frames must be attenuated, and the VAD must separate them. */
static void speech_in_noise(void) {
    humla_rnnoise *h = humla_rnnoise_create();
    CHECK(h != NULL, "create returns a state for the speech test");
    if (!h) return;
    const double pi = 3.14159265358979323846;
    const int frames = 400, warmup = 100;
    double voiced_in = 0, voiced_out = 0, noise_in = 0, noise_out = 0;
    double vad_voiced = 0, vad_noise = 0;
    int n_voiced = 0, n_noise = 0, vad_in_range = 1;
    seed = 4242;
    int16_t frame[HUMLA_RNNOISE_FRAME_SIZE];
    double clean[HUMLA_RNNOISE_FRAME_SIZE];
    for (int n = 0; n < frames; n++) {
        /* 25 frames voiced, 25 frames pause; the edges are skipped when measuring. */
        int phase = n % 50;
        int voiced = phase < 25;
        for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) {
            double t = (double)(n * HUMLA_RNNOISE_FRAME_SIZE + i) / 48000.0;
            double s = 0;
            if (voiced) for (int k = 1; k <= 20; k++) s += sin(2 * pi * 140.0 * k * t) / k;
            clean[i] = 3000.0 * s;
            frame[i] = (int16_t)lrint(clean[i] + noise_sample() / 4);
        }
        double in_e = energy(frame, HUMLA_RNNOISE_FRAME_SIZE);
        float p = humla_rnnoise_process(h, frame);
        if (!(p >= 0.0f && p <= 1.0f)) vad_in_range = 0;
        if (n < warmup) continue;
        if (voiced && phase >= 5 && phase < 22) {
            double ce = 0;
            for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) ce += clean[i] * clean[i];
            voiced_in += ce / HUMLA_RNNOISE_FRAME_SIZE;
            voiced_out += energy(frame, HUMLA_RNNOISE_FRAME_SIZE);
            vad_voiced += p; n_voiced++;
        } else if (!voiced && phase >= 30) {
            noise_in += in_e;
            noise_out += energy(frame, HUMLA_RNNOISE_FRAME_SIZE);
            vad_noise += p; n_noise++;
        }
    }
    humla_rnnoise_destroy(h);
    double voiced_db = 10.0 * log10(voiced_out / voiced_in);
    double noise_db = 10.0 * log10(noise_in / noise_out);
    vad_voiced /= n_voiced; vad_noise /= n_noise;
    printf("speech: voiced level %+.1f dB, pause noise attenuation %.1f dB, VAD %.2f vs %.2f\n",
           voiced_db, noise_db, vad_voiced, vad_noise);
    CHECK(vad_in_range, "VAD probability stays within [0,1] on speech in noise");
    CHECK(voiced_db > -6.0 && voiced_db < 3.0, "voiced frames keep their level within -6..+3 dB");
    CHECK(noise_db > 10.0, "noise in the pauses is attenuated by at least 10 dB");
    CHECK(vad_voiced > vad_noise + 0.3, "VAD is clearly higher for voiced frames than for pauses");
}

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
    /* RNNoise gates stationary white noise almost completely (~80 dB on the host). 20 dB fails
       a badly degraded model while leaving headroom for architecture-dependent DSP. */
    printf("noise attenuation: %.1f dB\n", 10.0 * log10(in_e / out_e));
    CHECK(out_e * 100.0 < in_e, "stationary white noise is attenuated by at least 20 dB");

    humla_rnnoise_destroy(h);

    speech_in_noise();

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

    /* Repeated teardown catches a double free or corrupted allocator state, and in the sanitized
       entry any per-cycle leak. The uninitialised FILE* read documented in
       humla_rnnoise_destroy is only caught reliably by the sanitized build. */
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
