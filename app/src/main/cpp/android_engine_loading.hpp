#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#else
#include <cstdio>
#endif
#include <chrono>
#include <cstdlib>
#include <stdexcept>
#include <llm/llm.hpp>

namespace dococr {
// 先设置有效运行配置再 load；权重和 KV 均不使用 mmap 磁盘缓存。
inline bool load_android_llm(MNN::Transformer::Llm& llm) {
    const char* cache_root = std::getenv("TMPDIR");
    if (!cache_root || !*cache_root) throw std::runtime_error("android_model_cache_missing");
    // 使用现有 App 私有临时目录，不创建或复用旧 ovis-mmap-* 目录。
    if (!llm.set_config("{\"use_mmap\":false,\"kvcache_mmap\":false,\"tmp_path\":" + json_quote(cache_root) + "}"))
        throw std::runtime_error("ocr_load_config_failed");
    auto started = std::chrono::steady_clock::now();
    bool loaded = llm.load();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "OcrEngine", "OCR model load: %lld ms, success=%d, mmap=0",
        static_cast<long long>(elapsed), loaded);
#else
    std::fprintf(stderr, "OCR model load: %lld ms, success=%d, mmap=0\n", static_cast<long long>(elapsed), loaded);
#endif
    return loaded;
}
} // namespace dococr
