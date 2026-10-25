// uvdoc_unwarp.cpp —— UVDoc MNN 卷曲矫正（拍照裁剪的阶段 B增强）。
//
// 模型：ModelScope dr3334/UVDoc-mnn 的 uvdoc_int8_baseline_floathead_kl.mnn（INT8 混合精度）
//   输入 image_712x488：[1,3,712,488] float32 NCHW BGR，uint8/255
//   输出 grid_45x31：[1,2,45,31] float32 归一化反向采样网格（ch0=x ch1=y）
//
// 前后处理逐行对译自 UVDoc-mnn 仓库的 uvdoc_math.cpp（align_corners=true、
// zero padding）；README 明确 OpenCV 默认 resize/remap 坐标约定不保证逐点一致，
// 因此自带实现。图像进出走 Android Bitmap（RGBA），解码/编码由 Java 侧完成——
// 不用 stb：其实现单元已由 libdococr_core.a 独占，跨单元引用会 duplicate/undefined。
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <memory>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "UvdocUnwarp", __VA_ARGS__)

namespace {

// ---- 以下两个函数对译自 UVDoc-mnn 仓库 uvdoc_math.cpp（坐标约定逐行一致）----

// 网格采样：按归一化 grid（[-1,1] → 像素坐标）从 src 双线性取值，越界补零
void uvdoc_grid_sample(const unsigned char* src, int channels, int ih, int iw,
                       const float* grid, unsigned char* dst, int oh, int ow) {
    const size_t plane = size_t(oh)*ow;
    for (size_t pos = 0; pos < plane; ++pos) {
        const float fx = (grid[pos]+1.f)*float((iw-1)*0.5);
        const float fy = (grid[plane+pos]+1.f)*float((ih-1)*0.5);
        if (!std::isfinite(fx) || !std::isfinite(fy) || fx < -1.f || fx > iw || fy < -1.f || fy > ih) {
            for (int c=0;c<channels;++c) dst[size_t(c)*plane+pos]=0;
            continue;
        }
        const int x0=int(std::floor(fx)), y0=int(std::floor(fy));
        const int x1=x0+1,y1=y0+1;
        const float wx=fx-x0,wy=fy-y0;
        for (int c=0;c<channels;++c) {
            const unsigned char* in=src+size_t(c)*ih*iw;
            const unsigned char a=x0>=0&&x0<iw&&y0>=0&&y0<ih?in[size_t(y0)*iw+x0]:0;
            const unsigned char b=x1>=0&&x1<iw&&y0>=0&&y0<ih?in[size_t(y0)*iw+x1]:0;
            const unsigned char d=x0>=0&&x0<iw&&y1>=0&&y1<ih?in[size_t(y1)*iw+x0]:0;
            const unsigned char e=x1>=0&&x1<iw&&y1>=0&&y1<ih?in[size_t(y1)*iw+x1]:0;
            const float v = a*(1.f-wx)*(1.f-wy)+b*wx*(1.f-wy)+d*(1.f-wx)*wy+e*wx*wy;
            dst[size_t(c)*plane+pos] = (unsigned char)std::min(255.f, std::max(0.f, v + 0.5f));
        }
    }
}

// 网格双线性放大（45×31 → 目标尺寸）
void uvdoc_resize_grid(const float* src, int ih, int iw, float* dst, int oh, int ow) {
    const float ry = oh > 1 ? float(ih - 1) / float(oh - 1) : 0.f;
    const float rx = ow > 1 ? float(iw - 1) / float(ow - 1) : 0.f;
    for (int c = 0; c < 2; ++c) {
        const float* in = src + size_t(c) * ih * iw;
        float* out = dst + size_t(c) * oh * ow;
        for (int y = 0; y < oh; ++y) {
            const float fy = ry * y;
            const int y0 = int(fy), y1 = std::min(y0 + 1, ih - 1);
            const float wy = fy - y0;
            for (int x = 0; x < ow; ++x) {
                const float fx = rx * x;
                const int x0 = int(fx), x1 = std::min(x0 + 1, iw - 1);
                const float wx = fx - x0;
                out[size_t(y)*ow+x] = (1.f-wy)*((1.f-wx)*in[size_t(y0)*iw+x0]+wx*in[size_t(y0)*iw+x1])
                    + wy*((1.f-wx)*in[size_t(y1)*iw+x0]+wx*in[size_t(y1)*iw+x1]);
            }
        }
    }
}

} // namespace

// JNI：src（RGBA_8888，调用方已限长边 ≤1600）→ 网络推理 → 展平结果写入同尺寸 out。
// 返回 false 时调用方应回退用原图，不阻断识别链。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_doclayout_recognize_data_local_UvdocUnwarp_nativeUnwarp(
        JNIEnv* env, jclass, jstring modelPath, jobject srcBitmap, jobject outBitmap) {
    const char* model = env->GetStringUTFChars(modelPath, nullptr);
    bool ok = false;
    AndroidBitmapInfo info{};
    void* srcPixels = nullptr;
    void* outPixels = nullptr;
    std::unique_ptr<MNN::Interpreter> net;
    MNN::Session* session = nullptr;
    do {
        if (AndroidBitmap_getInfo(env, srcBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) { LOGE("src bitmap info failed"); break; }
        const int w = int(info.width), h = int(info.height);
        if (w < 2 || h < 2) { LOGE("bitmap too small"); break; }
        if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS) { LOGE("lock src failed"); break; }
        if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) { LOGE("lock out failed"); break; }

        // 1) 网络输入 [1,3,712,488] float NCHW BGR ×(1/255)：
        //    从 RGBA uint8 直接重采样（align_corners 双线性，公式与 uvdoc_resize 一致）
        const size_t netPlane = size_t(712)*488;
        std::vector<float> netIn(netPlane*3);
        {
            const float ry = float(h-1)/(712-1), rx = float(w-1)/(488-1);
            const float scale = 1.f/255.f;
            float* b = netIn.data(); float* g = netIn.data()+netPlane; float* r = netIn.data()+netPlane*2;
            const unsigned char* rgba = static_cast<const unsigned char*>(srcPixels);
            for (int y = 0; y < 712; ++y) {
                const float fy = ry*y; const int y0=int(fy), y1=std::min(y0+1,h-1); const float wy=fy-y0;
                for (int x = 0; x < 488; ++x) {
                    const float fx = rx*x; const int x0=int(fx), x1=std::min(x0+1,w-1); const float wx=fx-x0;
                    const size_t s00=(size_t(y0)*w+x0)*4,s01=(size_t(y0)*w+x1)*4;
                    const size_t s10=(size_t(y1)*w+x0)*4,s11=(size_t(y1)*w+x1)*4;
                    const float w00=(1.f-wx)*(1.f-wy),w01=wx*(1.f-wy),w10=(1.f-wx)*wy,w11=wx*wy;
                    const size_t o=size_t(y)*488+x;
                    b[o]=(rgba[s00+2]*w00+rgba[s01+2]*w01+rgba[s10+2]*w10+rgba[s11+2]*w11)*scale;
                    g[o]=(rgba[s00+1]*w00+rgba[s01+1]*w01+rgba[s10+1]*w10+rgba[s11+1]*w11)*scale;
                    r[o]=(rgba[s00+0]*w00+rgba[s01+0]*w01+rgba[s10+0]*w10+rgba[s11+0]*w11)*scale;
                }
            }
        }

        // 2) MNN 推理（会话参数照仓库 mnn_grid_runner：CPU + Precision_High）
        net.reset(MNN::Interpreter::createFromFile(model));
        if (!net) { LOGE("model load failed"); break; }
        MNN::ScheduleConfig config; config.type = MNN_FORWARD_CPU; config.numThread = 4;
        MNN::BackendConfig backend; backend.precision = MNN::BackendConfig::Precision_High;
        config.backendConfig = &backend;
        session = net->createSession(config);
        if (!session) { LOGE("session failed"); break; }
        auto inputTensor = net->getSessionInput(session, "image_712x488");
        auto outputTensor = net->getSessionOutput(session, "grid_45x31");
        if (!inputTensor || !outputTensor) { LOGE("tensor lookup failed"); break; }
        MNN::Tensor hostIn(inputTensor, MNN::Tensor::CAFFE);
        MNN::Tensor hostOut(outputTensor, MNN::Tensor::CAFFE);
        std::memcpy(hostIn.host<float>(), netIn.data(), netIn.size()*sizeof(float));
        if (!inputTensor->copyFromHostTensor(&hostIn)) { LOGE("copyIn failed"); break; }
        if (net->runSession(session) != MNN::NO_ERROR) { LOGE("runSession failed"); break; }
        if (!outputTensor->copyToHostTensor(&hostOut)) { LOGE("copyOut failed"); break; }
        const float* grid = hostOut.host<float>();
        bool finite = true;
        for (size_t k = 0; k < hostOut.elementSize(); ++k) if (!std::isfinite(grid[k])) { finite = false; break; }
        if (!finite) { LOGE("nonfinite grid"); break; }

        // 3) 反向网格 warp：src 是 RGBA NCHW（4 通道 uint8），展平结果写 out（同布局）
        const size_t plane = size_t(h)*w;
        std::vector<unsigned char> srcNchw(plane*4), outNchw(plane*4);
        {
            const unsigned char* rgba = static_cast<const unsigned char*>(srcPixels);
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    const size_t s=(size_t(y)*w+x)*4, o=size_t(y)*w+x;
                    srcNchw[0*plane+o]=rgba[s+2]; srcNchw[1*plane+o]=rgba[s+1];
                    srcNchw[2*plane+o]=rgba[s+0]; srcNchw[3*plane+o]=255;
                }
        }
        std::vector<float> gridFull(plane*2);
        uvdoc_resize_grid(grid, 45, 31, gridFull.data(), h, w);
        uvdoc_grid_sample(srcNchw.data(), 4, h, w, gridFull.data(), outNchw.data(), h, w);
        {
            unsigned char* rgba = static_cast<unsigned char*>(outPixels);
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) {
                    const size_t d=(size_t(y)*w+x)*4, o=size_t(y)*w+x;
                    rgba[d+2]=outNchw[0*plane+o]; rgba[d+1]=outNchw[1*plane+o];
                    rgba[d+0]=outNchw[2*plane+o]; rgba[d+3]=255;
                }
        }
        ok = true;
    } while (false);

    if (srcPixels) AndroidBitmap_unlockPixels(env, srcBitmap);
    if (outPixels) AndroidBitmap_unlockPixels(env, outBitmap);
    if (session) net->releaseSession(session);
    env->ReleaseStringUTFChars(modelPath, model);
    return ok;
}
