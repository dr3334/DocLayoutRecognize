package cn.local.ocr;

/** EXIF 1～8 的像素输入/输出契约；输出已去掉方向元数据。 */
public final class ImagePixels {
    public static int[] orient(int[] input,int width,int height,int orientation) {
        if(width<=0||height<=0||(long)width*height!=input.length) throw new IllegalArgumentException("图片尺寸无效");
        if(orientation<1||orientation>8) orientation=1;
        int outWidth=orientation>=5?height:width;
        int[] out=new int[input.length];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int dx=x,dy=y;
            switch(orientation) {
                case 2:dx=width-1-x;break;
                case 3:dx=width-1-x;dy=height-1-y;break;
                case 4:dy=height-1-y;break;
                case 5:dx=y;dy=x;break;
                case 6:dx=height-1-y;dy=x;break;
                case 7:dx=height-1-y;dy=width-1-x;break;
                case 8:dx=y;dy=width-1-x;break;
            }
            out[dy*outWidth+dx]=input[y*width+x];
        }
        return out;
    }
}
