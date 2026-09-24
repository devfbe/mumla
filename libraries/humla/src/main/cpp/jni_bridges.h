/*
 * The registration entry point of each JNI bridge. JNI_OnLoad (jni_onload.cpp) calls all of them;
 * the host tests call them one at a time.
 *
 * Each returns false, with a Java exception pending, if a class or method it binds is missing or a
 * signature does not match the Kotlin `external fun`. jni_registrations.txt lists every binding.
 */
#ifndef HUMLA_JNI_BRIDGES_H
#define HUMLA_JNI_BRIDGES_H

#include <jni.h>

namespace humla {

bool registerOpusNatives(JNIEnv* env);
bool registerSpeexdspNatives(JNIEnv* env);
bool registerRnnoiseNatives(JNIEnv* env);
bool registerWebRtcApmNatives(JNIEnv* env);

}  // namespace humla

#endif  // HUMLA_JNI_BRIDGES_H
