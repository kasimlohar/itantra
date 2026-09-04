#include <jni.h>
#include <string>
#include "audio/audio_vad_bridge.h"
#include "audio/audio_capture.h"
#include "vad/silero_vad.h"
#include "vad/vad_fsm.h"
#include "vad/vad_pipeline.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_itantra_NativeLib_hello(JNIEnv* env, jclass) {
    const std::string hello = "it-01";
    return env->NewStringUTF(hello.c_str());
}

// --- AudioVadBridge JNI ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_createBridge(JNIEnv* /*env*/, jobject /*thiz*/) {
    auto* bridge = new itantra::bridge::AudioVadBridge();
    return reinterpret_cast<jlong>(bridge);
}

extern "C" JNIEXPORT void JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_destroyBridge(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    delete bridge;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_startCapture(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    return bridge->capture->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_stopCapture(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    bridge->capture->stop();
    bridge->pipeline->stop();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_isCapturing(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    return bridge->capture->isCapturing() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_process(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return 0;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    // Ensure pipeline is running for process
    if (!bridge->pipeline->isRunning()) bridge->pipeline->start();
    return static_cast<jint>(bridge->pipeline->processAll());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_getVadState(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return 0; // Idle ordinal
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    return static_cast<jint>(bridge->pipeline->state());
}

extern "C" JNIEXPORT void JNICALL
Java_com_itantra_data_audio_NativeAudioBridge_clear(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto* bridge = reinterpret_cast<itantra::bridge::AudioVadBridge*>(handle);
    bridge->pipeline->clear();
}
