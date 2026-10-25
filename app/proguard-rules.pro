# DocLayoutRecognize 的 R8 规则。
#
# 本文件刻意保持接近空：应用反射面为零（2026-10-25 r8-analyzer 排查）——
#   - JNI：cn.local.ocr.NativeOcr 的 native 方法由 AGP 默认规则
#     -keepclasseswithmembernames class * { native <methods>; } 保留；
#     C++ 侧（dococr_jni.cpp）只 FindClass 平台类，无按名回调应用类
#   - WebView JS 桥：OcrMarkdownView.AndroidBridge 的 @JavascriptInterface
#     由 AGP 默认规则保留
#   - Manifest 组件（RecognitionService / :ocr 进程）：aapt 生成的规则保留
#   - IRemoteOcr 是手工 Binder（字符串 DESCRIPTOR，不按类名反射）
#   - JSON 解析全走 org.json 手工字段访问
#
# 下面只放构建期确需的 -dontwarn（按 release 构建的实际警告逐条补，
# 不做一刀切 dontwarn——那会掩盖真实的缺类问题）。

# 预期会出现警告的库按需追加在这里，例如：
# -dontwarn some.library.**
