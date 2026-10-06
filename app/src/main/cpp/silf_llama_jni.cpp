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

static void throwOOMException(JNIEnv *env, const char *message) {
    jclass exClass = env->FindClass("java/lang/OutOfMemoryError");
    if (!exClass) {
        env->ExceptionClear();
        exClass = env->FindClass("java/lang/RuntimeException");
    }
    if (exClass) {
        env->ThrowNew(exClass, message);
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_silf_app_llm_LlamaNative_loadModel(JNIEnv *env, jobject thiz, jstring model_path, jboolean use_mmap, jint threads, jint context_size) {
    if (!model_path) {
        throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo (ruta inválida)");
        return 0;
    }

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) {
        throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
        return 0;
    }

    InferenceContext* infCtx = nullptr;

    try {
        infCtx = new InferenceContext();

        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = use_mmap;
        mparams.n_gpu_layers = 99; // Fase 6: Delegar capas a la GPU

        infCtx->model = llama_load_model_from_file(path, mparams);
        env->ReleaseStringUTFChars(model_path, path);
        path = nullptr;

        if (!infCtx->model) {
            LOGE("Failed to load model from file: insufficient memory or invalid model");
            delete infCtx;
            infCtx = nullptr;
            throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
            return 0;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = context_size;
        int effective_threads = (threads > 0) ? threads : 4;
        cparams.n_threads = effective_threads;
        cparams.n_threads_batch = effective_threads; // Optimización clave: batch evaluation con todos los cores
        cparams.offload_kqv = true; // Offload KV ops a GPU si hay soporte
        cparams.flash_attn = true;  // Flash attention para máxima velocidad

        infCtx->ctx = llama_new_context_with_model(infCtx->model, cparams);
        if (!infCtx->ctx) {
            LOGE("Failed to create context: insufficient memory");
            delete infCtx;
            infCtx = nullptr;
            throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
            return 0;
        }

        LOGI("Model loaded successfully with n_gpu_layers=99, flash_attn=true, offload_kqv=true");
        return reinterpret_cast<jlong>(infCtx);

    } catch (const std::bad_alloc &e) {
        LOGE("std::bad_alloc caught while loading model: %s", e.what());
        if (path) {
            env->ReleaseStringUTFChars(model_path, path);
            path = nullptr;
        }
        if (infCtx) {
            delete infCtx;
            infCtx = nullptr;
        }
        throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
        return 0;
    } catch (const std::exception &e) {
        LOGE("std::exception caught while loading model: %s", e.what());
        if (path) {
            env->ReleaseStringUTFChars(model_path, path);
            path = nullptr;
        }
        if (infCtx) {
            delete infCtx;
            infCtx = nullptr;
        }
        throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
        return 0;
    } catch (...) {
        LOGE("Unknown exception caught while loading model");
        if (path) {
            env->ReleaseStringUTFChars(model_path, path);
            path = nullptr;
        }
        if (infCtx) {
            delete infCtx;
            infCtx = nullptr;
        }
        throwOOMException(env, "Error: Memoria insuficiente para cargar el modelo");
        return 0;
    }
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
            // Tokenizar el prompt.
            // BUGFIX: el buffer debe ser >= 4x la longitud del string en bytes porque:
            //   - tokens especiales como <|im_start|> se cuentan como 1 token pero son muchos bytes
            //   - el ratio tokens/chars en ChatML puede superar 1.5x
            // Primer intento con estimación generosa (4x + 64 para tokens BOS/EOS)
            std::vector<llama_token> tokens_list;
            int estimated = (int)prompt_str.length() * 4 + 64;
            tokens_list.resize(estimated);
            int n_tokens = llama_tokenize(infCtx->model, prompt_str.c_str(), (int)prompt_str.length(), tokens_list.data(), (int)tokens_list.size(), true, true);

            if (n_tokens < 0) {
                // llama.cpp devuelve el tamaño exacto negado si el buffer era pequeño
                tokens_list.resize(-n_tokens + 8);
                n_tokens = llama_tokenize(infCtx->model, prompt_str.c_str(), (int)prompt_str.length(), tokens_list.data(), (int)tokens_list.size(), true, true);
            }
            if (n_tokens <= 0) {
                throw std::runtime_error("Failed to tokenize prompt (n_tokens=" + std::to_string(n_tokens) + ")");
            }
            tokens_list.resize(n_tokens);
            LOGI("Prompt tokenized: %d chars -> %d tokens", (int)prompt_str.length(), n_tokens);

            int n_ctx_total = llama_n_ctx(infCtx->ctx);
            if (n_tokens >= n_ctx_total) {
                throw std::runtime_error("Prompt exceeds context size");
            }

            // Limpiar KV cache para que la nueva generación no arrastre tokens de la respuesta anterior
            llama_kv_cache_clear(infCtx->ctx);

            // Inicializar el batch (suficiente para prompt y especulaciones MTP)
            llama_batch batch = llama_batch_init(std::max(512, n_tokens + 16), 0, 1);
            struct BatchGuard {
                llama_batch& b;
                ~BatchGuard() { llama_batch_free(b); }
            } batch_guard{batch};

            for (size_t i = 0; i < tokens_list.size(); i++) {
                llama_batch_add(batch, tokens_list[i], (llama_pos)i, { 0 }, false);
            }
            batch.logits[batch.n_tokens - 1] = true;

            if (llama_decode(infCtx->ctx, batch)) {
                throw std::runtime_error("llama_decode() failed during prompt evaluation");
            }

            int n_cur = batch.n_tokens;
            int n_decode = 0;

            // Helper lambda para muestrear un token dado un índice en las salidas del batch actual.
            // Parámetros de sampling deterministas (cero creatividad) para forzar solo comandos estructurados:
            //   temperature = 0.1f
            //   top_k       = 1
            //   top_p       = 0.1f
            const float SAMPLING_TEMP  = (temp > 0.0f && temp <= 0.1f) ? temp : 0.1f;
            const int   SAMPLING_TOP_K = 1;
            const float SAMPLING_TOP_P = 0.1f;

            auto sample_token_at = [&](int i_batch, float /*temperature_unused*/) -> llama_token {
                float* logits = llama_get_logits_ith(infCtx->ctx, i_batch);
                if (!logits) return llama_token_eos(infCtx->model);

                auto n_vocab = llama_n_vocab(infCtx->model);
                std::vector<llama_token_data> candidates;
                candidates.reserve(n_vocab);
                for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
                    candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
                }
                llama_token_data_array candidates_p = { candidates.data(), candidates.size(), false };

                // Aplicar top_k, luego top_p (nucleus), luego temperatura en orden correcto
                llama_sample_top_k(infCtx->ctx, &candidates_p, SAMPLING_TOP_K, 1);
                llama_sample_top_p(infCtx->ctx, &candidates_p, SAMPLING_TOP_P, 1);
                llama_sample_temp(infCtx->ctx, &candidates_p, SAMPLING_TEMP);
                return llama_sample_token(infCtx->ctx, &candidates_p);
            };

            // Helper: devuelve true si el token o su representación textual es un stop token.
            // Es la fuente única de verdad para la detección de parada — evita duplicar la lógica.
            auto is_stop_token = [&](llama_token tok) -> bool {
                // 1. Verificación por ID de token: EOS, EOT y cualquier end-of-generation
                if (llama_token_is_eog(infCtx->model, tok) ||
                    tok == llama_token_eos(infCtx->model) ||
                    tok == llama_token_eot(infCtx->model)) {
                    LOGI("Stop token by ID detected: %d", tok);
                    return true;
                }
                // 2. Verificación por string: <|im_end|> y <|endoftext|>
                char buf[128];
                int n = llama_token_to_piece(infCtx->model, tok, buf, sizeof(buf), 0, true);
                if (n > 0) {
                    std::string piece(buf, n);
                    if (piece.find("<|im_end|>") != std::string::npos ||
                        piece.find("<|endoftext|>") != std::string::npos) {
                        LOGI("Stop token by string detected: '%s'", piece.c_str());
                        return true;
                    }
                }
                return false;
            };

            // Helper lambda para emitir un token hacia Kotlin vía callback.
            // Retorna false si es stop token (el caller debe romper el bucle inmediatamente).
            auto emit_token = [&](llama_token tok) -> bool {
                if (is_stop_token(tok)) {
                    return false; // Señal de parada: el caller hace break
                }
                char buf[128];
                int n = llama_token_to_piece(infCtx->model, tok, buf, sizeof(buf), 0, true);
                if (n > 0) {
                    std::string piece(buf, n);
                    jstring jPiece = env->NewStringUTF(piece.c_str());
                    env->CallVoidMethod(callbackGlobal, onTokenMethod, jPiece);
                    env->DeleteLocalRef(jPiece);
                }
                return true;
            };

            // MTP (Multi-Token Prediction) Speculative Decoding
            // Replicando programáticamente: --spec-type draft-mtp --spec-draft-n-max 2
            const int SPEC_DRAFT_N_MAX = 2;
            std::vector<llama_token> history_tokens = tokens_list;

            // Función de draft para predecir hasta SPEC_DRAFT_N_MAX tokens futuros
            auto draft_mtp_tokens = [&](const std::vector<llama_token>& history, int max_draft) -> std::vector<llama_token> {
                std::vector<llama_token> drafts;
                if (history.size() < 2 || max_draft <= 0) return drafts;

                // Búsqueda de patrones multi-token (3-gram y 2-gram) en el historial contextual
                for (int n = 3; n >= 2; --n) {
                    if ((int)history.size() < n) continue;
                    for (int i = (int)history.size() - n - 1; i >= 0; --i) {
                        bool match = true;
                        for (int k = 0; k < n; ++k) {
                            if (history[history.size() - n + k] != history[i + k]) {
                                match = false;
                                break;
                            }
                        }
                        if (match && (i + n < (int)history.size())) {
                            for (int d = 0; d < max_draft && (i + n + d < (int)history.size()); ++d) {
                                drafts.push_back(history[i + n + d]);
                            }
                            return drafts;
                        }
                    }
                }
                return drafts;
            };

            // Muestrear el primer token producido por el prompt
            llama_token cur_token = sample_token_at(batch.n_tokens - 1, temp);
            if (!emit_token(cur_token)) {
                env->CallVoidMethod(callbackGlobal, onCompleteMethod);
                return;
            }
            history_tokens.push_back(cur_token);
            n_decode++;

            // Bucle principal con decodificación especulativa MTP
            while (n_cur < n_ctx_total && n_decode < max_tokens) {
                if (infCtx->cancel_flag) break;

                // 1. Predecir hasta SPEC_DRAFT_N_MAX tokens especulativos
                std::vector<llama_token> drafts;
                if (n_decode + SPEC_DRAFT_N_MAX <= max_tokens && n_cur + 1 + SPEC_DRAFT_N_MAX < n_ctx_total) {
                    drafts = draft_mtp_tokens(history_tokens, SPEC_DRAFT_N_MAX);
                }

                // 2. Preparar batch: cur_token en n_cur y drafts en n_cur + 1 + d
                llama_batch_clear(batch);
                llama_batch_add(batch, cur_token, (llama_pos)n_cur, { 0 }, true);

                for (size_t d = 0; d < drafts.size(); ++d) {
                    llama_batch_add(batch, drafts[d], (llama_pos)(n_cur + 1 + d), { 0 }, true);
                }

                // 3. Evaluar el batch en un solo paso forward
                if (llama_decode(infCtx->ctx, batch)) {
                    throw std::runtime_error("llama_decode() failed in speculative MTP loop");
                }

                // 4. Verificación especulativa de los draft tokens
                int n_past = n_cur + 1; // Posición asegurada en el KV cache
                bool all_drafts_accepted = true;
                llama_token next_token = -1;

                for (size_t d = 0; d < drafts.size(); ++d) {
                    llama_token sampled = sample_token_at((int)d, temp);

                    if (sampled == drafts[d]) {
                        // Token especulativo ACEPTADO — pero puede ser stop token
                        if (is_stop_token(sampled)) {
                            // EOS/EOT aceptado: salir limpiamente SIN emitir ni dejar next_token=-1
                            all_drafts_accepted = false;
                            next_token = sampled; // marcado para el break post-loop
                            goto generation_done;  // salida inmediata del while
                        }
                        if (!emit_token(sampled)) {
                            all_drafts_accepted = false;
                            next_token = sampled;
                            goto generation_done;
                        }
                        history_tokens.push_back(sampled);
                        n_decode++;
                        n_past++;

                        if (n_decode >= max_tokens) {
                            all_drafts_accepted = false;
                            next_token = sampled;
                            break;
                        }
                    } else {
                        // Token especulativo RECHAZADO: la predicción real es 'sampled'
                        all_drafts_accepted = false;
                        next_token = sampled;
                        break;
                    }
                }

                if (all_drafts_accepted && !drafts.empty()) {
                    // Todos los tokens especulados fueron aceptados;
                    // muestrear el siguiente a partir de los logits del último draft
                    next_token = sample_token_at((int)drafts.size(), temp);
                    if (!emit_token(next_token)) {
                        break; // stop token detectado
                    }
                    history_tokens.push_back(next_token);
                    n_decode++;
                } else if (!drafts.empty()) {
                    // Al menos un token especulado fue rechazado; podar el KV cache
                    llama_kv_cache_seq_rm(infCtx->ctx, 0, (llama_pos)n_past, -1);

                    // next_token puede ser -1 si nunca se asignó en el loop de drafts
                    // (no debería ocurrir con el goto arriba, pero salvaguarda defensiva)
                    if (next_token < 0 || is_stop_token(next_token)) {
                        break;
                    }
                    if (n_decode < max_tokens) {
                        if (!emit_token(next_token)) {
                            break;
                        }
                        history_tokens.push_back(next_token);
                        n_decode++;
                    }
                } else {
                    // Paso autorregresivo estándar sin drafts
                    next_token = sample_token_at(0, temp);
                    if (!emit_token(next_token)) {
                        break; // stop token detectado, salir inmediatamente
                    }
                    history_tokens.push_back(next_token);
                    n_decode++;
                }

                cur_token = next_token;
                n_cur = n_past;
            }

            generation_done:
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
