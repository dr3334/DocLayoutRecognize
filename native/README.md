# 原生依赖

默认使用仓库内预编译 MNN，下载源码 ZIP 或普通克隆即可构建，不需要额外获取 MNN、Git 子模块或 Git LFS。

- `docprase/`：实际编译的模块化页面流水线源码快照。来源基线和文件 SHA 在 `dependencies.json`。保留原 LICENSE；Android 适配位于 `app/src/main/cpp`。
- `mnn-prebuilt/`：固定 MNN 3.6.1 提交的 Android arm64-v8a 运行库、必要头文件、许可及来源清单。四个 `.so` 合计约 4.5 MB；它们是运行时，不是 OCR 模型权重。
- 默认 CMake 仅编译 docprase 和 JNI，链接内附 MNN；配置时检查预编译文件 SHA。Gradle 将动态依赖和许可一并打包。

保持 Android API 26、NDK 28.1.13356709、`c++_shared`、Release／`-O3`／`NDEBUG`，仅支持 `arm64-v8a`。不要替换为 Linux 或不同 ABI/NDK 的库。

## 日常校验

```bash
python tools/setup_native_deps.py --check-only
```

此命令只校验仓库已有文件，不联网、不获取源码。构建不依赖先运行它。APK 校验还会比较内附库的实际 SHA、build ID、动态依赖及清单内的 Release 编译证据。

## 维护者：显式从源码构建

只有需要修改引擎或重新生成预编译包时才执行：

```bash
python tools/setup_native_deps.py --source
./gradlew -PmnnSourceRoot=native/MNN assembleUserDebug assembleLabDebug
```

Windows 使用 `gradlew.bat`。`mnnSourceRoot` 路径按项目根目录解析，也可传绝对路径。默认模式忽略源码目录，不自动寻找相邻 MNN。

需要更新内附库时，从源码模式构建后，找到对应 `app/.cxx/Release/<hash>/arm64-v8a`，执行：

```bash
python tools/mnn_prebuilt.py --source-root native/MNN --build-dir app/.cxx/Release/<hash>/arm64-v8a
```

脚本核对固定 Git 提交、工作区干净、NDK/API/分库、所有 MNN 编译单元的最终优化和调试宏，再复制头文件、运行库及许可，移除不必要符号并记录 SHA/build ID/动态依赖。只在正式发布工具检查通过并完成干净构建后更新这些二进制文件。改上游版本需同时更新 `dependencies.json` 和预编译包，不能只换 `.so`。

默认运行库构建采用 Android 官方 CMake imported library 接口：[Android 文档](https://developer.android.com/studio/projects/configure-cmake#add-other-library)。
