#pragma once
#include "config.hpp"
#include "legacy_ovis_profile.hpp"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <set>
#include <cmath>

namespace dococr {
using ModelJson = nlohmann::json;
inline bool same_model_json(const ModelJson& left, const ModelJson& right) {
    // 固定 MNN 的 dump_config 会把 JSON 小数序列化为 float 精度。
    if (left.is_number() && right.is_number()) {
        if (left.is_number_integer() && right.is_number_integer()) return left == right;
        const auto a = left.get<double>(), b = right.get<double>();
        return std::isfinite(a) && std::isfinite(b) && std::abs(a-b) <= 1e-6 * std::max({1.0,std::abs(a),std::abs(b)});
    }
    if (left.type() != right.type() || left.size() != right.size()) return false;
    if (left.is_object()) {
        for (auto field = left.begin(); field != left.end(); ++field)
            if (!right.contains(field.key()) || !same_model_json(field.value(),right.at(field.key()))) return false;
        return true;
    }
    if (left.is_array()) {
        for (size_t i=0;i<left.size();++i) if (!same_model_json(left.at(i),right.at(i))) return false;
        return true;
    }
    return left == right;
}
struct OcrModelConfig {
    std::filesystem::path root;
    ModelJson runtime, model, ocr;
    std::string name;
    int image_pad = -1;
    std::string prompt(const std::string& task) const {
        const auto& prompts = ocr.at("prompts");
        const auto key = prompts.contains(task) ? task : "text";
        return prompts.at(key).get<std::string>();
    }
};
inline ModelJson read_model_json(const std::filesystem::path& path) {
    if (!std::filesystem::is_regular_file(path) || std::filesystem::file_size(path) > 1048576)
        throw std::runtime_error("ocr_model_config_missing_or_too_large");
    std::ifstream input(path);
    auto value = ModelJson::parse(input);
    if (!value.is_object()) throw std::runtime_error("ocr_model_config_not_object");
    return value;
}
inline OcrModelConfig ocr_model_config(const std::vector<ArtifactInfo>& artifacts, int threads) {
    OcrModelConfig result;
    std::set<std::string> files;
    const ArtifactInfo* configuration = nullptr;
    for (const auto& artifact : artifacts) if (artifact.model == "recognition") {
        const auto path = std::filesystem::u8path(artifact.path);
        if (result.root.empty()) result.root = path.parent_path();
        if (result.root != path.parent_path() || artifact.contract_status != "contract_verified" ||
            !std::filesystem::is_regular_file(path) || !files.insert(path.filename().u8string()).second)
            throw std::runtime_error("ocr_artifact_binding_invalid");
        if (path.filename() == "ocr_runtime.json") configuration = &artifact;
        else if (path.filename() == "config.json" && !configuration) configuration = &artifact;
    }
    if (!configuration) throw std::runtime_error("ocr_model_config_missing");
    result.runtime = read_model_json(std::filesystem::u8path(configuration->path));
    if (!result.runtime.contains("ocr")) {
        // 旧目录的下载 config 没有 OCR 元数据；只兼容原固定 Ovis 工件。
        if (configuration->sha256 != "b81ac7008ba5f894301b7b9265bba882889df52c6e25c86390514c1bd4afe0c4")
            throw std::runtime_error("ocr_model_profile_missing");
        result.runtime.update(ModelJson::parse(legacy_ovis_profile));
    }
    auto file = [&](const char* field, const char* fallback) {
        const auto name = result.runtime.value(field, std::string(fallback));
        // 当前下载/工件绑定只接受同目录的模型文件，禁止跳出已校验工件集合。
        if (name.empty() || std::filesystem::u8path(name).filename().u8string() != name || !files.count(name))
            throw std::runtime_error(std::string("ocr_config_artifact_missing:") + field);
        return result.root / std::filesystem::u8path(name);
    };
    const auto model_config_path = file("llm_config", "llm_config.json");
    result.model = read_model_json(model_config_path);
    // 与固定 MNN 的合并顺序一致；所有最终文件引用仍须属于下载工件集合。
    result.runtime.update(result.model);
    if (file("llm_config", "llm_config.json") != model_config_path)
        throw std::runtime_error("ocr_model_config_redirect_invalid");
    result.ocr = result.runtime.at("ocr");
    result.name = result.ocr.at("name").get<std::string>();
    if (result.name.empty() || result.name.size() > 128 || !result.ocr.at("prompts").is_object())
        throw std::runtime_error("ocr_model_profile_invalid");
    for (const auto& task : {"text", "formula", "table"}) {
        const auto prompt = result.prompt(task);
        if (prompt.empty() || prompt.size() > 32768) throw std::runtime_error("ocr_task_prompt_invalid");
    }
    file("llm_model", "llm.mnn");file("llm_weight", "llm.mnn.weight");
    file("visual_model", "visual.mnn");file("visual_weight", "visual.mnn.weight");
    file("tokenizer_file", "tokenizer.txt");
    if (result.runtime.contains("embedding_file")) file("embedding_file", "embeddings_bf16.bin");
    if (!result.model.value("is_visual", false) || !result.model.contains("image_pad") ||
        !result.model.at("image_pad").is_number_integer() || !result.model.contains("jinja"))
        throw std::runtime_error("ocr_visual_model_config_invalid");
    result.image_pad = result.model.at("image_pad").get<int>();
    if (result.image_pad < 0) throw std::runtime_error("ocr_image_token_invalid");
    const std::set<std::string> samplers{"greedy", "temperature", "topK", "topP", "minP", "min_p", "tfs", "typical", "penalty", "mixed"};
    const auto sampler = result.runtime.value("sampler_type", "mixed");
    if (!samplers.count(sampler)) throw std::runtime_error("ocr_sampler_invalid");
    result.runtime["sampler_type"] = sampler;
    if (sampler == "mixed" && result.runtime.contains("mixed_samplers")) {
        const auto& mixed = result.runtime.at("mixed_samplers");
        if (!mixed.is_array() || mixed.empty()) throw std::runtime_error("ocr_mixed_samplers_invalid");
        for (const auto& item : mixed)
            if (!item.is_string() || item == "mixed" || !samplers.count(item.get<std::string>()))
                throw std::runtime_error("ocr_mixed_samplers_invalid");
    }
    const auto penalty_sampler = result.runtime.value("penalty_sampler", "greedy");
    if (penalty_sampler != "greedy" && penalty_sampler != "temperature")
        throw std::runtime_error("ocr_penalty_sampler_invalid");
    result.runtime["base_dir"] = result.root.u8string() + "/";
    result.runtime["backend_type"] = "cpu";result.runtime["thread_num"] = threads;
    result.runtime["reuse_kv"] = false;result.runtime["prompt_cache"] = false;
    result.runtime["use_mmap"] = false;result.runtime["kvcache_mmap"] = false;
    result.runtime["async"] = false;result.runtime["timeout_ms"] = 120000;
    auto& visual = result.runtime["mllm"];
    if (visual.is_null()) visual = ModelJson::object();
    if (!visual.is_object()) throw std::runtime_error("ocr_visual_runtime_invalid");
    visual["backend_type"] = "cpu";visual["thread_num"] = threads;
    return result;
}
inline std::string ocr_runtime_manifest(const ExecutionPlan& plan) {
    auto profile = ocr_model_config(plan.artifacts, plan.threads);
    const auto& config = profile.runtime;
    auto result = config;
    result["model_name"] = profile.name;
    result["model_type"] = profile.model.value("model_type", "unknown");
    result["image_pad"] = profile.image_pad;
    result["layout_threads"] = plan.threads;result["ovis_threads"] = plan.threads;
    result["recognition_threads"] = plan.threads;result["device"] = "cpu";
    result["sampler"] = config.at("sampler_type");
    if (!result.contains("seed")) result["seed"] = nullptr;
    result["seed_status"] = result.at("seed").is_null() ? "not_configured" : "not_supported_by_pinned_sampler";
    result["llm_precision"] = config.value("precision", "low");
    result["llm_memory"] = config.value("memory", "low");
    result["vision_precision"] = config.at("mllm").value("precision", "low");
    result["vision_memory"] = config.at("mllm").value("memory", "low");
    result["timeout_ms"] = plan.generation_timeout_ms;
    result["session_strategy"] = "shared_model_reset_before_each_region";
    result["prompt_sha256"] = sha256(profile.prompt("text"));
    result["configuration_source"] = "model_directory_config";
    return result.dump();
}
} // namespace dococr
