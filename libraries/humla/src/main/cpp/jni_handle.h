// Shared helpers for the hand-written JNI bindings. Handles are raw pointers carried as jlong,
// so the Kotlin side must release exactly once and never while another thread is inside the
// object. New bindings should use the cell-based handles from jni_native_handle.h instead.
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
