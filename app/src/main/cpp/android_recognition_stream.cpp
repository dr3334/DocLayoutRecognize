#include "android_recognition_stream.hpp"
#include "config.hpp"
#include <llm/llm.hpp>
#include <chrono>
#include <mutex>
#include <unordered_map>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {
using Clock = std::chrono::steady_clock;
thread_local DocOcrJob running_job = 0;
struct Region {
    std::string id, type, raw, finish, stop;
    int attempt = 0, threads = 1;
    bool done = false;
    Clock::time_point started;
    int64_t first_ms = -1, elapsed_ms = 0, vision_us = 0, prefill_us = 0, decode_us = 0;
    size_t tokens = 0;
};
struct Stream { uint64_t revision = 0; std::vector<Region> regions; };
std::mutex stream_mutex;
std::unordered_map<DocOcrJob, Stream> streams;
int64_t elapsed(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now()-start).count();
}
Region& region(Stream& stream, const std::string& id) {
    for(auto& item:stream.regions) if(item.id == id) return item;
    stream.regions.push_back({});return stream.regions.back();
}
// token 写入可能拆开一个 UTF-8 字符；只在快照中隐藏末尾不完整字符，保留原始字节。
std::string valid_utf8(const std::string& raw) {
    std::string out;out.reserve(raw.size());
    for(size_t i=0;i<raw.size();) {
        unsigned char ch=raw[i];size_t n=ch<0x80?1:ch>=0xc2&&ch<=0xdf?2:ch>=0xe0&&ch<=0xef?3:ch>=0xf0&&ch<=0xf4?4:0;
        if(!n){out+="\xef\xbf\xbd";++i;continue;}
        if(i+n>raw.size())break;
        bool valid=true;
        for(size_t j=1;j<n;++j) if((static_cast<unsigned char>(raw[i+j])&0xc0)!=0x80)valid=false;
        if(n>=3){unsigned char next=raw[i+1];if((ch==0xe0&&next<0xa0)||(ch==0xed&&next>=0xa0)||(ch==0xf0&&next<0x90)||(ch==0xf4&&next>=0x90))valid=false;}
        if(!valid){out+="\xef\xbf\xbd";++i;continue;}
        out.append(raw,i,n);i+=n;
    }
    return out;
}
}
extern "C" DocOcrStatus android_ocr_run_streaming(DocOcrJob job,const DocOcrInput* input) {
    struct Binding { DocOcrJob previous; Binding(DocOcrJob job):previous(running_job){running_job=job;}~Binding(){running_job=previous;} } binding(job);
    return dococr_job_run(job,input);
}
extern "C" void android_ocr_stream_forget(DocOcrJob job) {
    std::lock_guard<std::mutex> lock(stream_mutex);streams.erase(job);
}
extern "C" const char* android_ocr_stream_snapshot(DocOcrJob job) {
    thread_local std::string snapshot;
    Stream copy;
    {std::lock_guard<std::mutex> lock(stream_mutex);auto found=streams.find(job);if(found!=streams.end())copy=found->second;}
    snapshot="{\"revision\":"+std::to_string(copy.revision)+",\"regions\":[";
    bool first=true;
    for(const auto& r:copy.regions) {
        if(!first)snapshot+=",";first=false;
        snapshot+="{\"threads\":"+std::to_string(r.threads)+",\"requestId\":"+dococr::json_quote(r.id)+",\"type\":"+dococr::json_quote(r.type)+
            ",\"attempt\":"+std::to_string(r.attempt)+",\"raw\":"+dococr::json_quote(valid_utf8(r.raw))+
            ",\"done\":"+(r.done?"true":"false")+",\"finishReason\":"+dococr::json_quote(r.finish)+
            ",\"stopReason\":"+dococr::json_quote(r.stop)+
            ",\"firstContentMs\":"+std::to_string(r.first_ms)+",\"elapsedMs\":"+std::to_string(r.done?r.elapsed_ms:elapsed(r.started))+
            ",\"visionMs\":"+std::to_string(r.vision_us/1000)+",\"prefillMs\":"+std::to_string(r.prefill_us/1000)+
            ",\"decodeMs\":"+std::to_string(r.decode_us/1000)+",\"outputTokens\":"+std::to_string(r.tokens)+"}";
    }
    snapshot+="]}";return snapshot.c_str();
}
namespace dococr {
AndroidGenerationStream::AndroidGenerationStream(const GenerationRequest& request, int threads)
    :std::ostream(this),request_(request.request_id),job_(running_job),loop_guard_(request.task == "table") {
    if(!job_)return;
    std::lock_guard<std::mutex> lock(stream_mutex);auto& s=streams[job_];auto& r=region(s,request_);
    int attempt=r.attempt+1;r=Region{};r.id=request_;r.type=request.task;r.attempt=attempt;r.threads=threads;r.started=Clock::now();++s.revision;
}
AndroidGenerationStream::~AndroidGenerationStream() {
    if(!job_||finished_)return;
    std::lock_guard<std::mutex> lock(stream_mutex);auto& s=streams[job_];auto& r=region(s,request_);
    r.done=true;r.finish="failed";r.elapsed_ms=elapsed(r.started);++s.revision;
}
std::streamsize AndroidGenerationStream::xsputn(const char* data,std::streamsize size) {
    if(size<=0)return 0;
    if(loop_guard_.detected())return size;
    raw_.append(data,static_cast<size_t>(size));
    loop_guard_.append(data,static_cast<size_t>(size));
    if(job_) {
        std::lock_guard<std::mutex> lock(stream_mutex);auto& s=streams[job_];auto& r=region(s,request_);
        if(r.first_ms<0)r.first_ms=elapsed(r.started);
        r.raw.append(data,static_cast<size_t>(size));++s.revision;
    }
    return size;
}
AndroidGenerationStream::int_type AndroidGenerationStream::overflow(int_type ch) {
    if(traits_type::eq_int_type(ch,traits_type::eof()))return traits_type::not_eof(ch);
    char value=traits_type::to_char_type(ch);xsputn(&value,1);return ch;
}
void AndroidGenerationStream::finish(const GenerationOutput& output,const MNN::Transformer::LlmContext* context) {
    finished_=true;if(!job_)return;
    std::lock_guard<std::mutex> lock(stream_mutex);auto& s=streams[job_];auto& r=region(s,request_);
    r.done=true;r.finish=output.finish_reason;r.stop=output.stop_reason;r.elapsed_ms=output.elapsed_ms;
    if(context){r.vision_us=context->vision_us;r.prefill_us=context->prefill_us;r.decode_us=context->decode_us;r.tokens=context->output_tokens.size();}
    ++s.revision;
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO,"OcrEngine","%s attempt=%d backend=cpu threads=%d first=%lldms vision=%lldms prefill=%lldms decode=%lldms tokens=%zu total=%lldms",
        r.id.c_str(),r.attempt,r.threads,(long long)r.first_ms,(long long)(r.vision_us/1000),(long long)(r.prefill_us/1000),(long long)(r.decode_us/1000),r.tokens,(long long)r.elapsed_ms);
#endif
}
}
