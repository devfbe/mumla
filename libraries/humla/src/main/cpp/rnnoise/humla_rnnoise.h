#ifndef HUMLA_RNNOISE_H
#define HUMLA_RNNOISE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define HUMLA_RNNOISE_FRAME_SIZE 480
typedef struct humla_rnnoise humla_rnnoise;
/* Allocates a denoiser using the embedded model. Returns NULL on failure. */
humla_rnnoise *humla_rnnoise_create(void);
/* Denoises one 10 ms 48 kHz mono frame in place and returns the VAD probability [0,1]. */
float humla_rnnoise_process(humla_rnnoise *h, int16_t *frame);
void humla_rnnoise_destroy(humla_rnnoise *h);
#ifdef __cplusplus
}
#endif
#endif
