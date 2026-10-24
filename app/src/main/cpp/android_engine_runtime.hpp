#pragma once
#include <dococr/inference.hpp>
#include <stdexcept>
#include <string>

namespace dococr {
// 加载在同一线程完成；嵌套版面后端继承当前计划，独立引擎不会互相覆盖。
inline thread_local int android_loading_threads = 1;
inline int android_inference_threads() { return android_loading_threads; }
inline bool load_android_backend(IInferenceEngine& backend, const BackendLoadSpec& spec, int threads) {
    if (threads != 1 && threads != 2 && threads != 4) throw std::runtime_error("android_threads_unsupported");
    struct Scope {
        int previous;
        explicit Scope(int threads) : previous(android_loading_threads) { android_loading_threads = threads; }
        ~Scope() { android_loading_threads = previous; }
    } scope(threads);
    return backend.load(spec);
}
inline std::string android_thread_config(std::string config) {
    const std::string before = "\"thread_num\":1";
    const std::string after = "\"thread_num\":" + std::to_string(android_inference_threads());
    size_t offset = 0, count = 0;
    while ((offset = config.find(before, offset)) != std::string::npos) {
        config.replace(offset, before.size(), after);offset += after.size();++count;
    }
    if (count != 2) throw std::runtime_error("android_thread_config_mismatch");
    return config;
}
}
