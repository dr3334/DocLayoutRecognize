# 内附 MNN Android 运行库

本目录作为普通 Git 文件提交，下载 GitHub 源码 ZIP 即包含，不需另行获取或编译 MNN。

来源：MNN 3.6.1，固定提交 `baaa5a62e9cc6d5b3660e37f8a2608a2d585adc6`，仓库 https://github.com/bestkojima/MNN 。

- `include/MNN`、`include/llm`：构建宿主后端所需的同版本公共头文件。
- `lib/arm64-v8a`：MNN、Express、MNNOpenCV 和 LLM 四个 Android 动态库。
- `manifest.json`：来源、ABI/API/NDK、各编译单元的优化参数和宏、工件 SHA、build ID 和动态依赖。
- `LICENSE.txt`、`licenses/`：MNN 及随运行库使用的第三方许可；构建时一并进入 APK。

使用固定 NDK 28.1.13356709，API 26，c++_shared，Release/-O3/NDEBUG；不含模型权重和 libc++ 运行库（后者由 NDK 提供）。维护/重建方式见上级 README。
