/*
 * Shared helpers for the JNI bridges: registration tables whose signatures are derived from the C++
 * function types, and array-region buffers that avoid Get*ArrayElements.
 */
#ifndef HUMLA_JNI_COMMON_H
#define HUMLA_JNI_COMMON_H

#include <jni.h>

#include <array>
#include <cstddef>
#include <new>

namespace humla {

// ---------------------------------------------------------------- JNI type descriptors

template <typename T> struct JniDescriptor;
template <> struct JniDescriptor<void> { static constexpr char value[] = "V"; };
template <> struct JniDescriptor<jboolean> { static constexpr char value[] = "Z"; };
template <> struct JniDescriptor<jint> { static constexpr char value[] = "I"; };
template <> struct JniDescriptor<jlong> { static constexpr char value[] = "J"; };
template <> struct JniDescriptor<jfloat> { static constexpr char value[] = "F"; };
template <> struct JniDescriptor<jbyteArray> { static constexpr char value[] = "[B"; };
template <> struct JniDescriptor<jshortArray> { static constexpr char value[] = "[S"; };
template <> struct JniDescriptor<jintArray> { static constexpr char value[] = "[I"; };
template <> struct JniDescriptor<jfloatArray> { static constexpr char value[] = "[F"; };

constexpr std::size_t length(const char* s) {
    std::size_t n = 0;
    while (s[n] != '\0') n++;
    return n;
}

/** "(<args>)<return>" for a native method implemented as R fn(JNIEnv*, jobject, Args...). */
template <typename R, typename... Args>
struct MethodDescriptor {
    static constexpr std::size_t size =
        2 + (length(JniDescriptor<Args>::value) + ... + 0) + length(JniDescriptor<R>::value) + 1;

    static constexpr std::array<char, size> make() {
        std::array<char, size> out{};
        std::size_t at = 0;
        out[at++] = '(';
        const char* parts[] = {JniDescriptor<Args>::value..., ")"};
        for (const char* part : parts) {
            for (std::size_t i = 0; part[i] != '\0'; i++) out[at++] = part[i];
        }
        for (std::size_t i = 0; JniDescriptor<R>::value[i] != '\0'; i++) out[at++] = JniDescriptor<R>::value[i];
        out[at] = '\0';
        return out;
    }

    static constexpr std::array<char, size> value = make();
};

template <typename F> struct NativeFunction;
template <typename R, typename... Args>
struct NativeFunction<R (*)(JNIEnv*, jobject, Args...) noexcept> {
    static constexpr const char* descriptor() { return MethodDescriptor<R, Args...>::value.data(); }
};

/** One registration entry. The signature comes from fn's type, so the two cannot disagree. */
template <typename F>
JNINativeMethod nativeMethod(const char* name, F fn) {
    // JDK headers declare these fields char*, the NDK const char*; RegisterNatives only reads them.
    return JNINativeMethod{const_cast<char*>(name),
                           const_cast<char*>(NativeFunction<F>::descriptor()),
                           reinterpret_cast<void*>(fn)};
}

/** Registers methods on className; false (with a pending exception) if the class or a method is
 *  missing or a signature does not match its Kotlin declaration. */
template <std::size_t N>
bool registerNatives(JNIEnv* env, const char* className, const std::array<JNINativeMethod, N>& methods) {
    jclass cls = env->FindClass(className);
    if (cls == nullptr) return false;
    bool ok = env->RegisterNatives(cls, methods.data(), static_cast<jint>(N)) == JNI_OK;
    env->DeleteLocalRef(cls);
    return ok;
}

// ---------------------------------------------------------------- array regions

template <typename T> struct ArrayAccess;
template <> struct ArrayAccess<jbyte> {
    using Array = jbyteArray;
    static void get(JNIEnv* e, Array a, jsize s, jsize n, jbyte* b) { e->GetByteArrayRegion(a, s, n, b); }
    static void set(JNIEnv* e, Array a, jsize s, jsize n, const jbyte* b) { e->SetByteArrayRegion(a, s, n, b); }
};
template <> struct ArrayAccess<jshort> {
    using Array = jshortArray;
    static void get(JNIEnv* e, Array a, jsize s, jsize n, jshort* b) { e->GetShortArrayRegion(a, s, n, b); }
    static void set(JNIEnv* e, Array a, jsize s, jsize n, const jshort* b) { e->SetShortArrayRegion(a, s, n, b); }
};
template <> struct ArrayAccess<jfloat> {
    using Array = jfloatArray;
    static void get(JNIEnv* e, Array a, jsize s, jsize n, jfloat* b) { e->GetFloatArrayRegion(a, s, n, b); }
    static void set(JNIEnv* e, Array a, jsize s, jsize n, const jfloat* b) { e->SetFloatArrayRegion(a, s, n, b); }
};

/**
 * A native copy of part of a Java array: on the stack up to Inline elements (every per-frame buffer
 * fits), on the heap beyond. Unlike Get*ArrayElements it copies only what is used and needs no
 * malloc on the audio thread. The caller checks bounds before reading or writing back; data() is
 * nullptr if a heap buffer could not be allocated.
 */
template <typename T, std::size_t Inline>
class RegionBuffer {
  public:
    using Array = typename ArrayAccess<T>::Array;

    explicit RegionBuffer(jsize count)
        : data_(count <= static_cast<jsize>(Inline) ? inline_ : new (std::nothrow) T[count]) {}
    RegionBuffer(const RegionBuffer&) = delete;
    RegionBuffer& operator=(const RegionBuffer&) = delete;
    ~RegionBuffer() {
        if (data_ != inline_) delete[] data_;
    }

    T* data() { return data_; }

    void read(JNIEnv* env, Array array, jsize start, jsize count) {
        ArrayAccess<T>::get(env, array, start, count, data_);
    }
    void write(JNIEnv* env, Array array, jsize start, jsize count) const {
        ArrayAccess<T>::set(env, array, start, count, data_);
    }

  private:
    T inline_[Inline];
    T* data_;
};

/** Writes value into slot 0 of an optional int array (error / out parameters) that has room. */
inline void writeInt(JNIEnv* env, jintArray target, jint value) {
    if (target != nullptr && env->GetArrayLength(target) >= 1) env->SetIntArrayRegion(target, 0, 1, &value);
}

}  // namespace humla

#endif  // HUMLA_JNI_COMMON_H
