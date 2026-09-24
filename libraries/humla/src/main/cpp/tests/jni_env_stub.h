/*
 * A stand-in JNIEnv (and JavaVM), enough of one to register and call the JNI bridges in
 * ../jni_*.cpp from a host test without a JVM. RegisterNatives records every binding; native()
 * looks one up by class and name.
 *
 * Arrays are exact-size heap blocks, and the region accessors abort on an out-of-range region, so
 * a bridge that reads or writes past a Java array fails the test. Get*ArrayElements is left out:
 * the bridges copy regions into buffers of their own, where ASan sees an overrun.
 *
 * This is a test double, not an emulator: functions the bridges do not need are left out of the
 * table on purpose, so a bridge calling something new crashes on a null pointer instead of
 * silently doing nothing.
 */
#ifndef HUMLA_TESTS_JNI_ENV_STUB_H
#define HUMLA_TESTS_JNI_ENV_STUB_H

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <type_traits>
#include <vector>

#include "jni_common.h"

namespace jnistub {

/* ---------------------------------------------------------------- classes and RegisterNatives */

/* One RegisterNatives entry, with the class it was registered on. */
struct Registration {
    std::string cls, name, signature;
    void* fn;
};

inline std::vector<Registration>& registrations() {
    static std::vector<Registration> r;
    return r;
}

/* FindClass hands out a pointer to the class name, interned so that it stays valid. */
inline jclass find_class(JNIEnv*, const char* name) {
    static std::deque<std::string> names;
    for (auto& n : names)
        if (n == name) return reinterpret_cast<jclass>(&n);
    names.emplace_back(name);
    return reinterpret_cast<jclass>(&names.back());
}

inline jint register_natives(JNIEnv*, jclass cls, const JNINativeMethod* methods, jint n) {
    const std::string& name = *reinterpret_cast<const std::string*>(cls);
    for (jint i = 0; i < n; i++)
        registrations().push_back({name, methods[i].name, methods[i].signature, methods[i].fnPtr});
    return JNI_OK;
}

/* The function registered as cls.name, called as type F. Aborts unless exactly one registration
 * matches and its signature is the one F implies, so a test cannot call a bridge through the
 * wrong type. */
template <typename F>
F native(const char* cls, const char* name) {
    const Registration* found = nullptr;
    for (const auto& r : registrations()) {
        if (r.cls != cls || r.name != name) continue;
        if (found != nullptr) {
            std::fprintf(stderr, "stub: %s.%s registered twice\n", cls, name);
            std::abort();
        }
        found = &r;
    }
    if (found == nullptr) {
        std::fprintf(stderr, "stub: %s.%s was never registered\n", cls, name);
        std::abort();
    }
    const char* expected = humla::NativeFunction<F>::descriptor();
    if (found->signature != expected) {
        std::fprintf(stderr, "stub: %s.%s registered as %s, called as %s\n", cls, name,
                     found->signature.c_str(), expected);
        std::abort();
    }
    return reinterpret_cast<F>(found->fn);
}

/* Backing store of one fake Java array. `length` is in elements, `bytes` per element. */
struct FakeArray {
    jsize length;
    jsize elem_size;
    void* data;
};

inline FakeArray* as_array(jarray a) { return reinterpret_cast<FakeArray*>(a); }

/* jshortArray, jbyteArray and jintArray are distinct C++ types but a FakeArray is one struct, so
 * accessor calls are checked by element SIZE. That separates byte, short and int/float, but not
 * jint from jfloat (both four bytes); that would need a type tag. */
template <typename T>
inline void check_element_type(const FakeArray* fa, const char* who) {
    if (fa->elem_size != jsize(sizeof(T))) {
        std::fprintf(stderr, "stub: %s used a %d-byte accessor on a %d-byte array\n", who,
                     int(sizeof(T)), int(fa->elem_size));
        std::abort();
    }
}

inline jsize get_array_length(JNIEnv*, jarray a) { return as_array(a)->length; }

/* Get/Set<Type>ArrayRegion. An out-of-range region aborts: a JVM would throw, and for these tests
 * reaching it is the bug. */
template <typename T>
inline void check_region(const FakeArray* fa, jsize start, jsize len, const char* who) {
    check_element_type<T>(fa, who);
    if (start < 0 || len < 0 || start + len > fa->length) {
        std::fprintf(stderr, "stub: %s out of range (%d+%d of %d)\n", who, start, len, fa->length);
        std::abort();
    }
}

template <typename T>
inline void get_region(JNIEnv*, jarray a, jsize start, jsize len, T* buf) {
    FakeArray* fa = as_array(a);
    check_region<T>(fa, start, len, "Get*ArrayRegion");
    std::memcpy(buf, static_cast<T*>(fa->data) + start, size_t(len) * sizeof(T));
}

template <typename T>
inline void set_region(JNIEnv*, jarray a, jsize start, jsize len, const T* buf) {
    FakeArray* fa = as_array(a);
    check_region<T>(fa, start, len, "Set*ArrayRegion");
    std::memcpy(static_cast<T*>(fa->data) + start, buf, size_t(len) * sizeof(T));
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
        table_.GetByteArrayRegion = [](JNIEnv* e, jbyteArray a, jsize s, jsize n, jbyte* b) {
            get_region<jbyte>(e, a, s, n, b);
        };
        table_.SetByteArrayRegion = [](JNIEnv* e, jbyteArray a, jsize s, jsize n, const jbyte* b) {
            set_region<jbyte>(e, a, s, n, b);
        };
        table_.GetShortArrayRegion = [](JNIEnv* e, jshortArray a, jsize s, jsize n, jshort* b) {
            get_region<jshort>(e, a, s, n, b);
        };
        table_.SetShortArrayRegion = [](JNIEnv* e, jshortArray a, jsize s, jsize n, const jshort* b) {
            set_region<jshort>(e, a, s, n, b);
        };
        table_.GetIntArrayRegion = [](JNIEnv* e, jintArray a, jsize s, jsize n, jint* b) {
            get_region<jint>(e, a, s, n, b);
        };
        table_.SetIntArrayRegion = [](JNIEnv* e, jintArray a, jsize s, jsize n, const jint* b) {
            set_region<jint>(e, a, s, n, b);
        };
        table_.GetFloatArrayRegion = [](JNIEnv* e, jfloatArray a, jsize s, jsize n, jfloat* b) {
            get_region<jfloat>(e, a, s, n, b);
        };
        table_.SetFloatArrayRegion = [](JNIEnv* e, jfloatArray a, jsize s, jsize n, const jfloat* b) {
            set_region<jfloat>(e, a, s, n, b);
        };
        table_.FindClass = find_class;
        table_.RegisterNatives = register_natives;
        table_.DeleteLocalRef = [](JNIEnv*, jobject) {};
        env_.functions = &table_;

        std::memset(&vm_table_, 0, sizeof(vm_table_));
        vm_table_.GetEnv = [](JavaVM* vm, void** out, jint) {
            *out = static_cast<Env*>(vm->functions->reserved0)->get();
            return jint(JNI_OK);
        };
        vm_table_.reserved0 = this;
        vm_.functions = &vm_table_;
    }
    Env(const Env&) = delete;
    Env& operator=(const Env&) = delete;

    JNIEnv* get() { return &env_; }
    JavaVM* vm() { return &vm_; }

  private:
    using InvokeInterface =
        std::remove_const<std::remove_pointer<decltype(JavaVM::functions)>::type>::type;

    NativeInterface table_;
    JNIEnv env_;
    InvokeInterface vm_table_;
    JavaVM vm_;
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
