/*
 * The only exported symbol of libhumla_native.so. Binds every bridge's native methods by name, so a
 * missing class or a signature mismatch fails System.loadLibrary instead of the first call.
 */
#include <jni.h>

#include "jni_bridges.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    bool ok = humla::registerOpusNatives(env) && humla::registerSpeexdspNatives(env) &&
              humla::registerRnnoiseNatives(env) && humla::registerWebRtcApmNatives(env);
    return ok ? JNI_VERSION_1_6 : JNI_ERR;
}
