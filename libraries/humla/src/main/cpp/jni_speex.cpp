#include <jni.h>
#include <algorithm>
#include <speex/speex.h>
#include "jni_handle.h"

#define SD(name) Java_se_lublin_humla_audio_native_SpeexDecoderNative_##name

namespace {
struct SpeexDecoderHandle {
    void* state;
    SpeexBits bits;
    int frameSize;
    float* frame;
};

/* SD(ctlInt) hands speex_decoder_ctl the address of a four-byte spx_int32_t on its own stack
 * frame, and the request number that decides what the callee does with that address arrives from
 * Kotlin. For several of the requests libspeex implements it is not "read or write four bytes":
 *
 *   SPEEX_GET_STACK           (106)  *((char**)ptr) = st->stack
 *       -- eight bytes into a four-byte stack object on arm64-v8a and x86_64.
 *   SPEEX_SET_INNOVATION_SAVE (104)  st->innov_save = (spx_word16_t*)ptr
 *       -- the stack address is KEPT, and every later speex_decode writes a subframe of samples
 *          through it, long after this frame is gone.
 *   SPEEX_SET_HANDLER          (20)
 *   SPEEX_SET_USER_HANDLER     (22)  SpeexCallback *c = (SpeexCallback*)ptr, then c->callback_id
 *          indexes a 16-element array and c->func is stored and called later.
 *   SPEEX_GET_PI_GAIN         (100)
 *   SPEEX_GET_EXC             (101)  one word per subframe into the same four bytes.
 *
 * An allow list rather than a list of the dangerous ones, for the same reason as in
 * jni_speexdsp.cpp: the argument's type is a property of each request inside libspeex, a version
 * bump can add another pointer-typed one, and being wrong in the allowing direction is a stack
 * smash. The list is the requests SpeexDecoderNative names as constants -- today exactly
 * SPEEX_SET_ENH -- restricted to those that really treat ptr as a single spx_int32_t and touch
 * nothing outside the decoder state. "Nothing else is reachable from Kotlin anyway" is not a
 * reason to pass the rest through: the request is a plain Int on a public interface, so reaching
 * one takes no native change at all.
 *
 * Adding one means checking in nb_celp.c / sb_celp.c that the new request really reads or writes
 * a single spx_int32_t, and adding it here too.
 *
 * The refusal is -1, which is also what speex_decoder_ctl answers for a request it does not know
 * (nb_celp.c:1253, sb_celp.c). The two are deliberately not distinguished: this bridge has no
 * error channel of its own, SpeexDecoderApi.ctlInt is documented as returning libspeex's own
 * status, and "the bridge refused it" and "libspeex has no such request" call for the same thing
 * from the caller. tests/test_jni_speex.cpp pins the refusals on requests libspeex DOES
 * implement, so the assertions still tell the two apart. */
bool decoderRequestAllowed(jint request) {
    switch (request) {
        case SPEEX_SET_ENH:  // 0
            return true;
        default:
            return false;
    }
}

}  // namespace

extern "C" {

// Returns 0 on failure; the Kotlin wrapper turns that into a NativeAudioException. Without the
// frame-size check a failed query would leave frameSize at 0, and the next speex_decode would
// write a whole mode frame into a zero-length buffer - the very overrun this file exists to stop.
JNIEXPORT jlong JNICALL SD(create)(JNIEnv*, jobject, jint modeId) {
    const SpeexMode* speexMode = speex_lib_get_mode(modeId);
    if (speexMode == nullptr) return 0;
    auto* h = new SpeexDecoderHandle();
    h->state = speex_decoder_init(speexMode);
    if (h->state == nullptr) {
        delete h;
        return 0;
    }
    speex_bits_init(&h->bits);
    spx_int32_t frameSize = 0;
    speex_decoder_ctl(h->state, SPEEX_GET_FRAME_SIZE, &frameSize);
    if (frameSize <= 0) {
        speex_decoder_destroy(h->state);
        speex_bits_destroy(&h->bits);
        delete h;
        return 0;
    }
    h->frameSize = frameSize;
    h->frame = new float[frameSize];
    return toHandle(h);
}

JNIEXPORT jint JNICALL SD(ctlInt)(JNIEnv*, jobject, jlong handle, jint request, jint value) {
    auto* h = fromHandle<SpeexDecoderHandle>(handle);
    if (h == nullptr) return -1;
    if (!decoderRequestAllowed(request)) return -1;
    spx_int32_t v = value;
    return speex_decoder_ctl(h->state, request, &v);
}

JNIEXPORT jint JNICALL SD(decodeFloat)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jfloatArray out) {
    auto* h = fromHandle<SpeexDecoderHandle>(handle);
    if (data != nullptr) {
        jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
        speex_bits_read_from(&h->bits, reinterpret_cast<const char*>(dataPtr), len);
        env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
    } else {
        speex_bits_read_from(&h->bits, nullptr, 0);
    }
    int result = speex_decode(h->state, &h->bits, h->frame);
    jsize n = std::min(env->GetArrayLength(out), static_cast<jsize>(h->frameSize));
    env->SetFloatArrayRegion(out, 0, n, h->frame);
    return result;
}

JNIEXPORT void JNICALL SD(destroy)(JNIEnv*, jobject, jlong handle) {
    auto* h = fromHandle<SpeexDecoderHandle>(handle);
    speex_decoder_destroy(h->state);
    speex_bits_destroy(&h->bits);
    delete[] h->frame;
    delete h;
}

} // extern "C"
