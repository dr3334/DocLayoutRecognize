package cn.local.ocr;

import java.io.IOException;

/** 图片解码前的尺寸策略；模型区域预算由 native 层另行控制。 */
final class ImageResolution {
    static final int VERSION=1;
    static String validate(String policy) {
        if(!policy.equals("balanced")&&!policy.equals("fast")&&!policy.equals("original"))
            throw new IllegalArgumentException("未知图片分辨率设置");
        return policy;
    }
    static final class Plan {
        final int width,height,sampleSize;
        Plan(int width,int height,int sampleSize){this.width=width;this.height=height;this.sampleSize=sampleSize;}
    }
    static Plan plan(int width,int height,String policy)throws IOException {
        validate(policy);
        if(width<=0||height<=0||(long)width*height>64_000_000L)
            throw new IOException("图片不可读取或超过 6400 万像素");
        long pixels=policy.equals("fast")?4_000_000L:policy.equals("original")?64_000_000L:8_000_000L;
        int edge=policy.equals("fast")?2560:policy.equals("original")?Integer.MAX_VALUE:4096;
        double scale=Math.min(1,Math.min(edge/(double)Math.max(width,height),Math.sqrt(pixels/((double)width*height))));
        int targetWidth=Math.max(1,(int)Math.floor(width*scale));
        int targetHeight=Math.max(1,(int)Math.floor(height*scale));
        int sample=1;
        // 按 Android 的 2 次幂采样；解码后仍至少覆盖目标尺寸，避免再次放大损失细节。
        while(width/(sample*2)>=targetWidth&&height/(sample*2)>=targetHeight) sample*=2;
        return new Plan(targetWidth,targetHeight,sample);
    }
}
