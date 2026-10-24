#include "android_region_recognition.hpp"
#include "android_recognition_stream.hpp"
#include "config.hpp"
#include "image_resources.hpp"
#include "document_export.hpp"
#include "page_identity.hpp"
#include "nlohmann/json.hpp"
#include <chrono>

namespace {
thread_local std::string region_task;
bool supported(const std::string& task) { return task == "text" || task == "table" || task == "formula"; }
}
extern "C" DocOcrStatus android_ocr_run_region(DocOcrJob job, const DocOcrInput* input, const char* task) {
    if (!task || !supported(task) || !input || input->format == DOCOCR_DOCUMENT_PDF) return DOCOCR_INVALID_ARGUMENT;
    struct Binding {
        std::string previous = region_task;
        explicit Binding(const char* task) { region_task = task; }
        ~Binding() { region_task = previous; }
    } binding(task);
    return android_ocr_run_streaming(job, input);
}
namespace dococr {
RunResult android_run_page(IInferenceEngine* backend, InputView input, std::atomic_bool& cancelled,
    const ExecutionPlan* plan, uint32_t source_page, const ProgressCallback& progress) {
    if (region_task.empty()) return run_page(backend, input, cancelled, plan, source_page, progress);
    if (!plan) return {RunCode::Failed, {}};
    return recognize_saved_region(backend, input, region_task, cancelled, *plan, progress);
}
RunResult recognize_saved_region(IInferenceEngine* backend, InputView input, const std::string& task,
    std::atomic_bool& cancelled, const ExecutionPlan& plan, const ProgressCallback& progress) {
    RunResult result{RunCode::Failed, {}};
    if (!backend || !supported(task)) { result.error_code = "invalid_region_task"; return result; }
    if (cancelled) { result.code = RunCode::Cancelled; return result; }
    const auto decode_started = std::chrono::steady_clock::now();
    auto image = image_resources::decode(input, plan.max_page_pixels);
    result.decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-decode_started).count();
    if (!image) { result.code = RunCode::InputError; return result; }
    result.did_decode = true; result.page_pixels = uint64_t(image->width) * image->height;
    DocumentBlock block;
    block.id = "b0001"; block.layout_id = "l0001"; block.region_id = "r0001";
    block.type = task; block.box = block.layout_box = {0, 0, image->width, image->height};
    block.resource = "assets/p0001-b0001.png";
    ExecutionContext context{cancelled};
    if (progress) progress("region_started", 1, "reqr0001", 0, 1);
    const auto started = std::chrono::steady_clock::now();
    // 输入已经是保存的识别区域，直接复用区域的会话、重试、循环保护与终态判定。
    block.recognition = recognize_region(*backend, *image, block.box, task, "reqr0001", plan, context);
    block.result = finalize_region_recognition(task, block.recognition);
    result.recognition_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();
    result.did_recognition = true; result.did_reset = true; result.reset_failed = block.recognition.reset_failed;
    result.regions.push_back({"reqr0001", block.result.status, block.result.assessment.stop_reason,
        result.recognition_ms, recognition_json(block.recognition)});
    if (cancelled) { result.code = RunCode::Cancelled; return result; }
    if (progress) progress("region_completed", 1, "reqr0001", 1, 1);
    const std::string state = block.result.status == "ok" ? "ok" : "partial";
    std::vector<DocumentBlock> blocks{std::move(block)};
    if (progress) progress("export_started", 1, "", 1, 1);
    const auto export_started = std::chrono::steady_clock::now();
    auto document = export_document_page({{image->width, image->height, 0, document_id(*image)}, blocks, state, backend->profile()});
    // 通用裁图导出不包含版面证据；额外保存真实尝试和停止原因供校对诊断。
    auto ir = nlohmann::json::parse(document.json);
    auto& provenance = ir["pages"][0]["blocks"][0]["provenance"];
    provenance["recognition"] = nlohmann::json::parse(recognition_json(blocks[0].recognition));
    const auto& assessment = blocks[0].result.assessment;
    provenance["assessment"] = {{"policy", assessment_policy}, {"state", assessment.state},
        {"reason", assessment.reason}, {"finish_reason", assessment.finish_reason},
        {"stop_reason", assessment.stop_reason}, {"evidence_source", assessment.evidence_source}};
    document.json = ir.dump();
    JobOutput output{std::move(document.json), std::move(document.markdown),
        {{blocks[0].resource, image_resources::crop_png(*image, blocks[0].box)}}};
    result.export_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-export_started).count();
    const auto bytes = output.json.size() + output.markdown.size() + output.assets[0].png.size();
    if (bytes > plan.max_output_bytes) { result.code = RunCode::BudgetExceeded; result.budget_stage = "output_bytes"; return result; }
    if (cancelled) { result.code = RunCode::Cancelled; return result; }
    result.output = std::move(output); result.did_export = true;
    result.code = state == "ok" ? RunCode::Ok : RunCode::Partial;
    if (progress) progress("export_completed", 1, "", 1, 1);
    return result;
}
}
