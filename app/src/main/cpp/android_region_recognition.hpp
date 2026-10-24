#pragma once
#include <dococr/dococr.h>
#include <dococr/inference.hpp>

// Android 专用入口，仍使用公共作业的占用、取消、清理和资源所有权规则。
extern "C" DocOcrStatus android_ocr_run_region(DocOcrJob job, const DocOcrInput* input, const char* task);
namespace dococr {
RunResult android_run_page(IInferenceEngine* backend, InputView input, std::atomic_bool& cancelled,
    const ExecutionPlan* plan, uint32_t source_page, const ProgressCallback& progress);
RunResult recognize_saved_region(IInferenceEngine* backend, InputView input, const std::string& task,
    std::atomic_bool& cancelled, const ExecutionPlan& plan, const ProgressCallback& progress = {});
}
