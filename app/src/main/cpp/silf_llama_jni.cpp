#include "silf_llama_jni.h"
#include <android/log.h>
#include <stdexcept>
#include <vector>

#define TAG "SilfLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    llama_backend_free();
}

// Helper para emular el comportamiento de llama_batch_add
static void llama_batch_add(struct llama_batch & batch, llama_token id, llama_pos pos, const std::vector<llama_seq_id> & seq_ids, bool logits) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits;
    batch.n_tokens++;
}

static void llama_batch_clear(struct llama_batch & batch) {
    batch.n_tokens = 0;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_silf_app_llm_LlamaNative_loadModel(JNIEnv *env, jobject thiz, jstring model_path, jboolean use_mmap, jint threads, jint context_size) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    InferenceContext* infCtx = new InferenceContext();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = use_mmap;

    infCtx->model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!infCtx->model) {
        LOGE("Failed to load model from %s", path);
        delete infCtx;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = context_size;

    infCtx->ctx = llama_new_context_with_model(infCtx->model, cparams);
    if (!infCtx->ctx) {
        LOGE("Failed to create context");
        delete infCtx;
        return 0;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(infCtx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_generate(JNIEnv *env, jobject thiz, jlong handle, jstring prompt, jint max_tokens, jfloat temp, jobject callback) {
    if (handle == 0) return;

    InferenceContext* infCtx = reinterpret_cast<InferenceContext*>(handle);
    const char *c_prompt = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(c_prompt);
    env->ReleaseStringUTFChars(prompt, c_prompt);

    // Necesitamos pasar la ejecución a un thread nativo y llamar a callbacks en Java
    jobject callbackGlobal = env->NewGlobalRef(callback);

    std::thread([infCtx, prompt_str, max_tokens, temp, callbackGlobal]() {
        JNIEnv *env = nullptr;
        g_vm->AttachCurrentThread(&env, nullptr);

        jclass callbackClass = env->GetObjectClass(callbackGlobal);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
        jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "()V");
        jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");

        std::lock_guard<std::mutex> lock(infCtx->mutex);
        infCtx->cancel_flag = false;

        try {
            // Tokenizar el prompt
            std::vector<llama_token> tokens_list;
            tokens_list.resize(prompt_str.length() + 4);
            int n_tokens = llama_tokenize(infCtx->model, prompt_str.c_str(), prompt_str.length(), tokens_list.data(), tokens_list.size(), true, true);

            if (n_tokens < 0) {
                tokens_list.resize(-n_tokens);
                n_tokens = llama_tokenize(infCtx->model, prompt_str.c_str(), prompt_str.length(), tokens_list.data(), tokens_list.size(), true, true);
            }
            if (n_tokens < 0) {
                throw std::runtime_error("Failed to tokenize");
            }
            tokens_list.resize(n_tokens);

            llama_batch batch = llama_batch_init(512, 0, 1);
            for (size_t i = 0; i < tokens_list.size(); i++) {
                llama_batch_add(batch, tokens_list[i], i, { 0 }, false);
            }
            batch.logits[batch.n_tokens - 1] = true;

            if (llama_decode(infCtx->ctx, batch)) {
                throw std::runtime_error("llama_decode() failed");
            }

            int n_cur = batch.n_tokens;
            int n_decode = 0;

            while (n_cur <= llama_n_ctx(infCtx->ctx) && n_decode < max_tokens) {
                if (infCtx->cancel_flag) break;

                auto* logits = llama_get_logits_ith(infCtx->ctx, batch.n_tokens - 1);
                auto n_vocab = llama_n_vocab(infCtx->model);

                std::vector<llama_token_data> candidates;
                candidates.reserve(n_vocab);
                for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                    candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
                }

                llama_token_data_array candidates_p = { candidates.data(), candidates.size(), false };
                llama_sample_temp(infCtx->ctx, &candidates_p, temp);
                llama_token new_token_id = llama_sample_token(infCtx->ctx, &candidates_p);

                if (new_token_id == llama_token_eos(infCtx->model) || new_token_id == llama_token_eot(infCtx->model)) {
                    break;
                }

                char buf[128];
                int n = llama_token_to_piece(infCtx->model, new_token_id, buf, sizeof(buf), 0, true);
                if (n < 0) {
                    throw std::runtime_error("llama_token_to_piece failed");
                }
                std::string piece(buf, n);

                jstring jPiece = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(callbackGlobal, onTokenMethod, jPiece);
                env->DeleteLocalRef(jPiece);

                llama_batch_clear(batch);
                llama_batch_add(batch, new_token_id, n_cur, { 0 }, true);

                if (llama_decode(infCtx->ctx, batch)) {
                    throw std::runtime_error("llama_decode() failed");
                }

                n_cur += 1;
                n_decode += 1;
            }

            llama_batch_free(batch);
            env->CallVoidMethod(callbackGlobal, onCompleteMethod);

        } catch (const std::exception& e) {
            jstring jMsg = env->NewStringUTF(e.what());
            env->CallVoidMethod(callbackGlobal, onErrorMethod, jMsg);
            env->DeleteLocalRef(jMsg);
        }

        env->DeleteGlobalRef(callbackGlobal);
        g_vm->DetachCurrentThread();
    }).detach();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_cancel(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    InferenceContext* infCtx = reinterpret_cast<InferenceContext*>(handle);
    infCtx->cancel_flag = true;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_silf_app_llm_LlamaNative_unload(JNIEnv *env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    InferenceContext* infCtx = reinterpret_cast<InferenceContext*>(handle);
    std::lock_guard<std::mutex> lock(infCtx->mutex);
    delete infCtx;
}
