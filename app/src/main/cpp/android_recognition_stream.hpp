#pragma once
#include <dococr/dococr.h>
#include <dococr/inference.hpp>
namespace MNN { namespace Transformer { struct LlmContext; } }
#include <ostream>
#include <streambuf>
#include <string>
#include "generation_loop_guard.hpp"

// Android 自有扩展；不改变 docprase 公共 ABI。返回字符串由线程拥有，下一次读取前有效。
extern "C" {
DocOcrStatus android_ocr_run_streaming(DocOcrJob job, const DocOcrInput* input);
const char* android_ocr_stream_snapshot(DocOcrJob job);
void android_ocr_stream_forget(DocOcrJob job);
}
namespace dococr {
class AndroidGenerationStream : private std::streambuf, public std::ostream {
public:
    explicit AndroidGenerationStream(const GenerationRequest& request, int threads = 1);
    ~AndroidGenerationStream();
    std::string str() const { return raw_; }
    bool repetition_detected() const { return loop_guard_.detected(); }
    const std::string& repetition_reason() const { return loop_guard_.reason(); }
    void finish(const GenerationOutput& output, const MNN::Transformer::LlmContext* context);
private:
    using int_type = std::streambuf::int_type;
    using traits_type = std::streambuf::traits_type;
    std::streamsize xsputn(const char* data, std::streamsize size) override;
    int_type overflow(int_type ch) override;
    std::string raw_, request_;
    DocOcrJob job_;
    bool finished_ = false;
    GenerationLoopGuard loop_guard_;
};
}
