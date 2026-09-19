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
}

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
    spx_int32_t v = value;
    return speex_decoder_ctl(fromHandle<SpeexDecoderHandle>(handle)->state, request, &v);
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
