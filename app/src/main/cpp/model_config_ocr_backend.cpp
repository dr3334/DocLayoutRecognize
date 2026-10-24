#include "backend_factory.hpp"
#include "config.hpp"
#include "android_engine_runtime.hpp"
#include "android_engine_loading.hpp"
#include "android_recognition_stream.hpp"
#include "model_ocr_config.hpp"
#include "visual_adaptation.hpp"
#include "llm/llm.hpp"
#include <MNN/Interpreter.hpp>
#define STB_IMAGE_WRITE_STATIC
#define STBIW_ONLY_PNG
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <random>
#include <sstream>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace dococr {
std::unique_ptr<IInferenceEngine> make_layout_mnn_backend();
namespace {
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;
class TempFile {
public:
    explicit TempFile(const char* prefix, const char* extension) {
        namespace fs = std::filesystem;
#ifdef _WIN32
        std::random_device random;
        for (int attempt = 0; attempt < 32; ++attempt) {
            const auto candidate = fs::temp_directory_path() /
                fs::u8path(std::string(prefix) + std::to_string(random()) + extension);
            HANDLE file = CreateFileW(candidate.c_str(), GENERIC_WRITE, 0, nullptr,
                                      CREATE_NEW, FILE_ATTRIBUTE_TEMPORARY, nullptr);
            if (file != INVALID_HANDLE_VALUE) {
                CloseHandle(file);
                path_ = candidate;
                utf8_path_ = path_.u8string();
                return;
            }
            if (GetLastError() != ERROR_FILE_EXISTS && GetLastError() != ERROR_ALREADY_EXISTS)
                break;
        }
#else
        std::string name = (fs::temp_directory_path() / fs::u8path(std::string(prefix) +
                            "XXXXXX" + extension)).u8string();
        std::vector<char> chars(name.begin(), name.end()); chars.push_back(0);
        int suffix = static_cast<int>(std::strlen(extension));
        int fd = mkstemps(chars.data(), suffix);
        if (fd >= 0) {
            path_ = fs::u8path(chars.data());
            utf8_path_ = path_.u8string();
            close(fd);
            return;
        }
#endif
        throw std::runtime_error("temporary_file_create_failed");
    }
    ~TempFile() { if (!path_.empty()) { std::error_code ec; std::filesystem::remove(path_, ec); } }
    const std::string& path() const { return utf8_path_; }
    void write(const std::string& data) const {
        std::ofstream out(path_, std::ios::binary | std::ios::trunc);
        out.write(data.data(), std::streamsize(data.size()));
        if (!out) throw std::runtime_error("temporary_file_write_failed");
    }
    void write(const std::vector<uint8_t>& data) const {
        write(std::string(reinterpret_cast<const char*>(data.data()), data.size()));
    }
private:
    std::filesystem::path path_;
    std::string utf8_path_;
};
void png_write(void* opaque, void* bytes, int size) {
    auto& data = *static_cast<std::vector<uint8_t>*>(opaque);
    auto* first = static_cast<uint8_t*>(bytes);
    data.insert(data.end(), first, first + size);
}
std::vector<uint8_t> png(const Image& image) {
    if (image.width <= 0 || image.height <= 0 ||
        image.rgb.size() != size_t(image.width) * image.height * 3)
        throw std::runtime_error("region_image_contract_mismatch");
    std::vector<uint8_t> bytes;
    if (!stbi_write_png_to_func(png_write, &bytes, image.width, image.height, 3,
                                image.rgb.data(), image.width * 3))
        throw std::runtime_error("region_png_encode_failed");
    return bytes;
}
class ModelConfigOcrBackend final : public IInferenceEngine {
public:
    bool load(const BackendLoadSpec& spec) override {
        unload(); error_.clear();
        try {
            if (spec.backend_id != "mnn:pp-doclayout-v3+ovisocr2" || spec.device != "cpu")
                throw std::runtime_error("ocr_backend_contract_mismatch");
            threads_ = android_inference_threads();
            profile_ = ocr_model_config(spec.artifacts, threads_);
            std::vector<ArtifactInfo> layout_artifacts;
            for (const auto& artifact : spec.artifacts)
                if (artifact.model == "layout") layout_artifacts.push_back(artifact);
                else if (artifact.model != "recognition") throw std::runtime_error("ocr_artifact_role_invalid");
            if (layout_artifacts.size() != 1) throw std::runtime_error("ocr_layout_artifact_missing");
            layout_ = make_layout_mnn_backend();
            BackendLoadSpec layout_spec{"mnn:pp-doclayout-v3", spec.config_hash, spec.device, layout_artifacts};
            if (!layout_->load(layout_spec)) throw std::runtime_error(layout_->last_error());
            std::string effective = profile_.runtime.dump();
            TempFile temporary("dococr-model-config-", ".json");
            temporary.write(effective);
            llm_.reset(Llm::createLLM(temporary.path()));
            // 构造时 MNN 再次合并 llm_config；加载前重设最终配置，落实 CPU 与关闭 mmap 的约定。
            if (!llm_ || !llm_->set_config(effective) || !load_android_llm(*llm_))
                throw std::runtime_error("ocr_model_load_failed");
            const auto loaded = ModelJson::parse(llm_->dump_config());
            const auto requested = ModelJson::parse(effective);
            for (auto field = requested.begin(); field != requested.end(); ++field)
                if (field.key() != "tmp_path" && (!loaded.contains(field.key()) || !same_model_json(loaded.at(field.key()),field.value())))
                    throw std::runtime_error("ocr_effective_config_mismatch:" + field.key());
            if (loaded.at("mllm").at("thread_num") != threads_ ||
                loaded.at("mllm").at("backend_type") != "cpu" || loaded.at("image_pad") != profile_.image_pad)
                throw std::runtime_error("ocr_effective_visual_config_mismatch");
            runtime_config_hash_ = sha256(llm_->dump_config());
            artifacts_ = spec.artifacts;
            return true;
        } catch (const std::exception& error) {
            error_ = error.what();unload();return false;
        }
    }
    std::vector<ArtifactInfo> loaded_artifacts() const override { return artifacts_; }
    const EngineCapabilities& capabilities() const override { return capabilities_; }
    std::string profile() const override {
        return "MNN/" + std::string(MNN::getVersion()) + "/PP-DocLayoutV3/" + profile_.name +
            "/ModelType=" + profile_.model.value("model_type", "unknown") +
            "/RuntimeConfig=" + runtime_config_hash_ + "/CPUThreads=" + std::to_string(threads_);
    }
    std::string last_error() const override { return error_; }
    InferenceResponse execute(const InferenceRequest& request, ExecutionContext& context) override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (std::holds_alternative<TensorRequest>(request.payload))
            return layout_->execute(request, context);
        auto* generation = std::get_if<GenerationRequest>(&request.payload);
        if (!llm_ || !generation) throw std::runtime_error("ocr_unavailable");
        using Clock = std::chrono::steady_clock;
        auto begin = Clock::now();
        GenerationOutput output;
        AndroidGenerationStream raw(*generation, threads_);
        if (context.cancelled) {
            output.finish_reason = "failed"; output.stop_reason = "cancelled";
            output.error = "cancelled"; return {output};
        }
        if (generation->max_new_tokens == 0 || generation->max_new_tokens > 4096)
            throw std::runtime_error("ocr_token_budget_unsupported");
        if (generation->generation_timeout_ms == 0 || generation->generation_timeout_ms > 120000)
            throw std::runtime_error("ocr_time_budget_unsupported");
        if (!llm_->set_config("{\"timeout_ms\":" + std::to_string(generation->generation_timeout_ms) + "}"))
            throw std::runtime_error("ocr_time_budget_config_failed");
        AdaptedVisual adapted;
        try {
            adapted = adapt_visual(generation->image, {generation->visual_min_pixels, generation->visual_max_pixels});
        } catch (const std::exception& error) {
            output.finish_reason = "failed"; output.stop_reason = "visual_adaptation_failed";
            output.error = error.what(); output.visual_evidence = "adaptation_failed";
            return {output};
        }
        output.visual_transform = adapted.transform;
        TempFile image("dococr-model-region-", ".png");
        image.write(png(adapted.canvas));
        // Omni::tokenizer_encode runs visual forward and returns image-pad IDs.
        // Reject an empty visual result before the language model can generate.
        const std::string user_content = "<img>" + image.path() + "</img>" + profile_.prompt(generation->task);
        std::string model_prompt = llm_->apply_chat_template(user_content);
        if (model_prompt.empty()) model_prompt = user_content;
        const auto tokens = llm_->tokenizer_encode(model_prompt);
        output.visual_tokens = uint32_t(std::count(tokens.begin(), tokens.end(), profile_.image_pad));
        if (output.visual_tokens == 0) {
            output.finish_reason = "failed"; output.stop_reason = "vision_missing";
            output.error = "ocr_visual_tokens_missing";
            output.visual_evidence = "no_visual_tokens";
            return {output};
        }
        output.visual_evidence = "image_pad_tokens";
        if (context.cancelled) {
            output.finish_reason = "failed"; output.stop_reason = "cancelled";
            output.error = "cancelled"; return {output};
        }
        // 模型写入由 AndroidGenerationStream 实时发布。
        const auto generation_start = Clock::now();
        // 首次 response 做 prefill + 一个 token；后续沿同一 KV 状态逐 token 推进。
        // 不通过 ostream 错误/异常中断 MNN，也不将循环误标为用户取消。
        llm_->response(tokens, &raw, nullptr, 1);
        for (uint64_t decoded = 1; decoded < generation->max_new_tokens; ++decoded) {
            const auto* current = llm_->getContext();
            if (context.cancelled || raw.repetition_detected() || !current ||
                current->status != LlmStatus::MAX_TOKENS_FINISHED) break;
            llm_->generate(1);
        }
        output.generation_elapsed_ms = uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-generation_start).count());
        output.raw_output = raw.str();
        output.elapsed_ms = uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-begin).count());
        const auto* state = llm_->getContext();
        if (context.cancelled) {
            output.finish_reason = "failed"; output.stop_reason = "cancelled";
            output.error = "cancelled";
        } else if (raw.repetition_detected()) {
            // 使用已有非正常终态，阻止扩大 token 预算重试；后续区域仍可重置后识别。
            output.finish_reason = "truncated"; output.stop_reason = "repetition";
            output.text = output.raw_output;
            output.error = "ocr_repetition_" + raw.repetition_reason();
        } else if (!state) {
            output.finish_reason = "failed"; output.stop_reason = "vision_missing";
            output.error = "ocr_visual_processing_missing";
        } else if (state->status == LlmStatus::NORMAL_FINISHED && !output.raw_output.empty()) {
            output.finish_reason = "complete"; output.stop_reason = "normal";
            output.text = output.raw_output;
        } else if (state->status == LlmStatus::NORMAL_FINISHED) {
            output.finish_reason = "failed"; output.stop_reason = "empty_output";
            output.error = "ocr_empty_output";
        } else if (state->status == LlmStatus::MAX_TOKENS_FINISHED) {
            output.finish_reason = "truncated"; output.stop_reason = "token_limit";
            output.text = output.raw_output; output.error = "ocr_token_limit";
        } else {
            output.finish_reason = "failed";
            output.stop_reason = state->status == LlmStatus::TIMEOUT ? "timeout" :
                state->status == LlmStatus::USER_CANCEL ? "cancelled" : "error";
            output.error = "ocr_runtime_" + output.stop_reason;
        }
        raw.finish(output, state);
        return {output};
    }
    bool reset() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!llm_) return false;
        llm_->reset();
        return true;
    }
    void unload() override {
        artifacts_.clear(); runtime_config_hash_.clear(); llm_.reset();
        if (layout_) layout_->unload(); layout_.reset();
    }
private:
    OcrModelConfig profile_;
    int threads_ = 1;
    EngineCapabilities capabilities_{true, true, true, 1};
    std::unique_ptr<IInferenceEngine> layout_;
    std::unique_ptr<Llm, void(*)(Llm*)> llm_{nullptr, Llm::destroy};
    std::vector<ArtifactInfo> artifacts_;
    std::string error_;
    std::string runtime_config_hash_;
    std::mutex mutex_;
};
}
std::unique_ptr<IInferenceEngine> make_printed_page_mnn_backend() {
    return std::make_unique<ModelConfigOcrBackend>();
}
}
