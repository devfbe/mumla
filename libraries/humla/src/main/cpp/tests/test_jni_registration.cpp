/* Host test for JNI_OnLoad (../jni_onload.cpp): it registers every bridge, and exactly the bindings
 * listed in ../jni_registrations.txt. JniRegistrationsTest.kt checks the same file against the
 * Kotlin `external fun`s, so between them a binding cannot drift from its declaration unnoticed;
 * on a device a mismatch would fail System.loadLibrary. */
#include "jni_env_stub.h"

#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved);

int main(int argc, char** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "usage: %s <jni_registrations.txt>\n", argv[0]);
        return 2;
    }
    std::ifstream file(argv[1]);
    if (!file) {
        std::fprintf(stderr, "cannot read %s\n", argv[1]);
        return 2;
    }
    std::vector<std::string> expected;
    for (std::string line; std::getline(file, line);) {
        if (!line.empty() && line[0] != '#') expected.push_back(line);
    }

    jnistub::Env env;
    if (JNI_OnLoad(env.vm(), nullptr) != JNI_VERSION_1_6) {
        std::fprintf(stderr, "FAIL: JNI_OnLoad did not report success\n");
        return 1;
    }
    std::vector<std::string> actual;
    for (const auto& r : jnistub::registrations()) actual.push_back(r.cls + " " + r.name + " " + r.signature);

    std::sort(expected.begin(), expected.end());
    std::sort(actual.begin(), actual.end());
    if (expected == actual) {
        std::printf("jni_registration: %zu bindings match %s\n", actual.size(), argv[1]);
        return 0;
    }
    for (const auto& line : actual)
        if (!std::binary_search(expected.begin(), expected.end(), line))
            std::fprintf(stderr, "FAIL: registered but not listed: %s\n", line.c_str());
    for (const auto& line : expected)
        if (!std::binary_search(actual.begin(), actual.end(), line))
            std::fprintf(stderr, "FAIL: listed but not registered: %s\n", line.c_str());
    return 1;
}
