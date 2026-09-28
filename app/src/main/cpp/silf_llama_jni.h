#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include "llama.h"

// Contexto de inferencia C++
struct InferenceContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> cancel_flag{false};
    std::mutex mutex;

    ~InferenceContext() {
        if (ctx) llama_free(ctx);
        if (model) llama_free_model(model);
    }
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_silf_app_llm_LlamaNative_loadModel(JNIEnv *env, jobject thiz, jstring model_path, jboolean use_mmap, jint threads, jint context_size);

JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_generate(JNIEnv *env, jobject thiz, jlong handle, jstring prompt, jint max_tokens, jfloat temp, jobject callback);

JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_cancel(JNIEnv *env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_unload(JNIEnv *env, jobject thiz, jlong handle);

}
