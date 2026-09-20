/*
 * JNI bridge for libspeexdsp: the resampler, the jitter buffer and the preprocessor.
 *
 * Every entry point here has the same hazard. libspeexdsp takes the number of samples or bytes to
 * touch from somewhere other than the buffer -- speex_preprocess_run from the frame size the
 * state was created with, speex_resampler_process_int and jitter_buffer_put from counts the
 * caller supplies separately -- so nothing under this layer can tell whether the Java array is
 * long enough. The binding this file replaced did not check, and at ultra-wideband speex wrote
 * 640 samples into a 480-element array on every frame; that was a crash in the field, not a
 * theoretical one. Each function below therefore reads the array's real length and clamps or
 * refuses. tests/test_jni_speexdsp.cpp reproduces the original overrun and fails without them.
 */
#include <jni.h>
#include <new>
#include <speex/speex_jitter.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include "jni_handle.h"

#define RS(name) Java_se_lublin_humla_audio_native_SpeexResamplerNative_##name
#define JB(name) Java_se_lublin_humla_audio_native_SpeexJitterNative_##name
#define PP(name) Java_se_lublin_humla_audio_native_SpeexPreprocessNative_##name

namespace {

/* The preprocessor writes the frame size it was CREATED with, and libspeexdsp has no ctl to ask
 * it afterwards, so the size is kept beside the state and compared against the array in PP(run).
 * The handle Kotlin holds is this struct, not the SpeexPreprocessState. */
struct PreprocessHandle {
    SpeexPreprocessState* state;
    int frameSize;
};

/* Same shape, same reason. speex_resampler_process_native indexes st->last_sample[],
 * st->samp_frac_num[] and st->mem with the caller's channel_index and never compares it against
 * the channel count the state was created with -- all three are sized for that count, so an index
 * past it reads and writes outside the allocation. libspeexdsp has no ctl to ask a state how many
 * channels it has, so the count is kept beside the state. */
struct ResamplerHandle {
    SpeexResamplerState* state;
    int channels;
};

/* Clamps a caller-supplied element count to what the array can actually hold. */
jint clampToArray(JNIEnv* env, jint count, jarray array) {
    if (count < 0) return 0;
    jsize capacity = env->GetArrayLength(array);
    return count > capacity ? static_cast<jint>(capacity) : count;
}

/* The resampler's error array is optional on the Kotlin side (IntArray?) and, like every other
 * array here, arrives with a length of its own; writeInt() alone would write slot 0 of an empty
 * one, which on a real JVM is a pending ArrayIndexOutOfBoundsException. */
void writeError(JNIEnv* env, jintArray error, jint value) {
    if (error != nullptr && env->GetArrayLength(error) >= 1) writeInt(env, error, value);
}

/* The two ctl entry points below hand jitter_buffer_ctl / speex_preprocess_ctl the address of a
 * four-byte spx_int32_t on their own stack frame. The request number decides what the callee does
 * with that address, it arrives from Kotlin, and for several requests it is not "read or write
 * four bytes":
 *
 *   JITTER_BUFFER_GET_DESTROY_CALLBACK (5)   *(void(**)(void*))ptr = jitter->destroy
 *   SPEEX_PREPROCESS_GET_ECHO_STATE    (25)  *(SpeexEchoState**)ptr = st->echo_state
 *       -- eight bytes into a four-byte stack object on arm64-v8a and x86_64.
 *   JITTER_BUFFER_SET_DESTROY_CALLBACK (4)   jitter->destroy = (void(*)(void*))ptr
 *   SPEEX_PREPROCESS_SET_ECHO_STATE    (24)  st->echo_state = (SpeexEchoState*)ptr
 *       -- the stack address is kept as a pointer and dereferenced, or called, long after this
 *          frame is gone; jitter_buffer_reset() calls jitter->destroy for every queued packet.
 *   SPEEX_PREPROCESS_GET_PSD           (39)
 *   SPEEX_PREPROCESS_GET_NOISE_PSD     (43)  ps_size ints, i.e. a whole frame, into those four
 *                                            bytes.
 *   SPEEX_PREPROCESS_SET_AGC_LEVEL     (6)   reads the caller's int as a float.
 *   JITTER_BUFFER_SET_MAX_LATE_RATE    (10)  divides by the value, so 0 is a SIGFPE.
 *
 * An allow list rather than a list of the dangerous ones: the argument's type is a property of
 * each request inside libspeexdsp, a version bump can add another pointer-typed request, and
 * being wrong in the allowing direction is a stack smash. Both lists are exactly the ctl requests
 * the matching Kotlin object declares as constants (SpeexJitterNative additionally declares five
 * JITTER_BUFFER_* status codes, which are return values, not requests) -- nothing else was ever
 * reachable from Kotlin without also adding a constant there. That on its own is NOT the reason
 * to refuse the rest: the request is a plain Int on a public interface, so reaching one takes no
 * native change at all.
 *
 * The criterion for adding one is about the pointer, not about the whole request: jitter.c /
 * preprocess.c must treat ptr as exactly one spx_int32_t -- read once or written once -- and
 * everything else it touches must live inside the state's own allocation. SPEEX_PREPROCESS_SET_DEREVERB
 * (8) is the entry that makes the difference visible: it reads the single int and then zeroes
 * st->reverb_estimate[0 .. ps_size) (preprocess.c:1103-1107). That loop is inside the
 * preprocessor's own correctly sized array, so it is safe, but it is not "a single spx_int32_t
 * access" and a criterion phrased that way would have excluded an entry the list already has.
 *
 * The two refusals are deliberately spelled differently, and the difference is not cosmetic.
 * JB(ctl) answers JITTER_BUFFER_BAD_ARGUMENT (-2) while jitter_buffer_ctl answers -1 for a
 * request it does not know (jitter.c:833-835), so a caller can tell "the bridge refused this"
 * from "libspeexdsp has no such request". PP(ctlInt) cannot: speex_preprocess_ctl also answers
 * -1, and SpeexPreprocessApi.ctlInt is documented as returning speex's own status, which has no
 * spare value. The asymmetry is left in place rather than invented around, because a code
 * SpeexPreprocessNative made up would be indistinguishable from a future libspeexdsp return
 * value; test_jni_speexdsp.cpp therefore pins the preprocess refusals on requests libspeexdsp
 * does implement, where -1 is only reachable through the allow list.
 */
bool jitterRequestAllowed(jint request) {
    switch (request) {
        case JITTER_BUFFER_SET_MARGIN:            // 0
        case JITTER_BUFFER_GET_MARGIN:            // 1
        case JITTER_BUFFER_GET_AVALIABLE_COUNT:   // 3, spelled that way by libspeexdsp
            return true;
        default:
            return false;
    }
}

bool preprocessRequestAllowed(jint request) {
    switch (request) {
        case SPEEX_PREPROCESS_SET_DENOISE:        // 0
        case SPEEX_PREPROCESS_SET_AGC:            // 2
        case SPEEX_PREPROCESS_SET_VAD:            // 4
        case SPEEX_PREPROCESS_SET_DEREVERB:       // 8
        case SPEEX_PREPROCESS_SET_PROB_START:     // 14
        case SPEEX_PREPROCESS_GET_PROB_START:     // 15
        case SPEEX_PREPROCESS_SET_NOISE_SUPPRESS: // 18
        case SPEEX_PREPROCESS_GET_PROB:           // 45
        case SPEEX_PREPROCESS_SET_AGC_TARGET:     // 46
            return true;
        default:
            return false;
    }
}

}  // namespace

extern "C" {

// ---- resampler ----

JNIEXPORT jlong JNICALL RS(init)(JNIEnv* env, jobject, jint channels, jint inRate, jint outRate, jint quality, jintArray error) {
    // A non-positive channel count is refused here rather than passed on: speex_resampler_init
    // would allocate the per-channel arrays for it and every processInt would then be out of
    // range, whatever channel index the caller used.
    if (channels <= 0) {
        writeError(env, error, RESAMPLER_ERR_INVALID_ARG);
        return 0;
    }
    int err = 0;
    SpeexResamplerState* st = speex_resampler_init(channels, inRate, outRate, quality, &err);
    writeError(env, error, err);
    if (st == nullptr) return 0;
    // This failure path survives its own removal and cannot be pinned from here: jni_env_stub.h
    // can make Get*ArrayElements fail, but nothing in the test setup can make operator new fail,
    // so both the destroy and the error code below are unreachable in a test. Kept because the
    // alternative is leaking the SpeexResamplerState and returning 0 with err = 0, which reads as
    // success. Pinning it would need an allocation hook in the test binary.
    auto* h = new (std::nothrow) ResamplerHandle{st, channels};
    if (h == nullptr) {
        speex_resampler_destroy(st);
        writeError(env, error, RESAMPLER_ERR_ALLOC_FAILED);
        return 0;
    }
    return toHandle(h);
}

JNIEXPORT jint JNICALL RS(processInt)(JNIEnv* env, jobject, jlong state, jint channelIndex, jshortArray input, jintArray inLen, jshortArray out, jintArray outLen) {
    auto* h = fromHandle<ResamplerHandle>(state);
    if (h == nullptr || input == nullptr || out == nullptr || inLen == nullptr || outLen == nullptr)
        return RESAMPLER_ERR_INVALID_ARG;
    if (env->GetArrayLength(inLen) < 1 || env->GetArrayLength(outLen) < 1)
        return RESAMPLER_ERR_INVALID_ARG;
    // channelIndex is an index into three per-channel arrays that speex sized for the channel
    // count this state was created with, and speex_resampler_process_native compares it against
    // nothing. Out of range is a heap read and write outside those allocations, not an error
    // code, so it has to be refused here -- the only place that can see both numbers.
    if (channelIndex < 0 || channelIndex >= h->channels) return RESAMPLER_ERR_INVALID_ARG;
    jint inCount = 0, outCount = 0;
    env->GetIntArrayRegion(inLen, 0, 1, &inCount);
    env->GetIntArrayRegion(outLen, 0, 1, &outCount);
    // The two counts arrive in their own int[] and say nothing about how long the sample arrays
    // are: speex reads inCount samples from input and writes up to outCount into out, so a count
    // larger than its array is an out-of-bounds access rather than an error code. A negative
    // count would become an enormous spx_uint32_t two lines further down.
    inCount = clampToArray(env, inCount, input);
    outCount = clampToArray(env, outCount, out);
    spx_uint32_t in = static_cast<spx_uint32_t>(inCount);
    spx_uint32_t outN = static_cast<spx_uint32_t>(outCount);
    jshort* inPtr = env->GetShortArrayElements(input, nullptr);
    if (inPtr == nullptr) return RESAMPLER_ERR_ALLOC_FAILED;
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
        return RESAMPLER_ERR_ALLOC_FAILED;
    }
    int result = speex_resampler_process_int(h->state, channelIndex, inPtr, &in, outPtr, &outN);
    env->ReleaseShortArrayElements(out, outPtr, 0);
    env->ReleaseShortArrayElements(input, inPtr, JNI_ABORT);
    writeInt(env, inLen, static_cast<jint>(in));
    writeInt(env, outLen, static_cast<jint>(outN));
    return result;
}

JNIEXPORT void JNICALL RS(destroy)(JNIEnv*, jobject, jlong state) {
    auto* h = fromHandle<ResamplerHandle>(state);
    if (h == nullptr) return;
    speex_resampler_destroy(h->state);
    delete h;
}

// ---- jitter buffer ----

JNIEXPORT jlong JNICALL JB(init)(JNIEnv*, jobject, jint stepSize) {
    return toHandle(jitter_buffer_init(stepSize));
}

JNIEXPORT void JNICALL JB(destroy)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb != nullptr) jitter_buffer_destroy(jb);
}

JNIEXPORT void JNICALL JB(put)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jint timestamp, jint span, jint sequence, jint userData) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr || data == nullptr) return;
    // jitter_buffer_put copies packet.len bytes out of packet.data, and len is the caller's own
    // number, not the array's.
    len = clampToArray(env, len, data);
    jbyte* dataPtr = env->GetByteArrayElements(data, nullptr);
    if (dataPtr == nullptr) return;
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(dataPtr);
    packet.len = static_cast<spx_uint32_t>(len);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);
    jitter_buffer_put(jb, &packet); // copies the payload
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);
}

JNIEXPORT jint JNICALL JB(get)(JNIEnv* env, jobject, jlong handle, jbyteArray out, jint desiredSpan, jintArray meta) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    // meta receives five values below; a shorter array would be written past its end.
    if (jb == nullptr || out == nullptr || meta == nullptr || env->GetArrayLength(meta) < 5)
        return JITTER_BUFFER_BAD_ARGUMENT;
    jbyte* outPtr = env->GetByteArrayElements(out, nullptr);
    if (outPtr == nullptr) return JITTER_BUFFER_INTERNAL_ERROR;
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(outPtr);
    packet.len = static_cast<spx_uint32_t>(env->GetArrayLength(out));
    packet.timestamp = 0;
    packet.span = 0;
    packet.sequence = 0;
    packet.user_data = 0;
    int status = jitter_buffer_get(jb, &packet, desiredSpan, nullptr);
    env->ReleaseByteArrayElements(out, outPtr, 0);
    jint values[5] = {
        static_cast<jint>(packet.len), static_cast<jint>(packet.timestamp), static_cast<jint>(packet.span),
        static_cast<jint>(packet.sequence), static_cast<jint>(packet.user_data)
    };
    env->SetIntArrayRegion(meta, 0, 5, values);
    return status;
}

JNIEXPORT jint JNICALL JB(pointerTimestamp)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    return jb != nullptr ? jitter_buffer_get_pointer_timestamp(jb) : 0;
}

JNIEXPORT void JNICALL JB(tick)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb != nullptr) jitter_buffer_tick(jb);
}

JNIEXPORT jint JNICALL JB(ctl)(JNIEnv* env, jobject, jlong handle, jint request, jintArray value) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr || value == nullptr || env->GetArrayLength(value) < 1)
        return JITTER_BUFFER_BAD_ARGUMENT;
    // &v below is a stack address; see jitterRequestAllowed for what the rejected requests do
    // with it.
    if (!jitterRequestAllowed(request)) return JITTER_BUFFER_BAD_ARGUMENT;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = jitter_buffer_ctl(jb, request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT jint JNICALL JB(updateDelay)(JNIEnv*, jobject, jlong handle) {
    auto* jb = fromHandle<JitterBuffer>(handle);
    if (jb == nullptr) return JITTER_BUFFER_BAD_ARGUMENT;
    // The packet and start_offset arguments are unused by libspeexdsp's implementation.
    return jitter_buffer_update_delay(jb, nullptr, nullptr);
}

// ---- preprocessor ----

// Returns 0 on failure, which the Kotlin wrapper turns into a disabled preprocessor. A
// non-positive frame size is rejected here rather than passed on: speex would accept it and then
// run with a frame size PP(run) could never satisfy.
JNIEXPORT jlong JNICALL PP(init)(JNIEnv*, jobject, jint frameSize, jint sampleRate) {
    if (frameSize <= 0) return 0;
    SpeexPreprocessState* state = speex_preprocess_state_init(frameSize, sampleRate);
    // Both checks below survive their own removal, and both for a structural reason rather than a
    // missing test. speex_preprocess_state_init cannot return nullptr in this libspeexdsp at all:
    // it writes st->frame_size into the allocation without checking it (preprocess.c:396-397), so
    // an allocation failure is a crash inside speex, not a null return. The check stays because
    // the promise belongs to the library's signature, not to this version of its body. The
    // nothrow check below is the same case as in RS(init): nothing in the test setup can make
    // operator new fail.
    if (state == nullptr) return 0;
    auto* h = new (std::nothrow) PreprocessHandle{state, frameSize};
    if (h == nullptr) {
        speex_preprocess_state_destroy(state);
        return 0;
    }
    return toHandle(h);
}

// Returns the speex VAD decision (1 = speech, 0 = not), or -1 when the frame cannot be processed
// at all. The length check is the whole point: speex_preprocess_run writes frameSize samples into
// whatever pointer it is given, so a shorter array is an overrun on every frame -- the original
// bug this file exists to prevent.
JNIEXPORT jint JNICALL PP(run)(JNIEnv* env, jobject, jlong state, jshortArray frame) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr || frame == nullptr) return -1;
    if (env->GetArrayLength(frame) < h->frameSize) return -1;
    jshort* ptr = env->GetShortArrayElements(frame, nullptr);
    if (ptr == nullptr) return -1;
    int result = speex_preprocess_run(h->state, ptr);
    env->ReleaseShortArrayElements(frame, ptr, 0);
    return result;
}

JNIEXPORT jint JNICALL PP(ctlInt)(JNIEnv* env, jobject, jlong state, jint request, jintArray value) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr || value == nullptr || env->GetArrayLength(value) < 1) return -1;
    // Same as JB(ctl): &v is a stack address, and -1 is what speex_preprocess_ctl itself returns
    // for a request it does not know.
    if (!preprocessRequestAllowed(request)) return -1;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = speex_preprocess_ctl(h->state, request, &v);
    writeInt(env, value, v);
    return result;
}

JNIEXPORT void JNICALL PP(destroy)(JNIEnv*, jobject, jlong state) {
    auto* h = fromHandle<PreprocessHandle>(state);
    if (h == nullptr) return;
    speex_preprocess_state_destroy(h->state);
    delete h;
}

} // extern "C"
