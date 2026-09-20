/* Host test for ../jni_speex.cpp, driven through the stand-in JNIEnv in jni_env_stub.h.
 *
 * SD(ctlInt) had the same defect the speexdsp bridge was fixed for: it takes a request number
 * straight from Kotlin and hands speex_decoder_ctl the address of a four-byte spx_int32_t on its
 * own stack frame. The request decides what the callee does with that address, and for several of
 * the requests libspeex implements it is not "read or write four bytes":
 *
 *   SPEEX_GET_STACK           (106)  *((char**)ptr) = st->stack
 *       -- eight bytes into a four-byte stack object on arm64-v8a and x86_64.
 *   SPEEX_SET_INNOVATION_SAVE (104)  st->innov_save = (spx_word16_t*)ptr
 *       -- keeps the stack address and writes a whole subframe through it on every later
 *          speex_decode, long after this frame is gone.
 *   SPEEX_SET_HANDLER          (20)
 *   SPEEX_SET_USER_HANDLER     (22)  SpeexCallback *c = (SpeexCallback*)ptr; then reads c->func
 *          and c->data and stores them -- a function pointer taken out of a four-byte int.
 *   SPEEX_GET_PI_GAIN         (100)
 *   SPEEX_GET_EXC             (101)  write one word per subframe into the same four bytes.
 *
 * The Kotlin object names exactly one ctl request, SPEEX_SET_ENH, so that is the allow list. "Only
 * SET_ENH is reachable from Kotlin" is not on its own a reason to pass the rest through: the
 * number is a plain Int on a public interface, and being wrong in the allowing direction is a
 * stack smash rather than an error code.
 *
 * The refusals are pinned by the return value, and the requests below are ones libspeex really
 * implements, so with the allow list deleted they answer 0 and these assertions fail. The harmless
 * ones come first on purpose: they make the mutant report four-line failures rather than crash
 * before it can print anything.
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
JNIEXPORT jlong JNICALL Java_se_lublin_humla_audio_native_SpeexDecoderNative_create(JNIEnv*, jobject, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexDecoderNative_ctlInt(JNIEnv*, jobject, jlong, jint, jint);
JNIEXPORT jint JNICALL Java_se_lublin_humla_audio_native_SpeexDecoderNative_decodeFloat(JNIEnv*, jobject, jlong, jbyteArray, jint, jfloatArray);
JNIEXPORT void JNICALL Java_se_lublin_humla_audio_native_SpeexDecoderNative_destroy(JNIEnv*, jobject, jlong);
}

#define SD_CREATE Java_se_lublin_humla_audio_native_SpeexDecoderNative_create
#define SD_CTL Java_se_lublin_humla_audio_native_SpeexDecoderNative_ctlInt
#define SD_DECODE Java_se_lublin_humla_audio_native_SpeexDecoderNative_decodeFloat
#define SD_DESTROY Java_se_lublin_humla_audio_native_SpeexDecoderNative_destroy

enum { kModeIdUwb = 2, kUwbFrameSize = 640 };

static void test_decoder_ctl(Env& env) {
    JNIEnv* e = env.get();
    jlong st = SD_CREATE(e, nullptr, kModeIdUwb);
    CHECK(st != 0, "speex ultra-wideband decoder init succeeds");
    if (st == 0) return;

    CHECK(SD_CTL(e, nullptr, st, 0 /* SPEEX_SET_ENH */, 1) == 0, "SET_ENH is allowed");
    CHECK(SD_CTL(e, nullptr, st, 0 /* SPEEX_SET_ENH */, 0) == 0, "SET_ENH is allowed with 0 too");

    /* Implemented by libspeex, int-typed, and never named by SpeexDecoderNative. Harmless if they
     * were let through, which is exactly why they are the ones that say so out loud. */
    const jint implementedButNotOurs[] = {1 /* GET_ENH */, 3 /* GET_FRAME_SIZE */,
                                          25 /* GET_SAMPLING_RATE */, 37 /* GET_SUBMODE_ENCODING */,
                                          39 /* GET_LOOKAHEAD */, 45 /* GET_HIGHPASS */};
    for (jint request : implementedButNotOurs)
        CHECK(SD_CTL(e, nullptr, st, request, 0) == -1,
              "a request libspeex implements but Kotlin never names is refused");

    /* The ones that would treat the four-byte stack slot as something else. */
    const jint pointerTyped[] = {20 /* SET_HANDLER */, 22 /* SET_USER_HANDLER */,
                                 100 /* GET_PI_GAIN */, 101 /* GET_EXC */,
                                 104 /* SET_INNOVATION_SAVE */, 106 /* GET_STACK */};
    for (jint request : pointerTyped)
        CHECK(SD_CTL(e, nullptr, st, request, 0x5a5a5a5a) == -1,
              "a pointer-typed request is refused before it reaches libspeex");

    const jint notRequests[] = {-1, 12345, 0x7fffffff};
    for (jint request : notRequests)
        CHECK(SD_CTL(e, nullptr, st, request, 0) == -1, "a number that is no request is refused");

    /* SET_INNOVATION_SAVE (104) above does not write through the pointer, it KEEPS it: every later
     * speex_decode writes a subframe of samples to st->innov_save. Had the ctl gone through, the
     * decode below would write through the address of a spx_int32_t whose frame is long gone.
     * Reaching the line after it is the assertion. */
    {
        Array<jfloat> out(kUwbFrameSize);
        SD_DECODE(e, nullptr, st, nullptr, 0, out.as<jfloatArray>());
        CHECK(true, "decoding after the refused ctls does not write through a stack address");
        CHECK(jnistub::outstanding_copies() == 0, "decodeFloat releases what it takes");
    }

    SD_DESTROY(e, nullptr, st);
}

/* ctlInt used to write fromHandle<SpeexDecoderHandle>(handle)->state with no check at all, so a
 * handle of 0 -- what create() returns on failure, and what a Kotlin field holds before it is
 * assigned -- was a null dereference. The request used here is on the allow list, so the refusal
 * above cannot stand in for this check. (decodeFloat and destroy have the same hole and are not
 * fixed here; they are on the stream's open list.) */
static void test_decoder_ctl_null_handle(Env& env) {
    JNIEnv* e = env.get();
    CHECK(SD_CTL(e, nullptr, 0, 0 /* SPEEX_SET_ENH */, 1) == -1,
          "ctlInt with a null handle reports an error instead of dereferencing it");
}

int main() {
    Env env;
    test_decoder_ctl(env);
    test_decoder_ctl_null_handle(env);
    CHECK(jnistub::outstanding_copies() == 0, "no array copy is outstanding at the end of the run");
    std::printf("%s\n", failures ? "FAILED" : "OK");
    return failures ? 1 : 0;
}
