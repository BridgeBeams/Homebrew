// w-Ai-de mobile: JNI bridge to llama.cpp.
// This is the piece that replaces Ollama on the desktop build. The phone loads a
// GGUF file straight off storage and runs it on the CPU.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>

#include "llama.h"

#define TAG "waide-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct ChatMsg {
    std::string role;
    std::string content;
};

struct State {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;

    std::vector<ChatMsg>     history;
    std::vector<llama_token> pending;   // tokens not yet fed to the model
    std::string              formatted; // full templated transcript so far
    size_t                   prev_len = 0;

    std::string assistant_buf;          // text of the reply being generated
    int   n_past      = 0;
    int   n_ctx       = 4096;
    int   n_threads   = 4;
    float temperature = 0.7f;
    float top_p       = 0.9f;
    float min_p       = 0.05f;
    bool  generating  = false;
    std::string last_error;

    std::mutex mu;
};

State g;
bool g_backend_ready = false;

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Fall back to a ChatML transcript when a GGUF ships without a chat template.
std::string fallback_template(const std::vector<ChatMsg> & msgs, bool add_assistant) {
    std::string out;
    for (const auto & m : msgs) {
        out += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
    }
    if (add_assistant) out += "<|im_start|>assistant\n";
    return out;
}

std::string apply_template(const std::vector<ChatMsg> & msgs, bool add_assistant) {
    const char * tmpl = llama_model_chat_template(g.model, nullptr);
    if (tmpl == nullptr) {
        return fallback_template(msgs, add_assistant);
    }

    std::vector<llama_chat_message> raw;
    raw.reserve(msgs.size());
    for (const auto & m : msgs) {
        raw.push_back({ m.role.c_str(), m.content.c_str() });
    }

    std::vector<char> buf(8192);
    int n = llama_chat_apply_template(tmpl, raw.data(), raw.size(), add_assistant,
                                      buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, raw.data(), raw.size(), add_assistant,
                                      buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        LOGE("chat template failed, using fallback");
        return fallback_template(msgs, add_assistant);
    }
    return std::string(buf.data(), n);
}

void rebuild_sampler() {
    if (g.sampler != nullptr) {
        llama_sampler_free(g.sampler);
        g.sampler = nullptr;
    }
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g.sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g.sampler, llama_sampler_init_top_p(g.top_p, 1));
    llama_sampler_chain_add(g.sampler, llama_sampler_init_min_p(g.min_p, 1));
    llama_sampler_chain_add(g.sampler, llama_sampler_init_temp(g.temperature));
    llama_sampler_chain_add(g.sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

bool make_context() {
    if (g.ctx != nullptr) {
        llama_free(g.ctx);
        g.ctx = nullptr;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) g.n_ctx;
    cp.n_batch         = 512;
    cp.n_ubatch        = 512;
    cp.n_threads       = g.n_threads;
    cp.n_threads_batch = g.n_threads;
    cp.no_perf         = true;

    g.ctx = llama_init_from_model(g.model, cp);
    if (g.ctx == nullptr) {
        g.last_error = "Could not create a context. Try a smaller context size in Settings.";
        return false;
    }
    g.n_past = 0;
    return true;
}

std::vector<llama_token> tokenize(const std::string & text, bool add_special) {
    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                            nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> toks(n);
    int got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                             toks.data(), (int32_t) toks.size(), add_special, true);
    if (got < 0) return {};
    toks.resize(got);
    return toks;
}

std::string piece(llama_token tok) {
    const llama_vocab * vocab = llama_model_get_vocab(g.model);
    char buf[512];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return {};
    return std::string(buf, n);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_waide_mobile_LlamaBridge_nativeLoad(JNIEnv * env, jobject,
                                             jstring path, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g.mu);

    if (!g_backend_ready) {
        llama_backend_init();
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
        }, nullptr);
        g_backend_ready = true;
    }

    // Drop anything already loaded first, so a second model does not sit on top
    // of the first one in memory.
    if (g.ctx)     { llama_free(g.ctx);             g.ctx = nullptr; }
    if (g.sampler) { llama_sampler_free(g.sampler); g.sampler = nullptr; }
    if (g.model)   { llama_model_free(g.model);     g.model = nullptr; }
    g.history.clear();
    g.formatted.clear();
    g.pending.clear();
    g.prev_len = 0;
    g.last_error.clear();

    g.n_ctx     = nCtx;
    g.n_threads = nThreads;

    std::string p = jstr(env, path);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU only. Phone GPU backends are not reliable here.
    mp.use_mmap     = true;
    mp.use_mlock    = false;

    g.model = llama_model_load_from_file(p.c_str(), mp);
    if (g.model == nullptr) {
        g.last_error = "That file would not load. It may be an unsupported or corrupted GGUF.";
        LOGE("model load failed: %s", p.c_str());
        return JNI_FALSE;
    }

    if (!make_context()) {
        llama_model_free(g.model);
        g.model = nullptr;
        return JNI_FALSE;
    }

    rebuild_sampler();
    LOGI("model loaded: %s", p.c_str());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_waide_mobile_LlamaBridge_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.ctx)     { llama_free(g.ctx);             g.ctx = nullptr; }
    if (g.sampler) { llama_sampler_free(g.sampler); g.sampler = nullptr; }
    if (g.model)   { llama_model_free(g.model);     g.model = nullptr; }
    g.history.clear();
    g.formatted.clear();
    g.pending.clear();
    g.prev_len = 0;
}

JNIEXPORT jstring JNICALL
Java_com_waide_mobile_LlamaBridge_nativeModelDescription(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.model == nullptr) return env->NewStringUTF("");
    char buf[256];
    llama_model_desc(g.model, buf, sizeof(buf));
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL
Java_com_waide_mobile_LlamaBridge_nativeLastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    return env->NewStringUTF(g.last_error.c_str());
}

JNIEXPORT void JNICALL
Java_com_waide_mobile_LlamaBridge_nativeSetSampling(JNIEnv *, jobject,
                                                    jfloat temp, jfloat topP, jfloat minP) {
    std::lock_guard<std::mutex> lock(g.mu);
    g.temperature = temp;
    g.top_p       = topP;
    g.min_p       = minP;
    if (g.model != nullptr) rebuild_sampler();
}

// Wipe the conversation. The context is rebuilt rather than cleared, which keeps
// this working across llama.cpp versions that renamed the cache clearing calls.
JNIEXPORT jboolean JNICALL
Java_com_waide_mobile_LlamaBridge_nativeResetChat(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.model == nullptr) return JNI_FALSE;
    g.history.clear();
    g.formatted.clear();
    g.pending.clear();
    g.prev_len = 0;
    g.assistant_buf.clear();
    g.generating = false;
    bool ok = make_context();
    if (ok) rebuild_sampler();
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_waide_mobile_LlamaBridge_nativeSetSystemPrompt(JNIEnv * env, jobject, jstring sys) {
    std::lock_guard<std::mutex> lock(g.mu);
    std::string s = jstr(env, sys);
    // Replace any existing system message, keep it first.
    if (!g.history.empty() && g.history.front().role == "system") {
        g.history.erase(g.history.begin());
    }
    if (!s.empty()) {
        g.history.insert(g.history.begin(), ChatMsg{"system", s});
    }
}

// Queue a user turn: apply the template to the whole history, work out what is
// new since last time, and tokenize only that delta.
JNIEXPORT jint JNICALL
Java_com_waide_mobile_LlamaBridge_nativeBeginTurn(JNIEnv * env, jobject, jstring userMsg) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (g.model == nullptr || g.ctx == nullptr) {
        g.last_error = "No model is loaded.";
        return -1;
    }

    g.history.push_back(ChatMsg{"user", jstr(env, userMsg)});
    std::string full = apply_template(g.history, true);

    if (full.size() < g.prev_len) {
        // Template output shrank, which should not happen. Start clean.
        g.prev_len = 0;
    }
    std::string delta = full.substr(g.prev_len);
    g.formatted = full;

    g.pending = tokenize(delta, g.prev_len == 0);
    if (g.pending.empty()) {
        g.last_error = "Nothing to send.";
        return -1;
    }

    if (g.n_past + (int) g.pending.size() >= g.n_ctx - 64) {
        g.last_error = "This conversation filled the context window. Start a new chat, "
                       "or raise the context size in Settings and reload the model.";
        g.history.pop_back();
        return -2;
    }

    g.assistant_buf.clear();
    g.generating = true;
    return 0;
}

// Produce one token of the reply. Returns null when the reply is finished.
JNIEXPORT jstring JNICALL
Java_com_waide_mobile_LlamaBridge_nativeNextToken(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.generating || g.ctx == nullptr) return nullptr;

    // First call after nativeBeginTurn feeds the prompt, in batch-sized chunks so
    // a long message or a long history can never overflow one decode call.
    if (!g.pending.empty()) {
        const int chunk = 256;
        const int total = (int) g.pending.size();
        for (int off = 0; off < total; off += chunk) {
            int n = std::min(chunk, total - off);
            llama_batch batch = llama_batch_get_one(g.pending.data() + off, (int32_t) n);
            if (llama_decode(g.ctx, batch) != 0) {
                g.last_error = "The model failed while reading the prompt. Try a smaller "
                               "context size, or start a new chat.";
                g.generating = false;
                g.pending.clear();
                return nullptr;
            }
            g.n_past += n;
        }
        g.pending.clear();
    }

    llama_token tok = llama_sampler_sample(g.sampler, g.ctx, -1);
    const llama_vocab * vocab = llama_model_get_vocab(g.model);

    if (llama_vocab_is_eog(vocab, tok)) {
        g.history.push_back(ChatMsg{"assistant", g.assistant_buf});
        g.prev_len  = apply_template(g.history, false).size();
        g.generating = false;
        return nullptr;
    }

    if (g.n_past >= g.n_ctx - 8) {
        g.history.push_back(ChatMsg{"assistant", g.assistant_buf});
        g.prev_len  = apply_template(g.history, false).size();
        g.generating = false;
        g.last_error = "Reached the end of the context window.";
        return nullptr;
    }

    std::string text = piece(tok);
    g.assistant_buf += text;

    // Feed the sampled token back so the next call can sample the one after it.
    llama_batch nb = llama_batch_get_one(&tok, 1);
    if (llama_decode(g.ctx, nb) != 0) {
        g.history.push_back(ChatMsg{"assistant", g.assistant_buf});
        g.prev_len  = apply_template(g.history, false).size();
        g.generating = false;
        return nullptr;
    }
    g.n_past += 1;

    return env->NewStringUTF(text.c_str());
}

// Stop early. Whatever has been generated so far is kept as the reply.
JNIEXPORT void JNICALL
Java_com_waide_mobile_LlamaBridge_nativeStop(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.generating) return;
    g.generating = false;
    g.pending.clear();
    g.history.push_back(ChatMsg{"assistant", g.assistant_buf});
    g.prev_len = apply_template(g.history, false).size();
}

JNIEXPORT jint JNICALL
Java_com_waide_mobile_LlamaBridge_nativeContextUsed(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mu);
    return g.n_past;
}

} // extern "C"
