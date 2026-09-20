/* Reviewer's independent M-7 measurement against the real APM.
 * Reproduces (or refutes) the task-7 numbers now binding in spec 4.1.
 * Not part of the build; lives in the reviewer's scratchpad only. */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "humla_apm.h"

#define SR 48000
#define N 480 /* 10 ms */

/* ---- deterministic PRNG so runs are repeatable ---- */
static uint64_t rng_s = 0x2026091900000007ull;
static double urand(void) { /* uniform [-1,1) */
    rng_s ^= rng_s << 13; rng_s ^= rng_s >> 7; rng_s ^= rng_s << 17;
    return (double)((int64_t)(rng_s >> 11)) / (double)(1ll << 52) - 1.0;
}

/* ---- signal generators: fill `out` with one 10 ms frame, unit-ish scale ---- */
typedef struct { double lp; double ph_hum; double ph_pulse; double r[3][2]; double t; } gen_state;

static void gen_lowpass_noise(gen_state *g, double *out) {
    for (int i = 0; i < N; i++) { g->lp = 0.97 * g->lp + 0.03 * urand(); out[i] = g->lp * 8.0; }
}
static void gen_white_noise(gen_state *g, double *out) {
    (void)g; for (int i = 0; i < N; i++) out[i] = urand();
}
static void gen_hum_hiss(gen_state *g, double *out) {
    for (int i = 0; i < N; i++) {
        g->ph_hum += 2.0 * M_PI * 100.0 / SR; if (g->ph_hum > 2*M_PI) g->ph_hum -= 2*M_PI;
        out[i] = 0.9 * sin(g->ph_hum) + 0.1 * urand();
    }
}
/* 120 Hz pulse train through three formant resonators, 4 Hz syllable envelope. */
static void gen_speech(gen_state *g, double *out) {
    static const double F[3] = {700.0, 1220.0, 2600.0};
    static const double BW[3] = {90.0, 110.0, 170.0};
    for (int i = 0; i < N; i++) {
        double x = 0.0;
        g->ph_pulse += 120.0 / SR;
        if (g->ph_pulse >= 1.0) { g->ph_pulse -= 1.0; x = 1.0; }
        double y = 0.0;
        for (int k = 0; k < 3; k++) {
            double r = exp(-M_PI * BW[k] / SR);
            double c = 2.0 * r * cos(2.0 * M_PI * F[k] / SR);
            double d = -r * r;
            double v = x + c * g->r[k][0] + d * g->r[k][1];
            g->r[k][1] = g->r[k][0]; g->r[k][0] = v;
            y += v * (k == 0 ? 1.0 : (k == 1 ? 0.55 : 0.3));
        }
        /* 4 Hz syllable envelope, never fully closing (a real talker's gaps carry room noise) */
        double env = 0.55 + 0.45 * sin(2.0 * M_PI * 4.0 * g->t);
        g->t += 1.0 / SR;
        out[i] = y * env * 0.02;
    }
}

typedef void (*genfn)(gen_state *, double *);

static double rms_of(const double *x, int n) {
    double s = 0; for (int i = 0; i < n; i++) s += x[i]*x[i]; return sqrt(s / n);
}

/* Run one configuration and return the mean processed level over `measure` frames
 * after `settle` frames of warm-up. target_dbfs scales the generator's output. */
static double run_point(genfn gen, double target_dbfs, int ns_on, int agc_on,
                        int settle, int measure, double *out_input_dbfs) {
    humla_apm_config cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.echo_cancellation = 1;      /* FOR_ECHO_CANCELLATION */
    cfg.noise_suppression = ns_on;
    cfg.noise_suppression_level = 2;
    cfg.gain_control = agc_on;      /* AGC2 adaptive digital */
    cfg.high_pass = 1;
    humla_apm *h = humla_apm_create(SR, &cfg);
    if (!h) { fprintf(stderr, "create failed\n"); exit(1); }

    gen_state g; memset(&g, 0, sizeof g);
    rng_s = 0x2026091900000007ull; /* same noise for every point */

    /* calibrate the generator's natural rms over a few frames first */
    gen_state gc; memset(&gc, 0, sizeof gc);
    uint64_t save = rng_s;
    double buf[N]; double acc = 0; int cn = 0;
    for (int f = 0; f < 50; f++) { gen(&gc, buf); acc += rms_of(buf, N); cn++; }
    double natural = acc / cn;
    rng_s = save;

    double target_rms = 32768.0 * pow(10.0, target_dbfs / 20.0);
    double scale = target_rms / (natural * 32768.0) * 32768.0 / 32768.0;
    scale = target_rms / (natural * 32768.0);

    int16_t frame[N], render[N];
    memset(render, 0, sizeof render);
    double lvl_sum = 0; int lvl_n = 0;
    double in_sum = 0; int in_n = 0;

    for (int f = 0; f < settle + measure; f++) {
        gen(&g, buf);
        double in_acc = 0;
        for (int i = 0; i < N; i++) {
            double v = buf[i] * 32768.0 * scale;
            if (v > 32767.0) v = 32767.0;
            if (v < -32768.0) v = -32768.0;
            frame[i] = (int16_t)lrint(v);
            in_acc += (double)frame[i] * (double)frame[i];
        }
        if (f >= settle) {
            double r = sqrt(in_acc / N);
            if (r >= 1.0) { in_sum += 20.0 * log10(r / 32768.0); in_n++; }
        }
        memset(render, 0, sizeof render);
        humla_apm_process_render(h, render);
        int err = humla_apm_process_capture(h, frame);
        if (err != 0) { fprintf(stderr, "process error %d\n", err); exit(1); }
        if (f >= settle) { lvl_sum += humla_apm_last_capture_level_dbfs(h); lvl_n++; }
    }
    humla_apm_destroy(h);
    if (out_input_dbfs) *out_input_dbfs = in_n ? in_sum / in_n : -100.0;
    return lvl_sum / lvl_n;
}

static float from_dbfs(double lvl, double silence, double full) {
    double p = (lvl - silence) / (full - silence);
    if (p < 0) p = 0; if (p > 1) p = 1;
    return (float)p;
}

int main(void) {
    const int SETTLE = 500;   /* 5 s */
    const int MEASURE = 800;  /* 800 frames per point */

    printf("=== 1. non-speech processed level vs input level (NS off = shipped config) ===\n");
    printf("%-18s %8s %10s %10s %10s\n", "character", "in_dBFS", "NSoff", "NSon", "diff");
    struct { const char *name; genfn f; } chars[] = {
        {"lowpass noise", gen_lowpass_noise},
        {"white noise",   gen_white_noise},
        {"hum+hiss",      gen_hum_hiss},
    };
    double levels[] = {-70, -60, -55, -50, -45, -40, -35, -30};
    for (size_t c = 0; c < sizeof chars / sizeof chars[0]; c++) {
        for (size_t l = 0; l < sizeof levels / sizeof levels[0]; l++) {
            double in_off, in_on;
            double off = run_point(chars[c].f, levels[l], 0, 1, SETTLE, MEASURE, &in_off);
            double on  = run_point(chars[c].f, levels[l], 1, 1, SETTLE, MEASURE, &in_on);
            printf("%-18s %8.2f %10.2f %10.2f %+10.2f\n",
                   chars[c].name, in_off, off, on, off - on);
        }
        printf("\n");
    }

    printf("=== 2. AGC2 removed, to decompose the cap from the suppressor ===\n");
    printf("%-18s %8s %10s %10s %10s\n", "character", "in_dBFS", "NSoff", "NSon", "diff");
    for (size_t l = 0; l < sizeof levels / sizeof levels[0]; l++) {
        double in_off;
        double off = run_point(gen_lowpass_noise, levels[l], 0, 0, SETTLE, MEASURE, &in_off);
        double on  = run_point(gen_lowpass_noise, levels[l], 1, 0, SETTLE, MEASURE, NULL);
        printf("%-18s %8.2f %10.2f %10.2f %+10.2f\n", "lowpass, AGC2 off", in_off, off, on, off - on);
    }
    printf("\n");

    printf("=== 3. speech-shaped signal: level and LevelToProbability ===\n");
    printf("%-10s %8s %10s %10s | %8s %8s | windows p(NSoff)\n",
           "", "in_dBFS", "NSoff", "NSon", "p_off", "p_on");
    double slevels[] = {-50, -45, -40, -35, -30};
    for (size_t l = 0; l < sizeof slevels / sizeof slevels[0]; l++) {
        double in_off;
        double off = run_point(gen_speech, slevels[l], 0, 1, SETTLE, MEASURE, &in_off);
        double on  = run_point(gen_speech, slevels[l], 1, 1, SETTLE, MEASURE, NULL);
        printf("%-10s %8.2f %10.2f %10.2f | %8.3f %8.3f | -50/-20=%.3f  -45/-20=%.3f  -45/-25=%.3f\n",
               "speech", in_off, off, on,
               from_dbfs(off, -50, -20), from_dbfs(on, -50, -20),
               from_dbfs(off, -50, -20), from_dbfs(off, -45, -20), from_dbfs(off, -45, -25));
    }
    printf("\n");

    printf("=== 4. the floor itself, and what each window reads it as ===\n");
    double in_f;
    double floor_lvl = run_point(gen_lowpass_noise, -55, 0, 1, SETTLE, MEASURE, &in_f);
    printf("measured non-speech floor (NS off, AGC2 on): %.2f dBFS\n", floor_lvl);
    printf("  -50/-20 window reads it as %.4f\n", from_dbfs(floor_lvl, -50, -20));
    printf("  -45/-20 window reads it as %.4f\n", from_dbfs(floor_lvl, -45, -20));
    printf("  -45/-25 window reads it as %.4f\n", from_dbfs(floor_lvl, -45, -25));
    return 0;
}
