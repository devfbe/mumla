// Shared helpers for the hand-written JNI bindings. Handles are raw pointers carried as jlong.
//
// A raw pointer handle puts the whole lifetime contract on the Kotlin side: release exactly
// once, and never while another thread is inside the object. jni_native_handle.h wraps a
// pointer in a cell instead, so that releasing twice is a no-op and using a released handle is
// an error return rather than heap corruption. The codec bindings still use the raw form; new
// bindings should not.
#pragma once
#include <jni.h>
#include <cstdint>

template <typename T>
static inline T* fromHandle(jlong handle) {
    return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

static inline jlong toHandle(const void* pointer) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

/** Writes value into slot 0 of an optional one-element int array (error / out parameters). */
static inline void writeInt(JNIEnv* env, jintArray target, jint value) {
    if (target != nullptr) env->SetIntArrayRegion(target, 0, 1, &value);
}
