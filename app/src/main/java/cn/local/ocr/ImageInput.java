package cn.local.ocr;
import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;
import org.json.*;

final class ImageInput {
    static int[] normalize(File input,File output) throws Exception {
        Result result=normalize(input,output,"balanced");return new int[]{result.width,result.height};
    }
    static final class Result {
        final int width,height;final JSONObject metadata;
        Result(int width,int height,JSONObject metadata){this.width=width;this.height=height;this.metadata=metadata;}
    }
    static Result normalize(File input,File output,String policy) throws Exception {
        long started=android.os.SystemClock.elapsedRealtime();
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),bounds);
        ImageResolution.Plan plan=ImageResolution.plan(bounds.outWidth,bounds.outHeight,policy);
        int orientation=1;
        try { orientation=new ExifInterface(input.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1); } catch(IOException ignored) {}
        if(orientation<1||orientation>8)orientation=1;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=plan.sampleSize;options.inScaled=false;
        Bitmap source=null,normalized=null;
        try {
            source=BitmapFactory.decodeFile(input.getPath(),options);
            if(source==null) throw new IOException("图片解码失败，原始输入已保留");
            int decodedWidth=source.getWidth(),decodedHeight=source.getHeight();
            if(decodedWidth!=plan.width||decodedHeight!=plan.height) {
                Bitmap scaled=Bitmap.createScaledBitmap(source,plan.width,plan.height,true);
                if(scaled!=source){source.recycle();source=scaled;}
            }
            int w=source.getWidth(),h=source.getHeight();
            int ow=orientation>=5&&orientation<=8?h:w,oh=orientation>=5&&orientation<=8?w:h;
            if(orientation==1)normalized=source;
            else {
                int[] pixels=new int[w*h];source.getPixels(pixels,0,w,0,0,w,h);
                int[] oriented=ImagePixels.orient(pixels,w,h,orientation);source.recycle();
                normalized=Bitmap.createBitmap(oriented,ow,oh,Bitmap.Config.ARGB_8888);
            }
            output.getParentFile().mkdirs();
            Bitmap image=normalized;
            atomicSave(output,out->{if(!image.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("保存规范图片失败");});
            long elapsed=android.os.SystemClock.elapsedRealtime()-started;
            JSONObject metadata=FilesUtil.obj("version",ImageResolution.VERSION,"policy",policy,
                "rawWidth",bounds.outWidth,"rawHeight",bounds.outHeight,"orientation",orientation,
                "sampleSize",plan.sampleSize,"decodedWidth",decodedWidth,"decodedHeight",decodedHeight,
                "width",ow,"height",oh,"elapsedMs",elapsed);
            android.util.Log.i("OcrEngine","Image preparation: "+metadata);
            return new Result(ow,oh,metadata);
        } catch(OutOfMemoryError e) {
            throw new IOException("图片处理内存不足，请选择快速分辨率后重试",e);
        } finally { if(source!=null&&!source.isRecycled()) source.recycle();if(normalized!=null&&!normalized.isRecycled()) normalized.recycle(); }
    }
    static String policy(android.content.Context context) {
        return ImageResolution.validate(context.getSharedPreferences("settings",0).getString("imageResolution","balanced"));
    }
    static JSONObject prepare(DocumentStore store,JSONObject doc,String policy)throws Exception {
        return prepare(store,doc,policy,ImageInput::normalize);
    }
    interface Normalizer { Result normalize(File input,File output,String policy)throws Exception; }
    static JSONObject prepare(DocumentStore store,JSONObject doc,String policy,Normalizer normalizer)throws Exception {
        ImageResolution.validate(policy);
        String path=doc.getString("input");File output=FilesUtil.child(store.dir(doc.getString("id")),path);
        JSONObject previous=doc.optJSONObject("imagePreparation");
        if(output.isFile()&&previous!=null&&previous.optInt("version")==ImageResolution.VERSION&&policy.equals(previous.optString("policy")))return doc;
        File raw=doc.has("rawInput")?FilesUtil.child(store.dir(doc.getString("id")),doc.getString("rawInput")):output;
        Result result=normalizer.normalize(raw,output,policy);
        // 大图解码／压缩不持有存储锁；提交时重新读取，保留期间到达的取消状态。
        synchronized(store) {
            JSONObject latest=store.load(doc.getString("id"));
            latest.put("original",store.url(doc.getString("id"),path)).put("imagePreparation",result.metadata);
            latest.getJSONObject("assets").put(path,FilesUtil.obj("src",latest.getString("original"),"width",result.width,"height",result.height));
            JSONArray pages=latest.getJSONArray("pages");
            if(pages.length()==0)pages.put(FilesUtil.obj("number",1,"route","单图本地识别"));
            pages.getJSONObject(0).put("source",latest.getString("original")).put("width",result.width).put("height",result.height);
            store.save(latest);return latest;
        }
    }
    interface ImageWriter { void write(OutputStream output) throws Exception; }
    static void atomicSave(File output,ImageWriter writer) throws Exception {
        output.getParentFile().mkdirs();
        File temporary=File.createTempFile("normalized-",".tmp",output.getParentFile());
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)){writer.write(out);out.getFD().sync();}
            java.nio.file.Files.move(temporary.toPath(),output.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
}
