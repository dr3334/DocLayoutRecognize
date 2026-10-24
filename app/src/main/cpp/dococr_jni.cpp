#include <jni.h>
#include "android_recognition_stream.hpp"
#include "android_region_recognition.hpp"
#include <dococr/dococr.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
#include <cstdlib>
#include <stdexcept>
#include <memory>

#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace {
struct String {
    JNIEnv* env; jstring value; const char* data;
    String(JNIEnv* e, jstring v) : env(e), value(v), data(e->GetStringUTFChars(v, nullptr)) {}
    ~String() { if(data) env->ReleaseStringUTFChars(value, data); }
};
struct Bytes {
    DocOcrBytes value{};
    ~Bytes() { if(value.data) dococr_bytes_free(&value); }
};
void check(DocOcrStatus status) {
    if(status == DOCOCR_OK) return;
    Bytes error;
    dococr_last_error(&error.value);
    throw std::runtime_error("原生 OCR 错误 " + std::to_string(status) + ": " +
        (error.value.data ? std::string(reinterpret_cast<char*>(error.value.data), error.value.size) : "请重试或检查模型"));
}
void fail(JNIEnv* env, const std::exception& e) { env->ThrowNew(env->FindClass("java/io/IOException"), e.what()); }
void write(const std::filesystem::path& path, const DocOcrBytes& bytes) {
    std::filesystem::create_directories(path.parent_path());
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(bytes.data), bytes.size);
    out.close();
    if(!out) throw std::runtime_error("识别结果保存失败");
}
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_local_ocr_NativeOcr_create(JNIEnv* env,jclass,jstring config,jstring cache) {
    try { String c(env,config), t(env,cache); setenv("TMPDIR",t.data,1);
        DocOcrHandle engine=0; check(dococr_create({c.data,std::char_traits<char>::length(c.data)},&engine)); return engine;
    } catch(const std::exception& e) { fail(env,e); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_cn_local_ocr_NativeOcr_jobCreate(JNIEnv* env,jclass,jlong engine) {
    try { DocOcrJob job=0; check(dococr_job_create(engine,&job)); return job; }
    catch(const std::exception& e) { fail(env,e); return 0; }
}
static jint run_image(JNIEnv* env,jlong job,jstring image,const char* task) {
    try { String path(env,image); std::ifstream in(path.data,std::ios::binary|std::ios::ate);
        if(!in) throw std::runtime_error("无法读取已保存的输入");
        auto size=in.tellg(); if(size<=0||size>256*1024*1024) throw std::runtime_error("输入图片大小无效");
        std::vector<uint8_t> bytes(static_cast<size_t>(size)); in.seekg(0); in.read(reinterpret_cast<char*>(bytes.data()),bytes.size());
        if(!in) throw std::runtime_error("读取输入图片失败");
        DocOcrInput input{}; input.struct_size=sizeof(input); input.data=bytes.data(); input.size=bytes.size(); input.format=DOCOCR_IMAGE_PNG;
        // Android ARGB_8888 的规范 PNG 含 Alpha；引擎的编码图片入口仅接受灰度/RGB。
        // 在 JNI 适配为白纸背景的 RGB8，也兼容修复前已落盘的 source.png。
        int width=0,height=0,channels=0;
        std::vector<uint8_t> rgb;
        if(bytes.size()<=64*1024*1024 &&
           stbi_info_from_memory(bytes.data(),static_cast<int>(bytes.size()),&width,&height,&channels) &&
           (channels==2||channels==4) && width>0 && height>0 &&
           uint64_t(width)*height<=64'000'000 &&
           !stbi_is_16_bit_from_memory(bytes.data(),static_cast<int>(bytes.size()))) {
            std::unique_ptr<stbi_uc,void(*)(void*)> rgba(
                stbi_load_from_memory(bytes.data(),static_cast<int>(bytes.size()),&width,&height,&channels,4),stbi_image_free);
            if(rgba) {
                size_t pixels=static_cast<size_t>(width)*height;rgb.resize(pixels*3);
                for(size_t i=0;i<pixels;++i) {
                    unsigned alpha=rgba.get()[i*4+3];
                    for(size_t c=0;c<3;++c)
                        rgb[i*3+c]=static_cast<uint8_t>((rgba.get()[i*4+c]*alpha+255*(255-alpha)+127)/255);
                }
                input.data=rgb.data();input.size=rgb.size();input.format=DOCOCR_IMAGE_RGB8;
                input.width=width;input.height=height;input.row_stride=static_cast<size_t>(width)*3;
            }
        }
        return task ? android_ocr_run_region(job,&input,task) : android_ocr_run_streaming(job,&input);
    } catch(const std::exception& e) { fail(env,e); return DOCOCR_FAILED; }
}
extern "C" JNIEXPORT jint JNICALL Java_cn_local_ocr_NativeOcr_run(JNIEnv* env,jclass,jlong job,jstring image) {
    return run_image(env,job,image,nullptr);
}
extern "C" JNIEXPORT jint JNICALL Java_cn_local_ocr_NativeOcr_runRegion(JNIEnv* env,jclass,jlong job,jstring image,jstring task) {
    String type(env,task);return run_image(env,job,image,type.data);
}
extern "C" JNIEXPORT jstring JNICALL Java_cn_local_ocr_NativeOcr_status(JNIEnv* env,jclass,jlong job) {
    try {
        std::string latest;
        for(;;) { Bytes event; auto code=dococr_job_next_event(job,&event.value);
            if(code==DOCOCR_NO_RESULT) break;
            check(code); latest.assign(reinterpret_cast<char*>(event.value.data),event.value.size);
        }
        Bytes bytes; check(dococr_job_status(job,&bytes.value));
        std::string value(reinterpret_cast<char*>(bytes.value.data),bytes.value.size);
        if(!latest.empty()) { value.pop_back();value+=",\"latestEvent\":"+latest+"}"; }
        value.pop_back();value+=",\"stream\":"+std::string(android_ocr_stream_snapshot(job))+"}";
        // NewStringUTF 使用 modified UTF-8，不能直接传模型生成的四字节 emoji。
        auto data=env->NewByteArray(static_cast<jsize>(value.size()));
        env->SetByteArrayRegion(data,0,static_cast<jsize>(value.size()),reinterpret_cast<const jbyte*>(value.data()));
        auto cls=env->FindClass("java/lang/String");
        auto ctor=env->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");
        auto charset=env->NewStringUTF("UTF-8");
        auto result=static_cast<jstring>(env->NewObject(cls,ctor,data,charset));
        env->DeleteLocalRef(data);env->DeleteLocalRef(charset);env->DeleteLocalRef(cls);return result;
    }
    catch(const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_cn_local_ocr_NativeOcr_cancel(JNIEnv* env,jclass,jlong job) {
    try { check(dococr_job_cancel(job)); } catch(const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT void JNICALL Java_cn_local_ocr_NativeOcr_export(JNIEnv* env,jclass,jlong job,jstring directory) {
    try { String dir(env,directory); std::filesystem::path root(dir.data);
        DocOcrResult result{}; result.struct_size=sizeof(result);
        check(dococr_job_result(job,&result));
        Bytes json,md; json.value=result.json; md.value=result.markdown;
        write(root/"document.json",json.value); write(root/"document.md",md.value);
        Bytes manifest; check(dococr_job_manifest(job,&manifest.value)); write(root/"run-manifest.json",manifest.value);
        size_t count=0; check(dococr_job_asset_count(job,&count));
        for(size_t i=0;i<count;++i) { Bytes name,data; check(dococr_job_asset(job,i,&name.value,&data.value));
            std::filesystem::path relative(std::string(reinterpret_cast<char*>(name.value.data),name.value.size));
            if(relative.empty()||relative.is_absolute()) throw std::runtime_error("无效识别资源路径");
            for(const auto& part:relative) if(part=="..") throw std::runtime_error("无效识别资源路径");
            write(root/relative,data.value);
        }
    } catch(const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT void JNICALL Java_cn_local_ocr_NativeOcr_jobDestroy(JNIEnv* env,jclass,jlong job) {
    try { check(dococr_job_destroy(job)); android_ocr_stream_forget(job); } catch(const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT void JNICALL Java_cn_local_ocr_NativeOcr_destroy(JNIEnv* env,jclass,jlong engine) {
    try { check(dococr_destroy(engine)); } catch(const std::exception& e) { fail(env,e); }
}
