#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_itantra_NativeLib_hello(JNIEnv* env, jclass) {
    // Phase 0 stub for toolchain validation; real sherpa-onnx/Oboe deferred
    const std::string hello = "it-01";
    return env->NewStringUTF(hello.c_str());
}
