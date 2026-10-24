package cn.local.ocr;

/** 公共 C ABI 的薄适配；同步 run 只在识别工作线程调用。 */
final class NativeOcr {
    static { System.loadLibrary("dococr_jni"); }
    static native long create(String config, String cache) throws java.io.IOException;
    static native long jobCreate(long engine) throws java.io.IOException;
    static native int run(long job, String image) throws java.io.IOException;
    static native int runRegion(long job, String image, String task) throws java.io.IOException;
    static native String status(long job) throws java.io.IOException;
    static native void cancel(long job) throws java.io.IOException;
    static native void export(long job, String directory) throws java.io.IOException;
    static native void jobDestroy(long job) throws java.io.IOException;
    static native void destroy(long engine) throws java.io.IOException;
}
