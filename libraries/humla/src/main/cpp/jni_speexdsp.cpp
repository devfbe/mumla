/*
 * JNI bridge for libspeexdsp: the resampler, the jitter buffer and the preprocessor.
 *
 * libspeexdsp takes sample/byte counts from the state or from separate arguments, never from the
 * buffer, so each entry point checks the real Java array length and clamps or refuses. Without
 * that, speex once wrote 640 samples into a 480-element array on every frame.
 */
#include <jni.h>
#include <new>
#include <speex/speex_jitter.h>
#include <speex/speex_preprocess.h>
#include <speex/speex_resampler.h>
#include "jni_bridges.h"
#include "jni_common.h"
#include "jni_native_handle.h"

namespace {

/* speex_preprocess_run writes the frame size the state was created with, and there is no ctl to
 * query it, so it is kept beside the state. Kotlin holds this struct, not the SpeexPreprocessState. */
struct PreprocessHandle {
    SpeexPreprocessState* state;
    int frameSize;
};

/* speex_resampler_process_native indexes per-channel arrays with the caller's channel_index
 * without checking it, and there is no ctl to query the channel count, so it is kept here. */
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

humla::HandleTable& resamplers() { return humla::handleTable<ResamplerHandle>(); }
humla::HandleTable& jitterBuffers() { return humla::handleTable<JitterBuffer>(); }
humla::HandleTable& preprocessors() { return humla::handleTable<PreprocessHandle>(); }

using humla::writeInt;

/* Stack capacity of the per-call copies; larger requests fall back to the heap. */
constexpr std::size_t kInlineSamples = 2048;
constexpr std::size_t kInlinePacket = 4096;

/* The ctl entry points pass jitter_buffer_ctl / speex_preprocess_ctl the address of a four-byte
 * spx_int32_t on the stack, and the request number comes from Kotlin. For several requests the
 * callee does something else with that address:
 *
 *   JITTER_BUFFER_GET_DESTROY_CALLBACK (5)   *(void(**)(void*))ptr = jitter->destroy
 *   SPEEX_PREPROCESS_GET_ECHO_STATE    (25)  *(SpeexEchoState**)ptr = st->echo_state
 *       -- eight bytes into a four-byte stack object on 64-bit ABIs.
 *   JITTER_BUFFER_SET_DESTROY_CALLBACK (4)   jitter->destroy = (void(*)(void*))ptr
 *   SPEEX_PREPROCESS_SET_ECHO_STATE    (24)  st->echo_state = (SpeexEchoState*)ptr
 *       -- the stack address is kept and later dereferenced or called.
 *   SPEEX_PREPROCESS_GET_PSD           (39)
 *   SPEEX_PREPROCESS_GET_NOISE_PSD     (43)  ps_size ints into those four bytes.
 *   SPEEX_PREPROCESS_SET_AGC_LEVEL     (6)   reads the caller's int as a float.
 *   JITTER_BUFFER_SET_MAX_LATE_RATE    (10)  divides by the value, so 0 is a SIGFPE.
 *
 * Hence an allow list: a libspeexdsp update can add pointer-typed requests. A request may be added
 * only if the library treats ptr as exactly one spx_int32_t (read or written once) and touches
 * nothing outside the state's own allocation.
 *
 * Refusals: jitterCtl returns JITTER_BUFFER_BAD_ARGUMENT (-2), distinct from the library's -1 for an
 * unknown request. preprocessCtlInt returns -1 like speex_preprocess_ctl, because its status has no
 * spare value.
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

jlong resamplerInit(JNIEnv* env, jobject, jint channels, jint inRate, jint outRate, jint quality, jintArray error) noexcept {
    // speex_resampler_init accepts it, but then every processInt would be out of range.
    if (channels <= 0) {
        writeInt(env, error, RESAMPLER_ERR_INVALID_ARG);
        return 0;
    }
    int err = 0;
    SpeexResamplerState* st = speex_resampler_init(channels, inRate, outRate, quality, &err);
    writeInt(env, error, err);
    if (st == nullptr) return 0;
    // Otherwise we would leak st and report success (err == 0).
    auto* h = new (std::nothrow) ResamplerHandle{st, channels};
    jlong handle = h != nullptr ? resamplers().add(h) : 0;
    if (handle == 0) {
        delete h;
        speex_resampler_destroy(st);
        writeInt(env, error, RESAMPLER_ERR_ALLOC_FAILED);
    }
    return handle;
}

jint resamplerProcessInt(JNIEnv* env, jobject, jlong state, jint channelIndex, jshortArray input, jintArray inLen, jshortArray out, jintArray outLen) noexcept {
    auto* h = static_cast<ResamplerHandle*>(resamplers().get(state));
    if (h == nullptr || input == nullptr || out == nullptr || inLen == nullptr || outLen == nullptr)
        return RESAMPLER_ERR_INVALID_ARG;
    if (env->GetArrayLength(inLen) < 1 || env->GetArrayLength(outLen) < 1)
        return RESAMPLER_ERR_INVALID_ARG;
    // speex indexes per-channel arrays with channelIndex without a range check.
    if (channelIndex < 0 || channelIndex >= h->channels) return RESAMPLER_ERR_INVALID_ARG;
    jint inCount = 0, outCount = 0;
    env->GetIntArrayRegion(inLen, 0, 1, &inCount);
    env->GetIntArrayRegion(outLen, 0, 1, &outCount);
    // speex trusts these counts, not the array lengths; a negative count would become a huge
    // spx_uint32_t.
    inCount = clampToArray(env, inCount, input);
    outCount = clampToArray(env, outCount, out);
    spx_uint32_t in = static_cast<spx_uint32_t>(inCount);
    spx_uint32_t outN = static_cast<spx_uint32_t>(outCount);
    humla::RegionBuffer<jshort, kInlineSamples> inBuf(inCount);
    humla::RegionBuffer<jshort, kInlineSamples> outBuf(outCount);
    if (inBuf.data() == nullptr || outBuf.data() == nullptr) return RESAMPLER_ERR_ALLOC_FAILED;
    inBuf.read(env, input, 0, inCount);
    int result = speex_resampler_process_int(h->state, channelIndex, inBuf.data(), &in, outBuf.data(), &outN);
    outBuf.write(env, out, 0, static_cast<jsize>(outN));
    writeInt(env, inLen, static_cast<jint>(in));
    writeInt(env, outLen, static_cast<jint>(outN));
    return result;
}

void resamplerDestroy(JNIEnv*, jobject, jlong state) noexcept {
    auto* h = static_cast<ResamplerHandle*>(resamplers().release(state));
    if (h == nullptr) return;
    speex_resampler_destroy(h->state);
    delete h;
}

jlong jitterInit(JNIEnv*, jobject, jint stepSize) noexcept {
    JitterBuffer* jb = jitter_buffer_init(stepSize);
    if (jb == nullptr) return 0;
    jlong handle = jitterBuffers().add(jb);
    if (handle == 0) jitter_buffer_destroy(jb);
    return handle;
}

void jitterDestroy(JNIEnv*, jobject, jlong handle) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().release(handle));
    if (jb != nullptr) jitter_buffer_destroy(jb);
}

void jitterPut(JNIEnv* env, jobject, jlong handle, jbyteArray data, jint len, jint timestamp, jint span, jint sequence, jint userData) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    if (jb == nullptr || data == nullptr) return;
    // jitter_buffer_put copies len bytes, and len is the caller's number, not the array's.
    len = clampToArray(env, len, data);
    humla::RegionBuffer<jbyte, kInlinePacket> bytes(len);
    if (bytes.data() == nullptr) return;
    bytes.read(env, data, 0, len);
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(bytes.data());
    packet.len = static_cast<spx_uint32_t>(len);
    packet.timestamp = static_cast<spx_uint32_t>(timestamp);
    packet.span = static_cast<spx_uint32_t>(span);
    packet.sequence = static_cast<spx_uint16_t>(sequence);
    packet.user_data = static_cast<spx_uint32_t>(userData);
    jitter_buffer_put(jb, &packet); // copies the payload
}

jint jitterGet(JNIEnv* env, jobject, jlong handle, jbyteArray out, jint desiredSpan, jintArray meta) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    // meta receives five values below; a shorter array would be written past its end.
    if (jb == nullptr || out == nullptr || meta == nullptr || env->GetArrayLength(meta) < 5)
        return JITTER_BUFFER_BAD_ARGUMENT;
    jsize capacity = env->GetArrayLength(out);
    humla::RegionBuffer<jbyte, kInlinePacket> bytes(capacity);
    if (bytes.data() == nullptr) return JITTER_BUFFER_INTERNAL_ERROR;
    JitterBufferPacket packet;
    packet.data = reinterpret_cast<char*>(bytes.data());
    packet.len = static_cast<spx_uint32_t>(capacity);
    packet.timestamp = 0;
    packet.span = 0;
    packet.sequence = 0;
    packet.user_data = 0;
    int status = jitter_buffer_get(jb, &packet, desiredSpan, nullptr);
    // Only a delivered packet has payload; libspeexdsp never reports more than the capacity.
    if (status == JITTER_BUFFER_OK && packet.len <= static_cast<spx_uint32_t>(capacity))
        bytes.write(env, out, 0, static_cast<jsize>(packet.len));
    jint values[5] = {
        static_cast<jint>(packet.len), static_cast<jint>(packet.timestamp), static_cast<jint>(packet.span),
        static_cast<jint>(packet.sequence), static_cast<jint>(packet.user_data)
    };
    env->SetIntArrayRegion(meta, 0, 5, values);
    return status;
}

jint jitterPointerTimestamp(JNIEnv*, jobject, jlong handle) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    return jb != nullptr ? jitter_buffer_get_pointer_timestamp(jb) : 0;
}

void jitterTick(JNIEnv*, jobject, jlong handle) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    if (jb != nullptr) jitter_buffer_tick(jb);
}

jint jitterCtl(JNIEnv* env, jobject, jlong handle, jint request, jintArray value) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    if (jb == nullptr || value == nullptr || env->GetArrayLength(value) < 1)
        return JITTER_BUFFER_BAD_ARGUMENT;
    // &v is a stack address, see jitterRequestAllowed.
    if (!jitterRequestAllowed(request)) return JITTER_BUFFER_BAD_ARGUMENT;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = jitter_buffer_ctl(jb, request, &v);
    writeInt(env, value, v);
    return result;
}

jint jitterUpdateDelay(JNIEnv*, jobject, jlong handle) noexcept {
    auto* jb = static_cast<JitterBuffer*>(jitterBuffers().get(handle));
    if (jb == nullptr) return JITTER_BUFFER_BAD_ARGUMENT;
    // The packet and start_offset arguments are unused by libspeexdsp's implementation.
    return jitter_buffer_update_delay(jb, nullptr, nullptr);
}

// Returns 0 on failure (the Kotlin wrapper then disables the preprocessor). A non-positive frame
// size would be accepted by speex but could never satisfy preprocessRun.
jlong preprocessInit(JNIEnv*, jobject, jint frameSize, jint sampleRate) noexcept {
    if (frameSize <= 0) return 0;
    SpeexPreprocessState* state = speex_preprocess_state_init(frameSize, sampleRate);
    // speex_preprocess_state_init never returns nullptr in this version, but its signature allows
    // it.
    if (state == nullptr) return 0;
    auto* h = new (std::nothrow) PreprocessHandle{state, frameSize};
    jlong handle = h != nullptr ? preprocessors().add(h) : 0;
    if (handle == 0) {
        delete h;
        speex_preprocess_state_destroy(state);
    }
    return handle;
}

// Returns the speex VAD decision (1 = speech, 0 = not), or -1 when the frame cannot be processed.
// speex_preprocess_run writes frameSize samples, so a shorter array must be refused.
jint preprocessRun(JNIEnv* env, jobject, jlong state, jshortArray frame) noexcept {
    auto* h = static_cast<PreprocessHandle*>(preprocessors().get(state));
    if (h == nullptr || frame == nullptr) return -1;
    if (env->GetArrayLength(frame) < h->frameSize) return -1;
    humla::RegionBuffer<jshort, kInlineSamples> samples(h->frameSize);
    if (samples.data() == nullptr) return -1;
    samples.read(env, frame, 0, h->frameSize);
    int result = speex_preprocess_run(h->state, samples.data());
    samples.write(env, frame, 0, h->frameSize);
    return result;
}

jint preprocessCtlInt(JNIEnv* env, jobject, jlong state, jint request, jintArray value) noexcept {
    auto* h = static_cast<PreprocessHandle*>(preprocessors().get(state));
    if (h == nullptr || value == nullptr || env->GetArrayLength(value) < 1) return -1;
    // &v is a stack address, see preprocessRequestAllowed. -1 matches speex's own "unknown".
    if (!preprocessRequestAllowed(request)) return -1;
    jint in = 0;
    env->GetIntArrayRegion(value, 0, 1, &in);
    spx_int32_t v = in;
    int result = speex_preprocess_ctl(h->state, request, &v);
    writeInt(env, value, v);
    return result;
}

void preprocessDestroy(JNIEnv*, jobject, jlong state) noexcept {
    auto* h = static_cast<PreprocessHandle*>(preprocessors().release(state));
    if (h == nullptr) return;
    speex_preprocess_state_destroy(h->state);
    delete h;
}

}  // namespace

bool humla::registerSpeexdspNatives(JNIEnv* env) {
    const std::array<JNINativeMethod, 3> resampler = {
        humla::nativeMethod("init", resamplerInit),
        humla::nativeMethod("processInt", resamplerProcessInt),
        humla::nativeMethod("destroy", resamplerDestroy),
    };
    const std::array<JNINativeMethod, 8> jitter = {
        humla::nativeMethod("init", jitterInit),
        humla::nativeMethod("destroy", jitterDestroy),
        humla::nativeMethod("put", jitterPut),
        humla::nativeMethod("get", jitterGet),
        humla::nativeMethod("pointerTimestamp", jitterPointerTimestamp),
        humla::nativeMethod("tick", jitterTick),
        humla::nativeMethod("ctl", jitterCtl),
        humla::nativeMethod("updateDelay", jitterUpdateDelay),
    };
    const std::array<JNINativeMethod, 4> preprocess = {
        humla::nativeMethod("init", preprocessInit),
        humla::nativeMethod("run", preprocessRun),
        humla::nativeMethod("ctlInt", preprocessCtlInt),
        humla::nativeMethod("destroy", preprocessDestroy),
    };
    return humla::registerNatives(env, "se/lublin/humla/audio/native/SpeexResamplerNative", resampler) &&
           humla::registerNatives(env, "se/lublin/humla/audio/native/SpeexJitterNative", jitter) &&
           humla::registerNatives(env, "se/lublin/humla/audio/native/SpeexPreprocessNative", preprocess);
}
