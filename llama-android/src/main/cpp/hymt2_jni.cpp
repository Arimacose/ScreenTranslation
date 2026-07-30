#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * kLogTag = "HyMt2Q4Runtime";
std::mutex g_mutex;
std::once_flag g_backend_once;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_sampler * g_sampler = nullptr;
jlong g_owner_token = 0;

void log_info(const std::string & message) {
    __android_log_write(ANDROID_LOG_INFO, kLogTag, message.c_str());
}

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass clazz = env->FindClass(class_name);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
    }
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void release_runtime_locked() {
    if (g_sampler != nullptr) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_owner_token = 0;
}

bool decode_tokens(
    llama_context * context,
    const std::vector<llama_token> & tokens,
    int32_t batch_size,
    std::string & error
) {
    for (size_t offset = 0; offset < tokens.size();) {
        const int32_t count = std::min<int32_t>(
            batch_size,
            static_cast<int32_t>(tokens.size() - offset)
        );
        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token *>(tokens.data() + offset),
            count
        );
        const int decode_result = llama_decode(context, batch);
        if (decode_result != 0) {
            error = "llama_decode failed for prompt batch: " +
                std::to_string(decode_result);
            return false;
        }
        offset += static_cast<size_t>(count);
    }
    return true;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t size = llama_token_to_piece(
        vocab,
        token,
        buffer.data(),
        static_cast<int32_t>(buffer.size()),
        0,
        true
    );
    if (size < 0) {
        buffer.resize(static_cast<size_t>(-size));
        size = llama_token_to_piece(
            vocab,
            token,
            buffer.data(),
            static_cast<int32_t>(buffer.size()),
            0,
            true
        );
    }
    if (size < 0) {
        return {};
    }
    return std::string(buffer.data(), static_cast<size_t>(size));
}

std::string apply_chat_template(const std::string & user_prompt, std::string & error) {
    const char * chat_template = llama_model_chat_template(g_model, nullptr);
    if (chat_template == nullptr) {
        error = "Hy-MT2 GGUF does not contain a chat template";
        return {};
    }

    llama_chat_message message{"user", user_prompt.c_str()};
    int32_t required = llama_chat_apply_template(
        chat_template,
        &message,
        1,
        true,
        nullptr,
        0
    );
    if (required < 0) {
        error = "Failed to size the Hy-MT2 chat template";
        return {};
    }

    std::vector<char> formatted(static_cast<size_t>(required) + 1U);
    const int32_t written = llama_chat_apply_template(
        chat_template,
        &message,
        1,
        true,
        formatted.data(),
        static_cast<int32_t>(formatted.size())
    );
    if (written < 0 || written > static_cast<int32_t>(formatted.size())) {
        error = "Failed to apply the Hy-MT2 chat template";
        return {};
    }
    return std::string(formatted.data(), static_cast<size_t>(written));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_screentranslation_llama_LlamaRuntime_nativeLoadModel(
    JNIEnv * env,
    jobject,
    jlong owner_token,
    jstring model_path_value,
    jint context_size,
    jint threads
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (owner_token <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Runtime owner token is invalid");
        return nullptr;
    }
    if (g_owner_token != 0 && g_owner_token != owner_token) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "Hy-MT2 Q4 runtime is already owned by another active engine"
        );
        return nullptr;
    }
    const std::string model_path = from_jstring(env, model_path_value);
    if (model_path.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Model path is empty");
        return nullptr;
    }

    std::call_once(g_backend_once, [] {
        ggml_backend_load_all();
        llama_backend_init();
    });
    release_runtime_locked();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (g_model == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "llama.cpp failed to load Hy-MT2 Q4");
        return nullptr;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size);
    context_params.n_batch = 512;
    context_params.n_ubatch = 512;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = false;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        release_runtime_locked();
        throw_java(env, "java/lang/IllegalStateException", "llama.cpp failed to create context");
        return nullptr;
    }

    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = false;
    g_sampler = llama_sampler_chain_init(chain_params);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(64, 1.05F, 0.0F, 0.0F));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    g_owner_token = owner_token;

    std::ostringstream info;
    info << "llama.cpp=" << llama_print_system_info()
         << "; context=" << context_size
         << "; threads=" << threads;
    log_info("Hy-MT2 Q4 loaded: " + model_path);
    return env->NewStringUTF(info.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_screentranslation_llama_LlamaRuntime_nativeComplete(
    JNIEnv * env,
    jobject,
    jlong owner_token,
    jstring prompt_value,
    jint max_tokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (owner_token <= 0 || g_owner_token != owner_token) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            "Hy-MT2 Q4 runtime ownership is no longer active"
        );
        return nullptr;
    }
    if (g_model == nullptr || g_context == nullptr || g_sampler == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Hy-MT2 Q4 is not loaded");
        return nullptr;
    }

    const std::string user_prompt = from_jstring(env, prompt_value);
    std::string error;
    const std::string prompt = apply_chat_template(user_prompt, error);
    if (!error.empty()) {
        throw_java(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int32_t token_count = -llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (token_count <= 0) {
        throw_java(env, "java/lang/IllegalStateException", "Failed to size Hy-MT2 prompt tokens");
        return nullptr;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    token_count = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,
        true
    );
    if (token_count < 0) {
        throw_java(env, "java/lang/IllegalStateException", "Failed to tokenize Hy-MT2 prompt");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(token_count));

    if (token_count + max_tokens > static_cast<int32_t>(llama_n_ctx(g_context))) {
        throw_java(env, "java/lang/IllegalArgumentException", "Text exceeds Hy-MT2 context window");
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    llama_sampler_reset(g_sampler);
    if (!decode_tokens(g_context, prompt_tokens, 512, error)) {
        throw_java(env, "java/lang/IllegalStateException", error);
        return nullptr;
    }

    std::string output;
    output.reserve(static_cast<size_t>(max_tokens) * 4U);
    for (int32_t index = 0; index < max_tokens; ++index) {
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        output += token_piece(vocab, token);

        llama_token mutable_token = token;
        llama_batch batch = llama_batch_get_one(&mutable_token, 1);
        const int decode_result = llama_decode(g_context, batch);
        if (decode_result != 0) {
            throw_java(
                env,
                "java/lang/IllegalStateException",
                "llama_decode failed during generation: " + std::to_string(decode_result)
            );
            return nullptr;
        }
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_screentranslation_llama_LlamaRuntime_nativeClose(
    JNIEnv *,
    jobject,
    jlong owner_token
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (owner_token > 0 && g_owner_token == owner_token) {
        release_runtime_locked();
    }
}
