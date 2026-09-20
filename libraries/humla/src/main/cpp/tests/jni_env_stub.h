/*
 * A stand-in JNIEnv, enough of one to call the hand-written JNI entry points in
 * ../jni_*.cpp from a host test without a JVM.
 *
 * Why this exists at all: everything the JNI layer can get wrong is invisible from Kotlin and
 * invisible from the C wrappers underneath. A missing length check writes past the end of a Java
 * array (this project has shipped that bug: speex wrote 640 samples into a 480-element array on
 * every frame), a handle freed twice corrupts the heap, and a bridge wired to the wrong C
 * function silently turns echo cancellation off. None of those produce an error code, so the
 * only way to hold them down is to execute the JNI functions and watch the memory.
 *
 * Two properties make that work:
 *
 *   - Arrays are exact-size heap blocks. A Java short[480] is 480 jshorts from malloc and not one
 *     byte more, so a write to element 480 is a heap-buffer-overflow that ASan reports with a
 *     stack trace, in the sanitized half of this directory.
 *   - Get*ArrayElements copies, like ART's does for non-critical accessors, and Release honours
 *     the mode argument. A bridge that releases with the wrong mode, or forgets to release, is
 *     therefore visible too: a missing release leaks the copy and LeakSanitizer fails the test.
 *
 * This is a test double, not an emulator. Anything not needed by the bridges under test is left
 * out of the function table on purpose: a bridge that starts calling something else crashes here
 * on a null function pointer rather than silently doing nothing.
 */
#ifndef HUMLA_TESTS_JNI_ENV_STUB_H
#define HUMLA_TESTS_JNI_ENV_STUB_H

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <type_traits>

namespace jnistub {

/* Backing store of one fake Java array. `length` is in elements, `bytes` per element. */
struct FakeArray {
    jsize length;
    jsize elem_size;
    void* data;
};

/* Number of Get*ArrayElements copies currently outstanding; a bridge that forgets to release
 * leaves this above zero and the test says so without needing a sanitizer. */
inline int& outstanding_copies() {
    static int n = 0;
    return n;
}

/* Arms one Get*ArrayElements to return NULL, which is what a JVM does when it cannot allocate the
 * copy. The bridges have to survive that without dereferencing it and without leaking whatever
 * they are already holding.
 *
 * fail_get_after(0) fails the very next call; fail_get_after(1) lets one succeed and fails the one
 * after it. The n > 0 form is the one that matters for a bridge that holds two arrays at once:
 * only the second allocation failing reaches a cleanup path that has something to release, and
 * arming the first call never gets there. Negative means disarmed, which is also where a
 * triggered failure leaves it. */
inline int& gets_until_failure() {
    static int n = -1;
    return n;
}
inline void fail_get_after(int n) { gets_until_failure() = n; }
inline void fail_get_never() { gets_until_failure() = -1; }

inline FakeArray* as_array(jarray a) { return reinterpret_cast<FakeArray*>(a); }

/* jshortArray, jbyteArray and jintArray are distinct C++ types, but a FakeArray is one struct, so
 * nothing in the type system stops a bridge from calling GetByteArrayElements on a short[]. On a
 * real JVM that is a hard error; here it would quietly read the wrong number of bytes.
 *
 * The comparison is by element SIZE, not by element type, which is as much as a FakeArray knows.
 * It separates byte from short from int/float, and it does NOT separate jint from jfloat -- both
 * are four bytes. Nothing currently reaches that gap (no bridge calls SetFloatArrayRegion at all;
 * the entry in Env() below is there so a bridge that starts to would not get a null function
 * pointer), but an int/float mix-up is precisely what this would have to catch, and it would not.
 * Closing it means giving FakeArray a type tag rather than a size. */
template <typename T>
inline void check_element_type(const FakeArray* fa, const char* who) {
    if (fa->elem_size != jsize(sizeof(T))) {
        std::fprintf(stderr, "stub: %s used a %d-byte accessor on a %d-byte array\n", who,
                     int(sizeof(T)), int(fa->elem_size));
        std::abort();
    }
}

inline jsize get_array_length(JNIEnv*, jarray a) { return as_array(a)->length; }

template <typename T>
inline T* get_elements(JNIEnv*, jarray a, jboolean* isCopy) {
    if (gets_until_failure() >= 0 && gets_until_failure()-- == 0) return nullptr;
    FakeArray* fa = as_array(a);
    check_element_type<T>(fa, "Get*ArrayElements");
    /* Exactly length*sizeof(T) bytes: ASan's redzone starts right after the last element. */
    T* copy = static_cast<T*>(std::malloc(size_t(fa->length) * sizeof(T)));
    std::memcpy(copy, fa->data, size_t(fa->length) * sizeof(T));
    if (isCopy) *isCopy = JNI_TRUE;
    outstanding_copies()++;
    return copy;
}

template <typename T>
inline void release_elements(JNIEnv*, jarray a, T* elems, jint mode) {
    FakeArray* fa = as_array(a);
    check_element_type<T>(fa, "Release*ArrayElements");
    if (mode != JNI_ABORT) std::memcpy(fa->data, elems, size_t(fa->length) * sizeof(T));
    if (mode != JNI_COMMIT) {
        std::free(elems);
        outstanding_copies()--;
    }
}

inline void get_int_region(JNIEnv*, jintArray a, jsize start, jsize len, jint* buf) {
    FakeArray* fa = as_array(a);
    check_element_type<jint>(fa, "GetIntArrayRegion");
    if (start < 0 || len < 0 || start + len > fa->length) {
        std::fprintf(stderr, "stub: GetIntArrayRegion out of range (%d+%d of %d)\n", start, len,
                     fa->length);
        std::abort();  /* a real JVM throws; for these tests, reaching it is the bug */
    }
    std::memcpy(buf, static_cast<jint*>(fa->data) + start, size_t(len) * sizeof(jint));
}

inline void set_int_region(JNIEnv*, jintArray a, jsize start, jsize len, const jint* buf) {
    FakeArray* fa = as_array(a);
    check_element_type<jint>(fa, "SetIntArrayRegion");
    if (start < 0 || len < 0 || start + len > fa->length) {
        std::fprintf(stderr, "stub: SetIntArrayRegion out of range (%d+%d of %d)\n", start, len,
                     fa->length);
        std::abort();
    }
    std::memcpy(static_cast<jint*>(fa->data) + start, buf, size_t(len) * sizeof(jint));
}

inline void set_float_region(JNIEnv*, jfloatArray a, jsize start, jsize len, const jfloat* buf) {
    FakeArray* fa = as_array(a);
    check_element_type<jfloat>(fa, "SetFloatArrayRegion");
    if (start < 0 || len < 0 || start + len > fa->length) {
        std::fprintf(stderr, "stub: SetFloatArrayRegion out of range (%d+%d of %d)\n", start, len,
                     fa->length);
        std::abort();
    }
    std::memcpy(static_cast<jfloat*>(fa->data) + start, buf, size_t(len) * sizeof(jfloat));
}

/* The JNI function table's struct is spelled JNINativeInterface by the NDK and
 * JNINativeInterface_ by the JDK. Naming it through JNIEnv::functions works with both. */
using NativeInterface =
    std::remove_const<std::remove_pointer<decltype(JNIEnv::functions)>::type>::type;

/* The one JNIEnv every test uses. Constructing it fills the function table. */
class Env {
  public:
    Env() {
        std::memset(&table_, 0, sizeof(table_));
        table_.GetArrayLength = get_array_length;
        table_.GetShortArrayElements = [](JNIEnv* e, jshortArray a, jboolean* c) {
            return get_elements<jshort>(e, a, c);
        };
        table_.ReleaseShortArrayElements = [](JNIEnv* e, jshortArray a, jshort* p, jint m) {
            release_elements<jshort>(e, a, p, m);
        };
        table_.GetByteArrayElements = [](JNIEnv* e, jbyteArray a, jboolean* c) {
            return get_elements<jbyte>(e, a, c);
        };
        table_.ReleaseByteArrayElements = [](JNIEnv* e, jbyteArray a, jbyte* p, jint m) {
            release_elements<jbyte>(e, a, p, m);
        };
        table_.GetIntArrayRegion = get_int_region;
        table_.SetIntArrayRegion = set_int_region;
        table_.SetFloatArrayRegion = set_float_region;
        env_.functions = &table_;
    }

    JNIEnv* get() { return &env_; }

  private:
    NativeInterface table_;
    JNIEnv env_;
};

/* A fake Java array that owns its storage. The storage is an exact-size heap block. */
template <typename T>
class Array {
  public:
    explicit Array(jsize length) : storage_(static_cast<T*>(std::calloc(size_t(length), sizeof(T)))) {
        fa_.length = length;
        fa_.elem_size = sizeof(T);
        fa_.data = storage_;
    }
    Array(const Array&) = delete;
    Array& operator=(const Array&) = delete;
    ~Array() { std::free(storage_); }

    /* jshortArray / jintArray / ... are distinct pointer types in C++; the cast is what makes
     * the FakeArray look like a Java array reference to the code under test. */
    template <typename JArrayT>
    JArrayT as() {
        return reinterpret_cast<JArrayT>(&fa_);
    }

    T& operator[](jsize i) { return storage_[i]; }
    const T& operator[](jsize i) const { return storage_[i]; }
    jsize length() const { return fa_.length; }
    T* data() { return storage_; }

  private:
    FakeArray fa_{};
    T* storage_;
};

}  // namespace jnistub

#endif  /* HUMLA_TESTS_JNI_ENV_STUB_H */
