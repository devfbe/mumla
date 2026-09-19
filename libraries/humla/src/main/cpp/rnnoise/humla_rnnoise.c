#include "humla_rnnoise.h"
#include <rnnoise.h>
#include <math.h>
#include <stdlib.h>

extern const unsigned char humla_rnnoise_model_blob[];
extern const unsigned int humla_rnnoise_model_blob_size;

struct humla_rnnoise {
    RNNModel *model;
    DenoiseState *st;
    float in[HUMLA_RNNOISE_FRAME_SIZE];
    float out[HUMLA_RNNOISE_FRAME_SIZE];
};

humla_rnnoise *humla_rnnoise_create(void) {
    if (rnnoise_get_frame_size() != HUMLA_RNNOISE_FRAME_SIZE) return NULL;
    humla_rnnoise *h = calloc(1, sizeof *h);
    if (!h) return NULL;
    h->model = rnnoise_model_from_buffer(humla_rnnoise_model_blob, (int)humla_rnnoise_model_blob_size);
    if (!h->model) { free(h); return NULL; }
    h->st = rnnoise_create(h->model);
    /* NOTE: free(), never rnnoise_model_free() — see humla_rnnoise_destroy. */
    if (!h->st) { free(h->model); free(h); return NULL; }
    return h;
}

float humla_rnnoise_process(humla_rnnoise *h, int16_t *frame) {
    for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) h->in[i] = (float)frame[i];
    float p = rnnoise_process_frame(h->st, h->out, h->in);
    for (int i = 0; i < HUMLA_RNNOISE_FRAME_SIZE; i++) {
        float v = h->out[i];
        if (v > 32767.f) v = 32767.f; else if (v < -32768.f) v = -32768.f;
        frame[i] = (int16_t)lrintf(v);
    }
    if (p < 0.f) p = 0.f; else if (p > 1.f) p = 1.f;
    return p;
}

void humla_rnnoise_destroy(humla_rnnoise *h) {
    if (!h) return;
    rnnoise_destroy(h->st);
    /* rnnoise_model_free() would fclose(model->file), which rnnoise_model_from_buffer never
       initialises (upstream src/denoise.c:235-242 vs :271-275). The struct is a plain malloc
       with blob == NULL and rnnoise_init keeps no pointer to it, so free() is the whole job. */
    free(h->model);
    free(h);
}
