package cn.local.ocr;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.*;

public final class FilesUtil {
    public static final long IMPORT_LIMIT=256L*1024*1024;
    public static String read(File f) throws Exception { try(InputStream in=new FileInputStream(f)){return new String(bytes(in,32L*1024*1024),StandardCharsets.UTF_8);} }
    public static byte[] bytes(InputStream in,long limit) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out,limit);return out.toByteArray();}
    public static void copy(InputStream in,OutputStream out,long limit) throws Exception {byte[] b=new byte[65536];long n=0;int r;while((r=in.read(b))!=-1){n+=r;if(n>limit)throw new IOException("文件超过允许大小");out.write(b,0,r);}}
    public static void write(File f,String s) throws Exception {f.getParentFile().mkdirs();File tmp=new File(f+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(s.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}java.nio.file.Files.move(tmp.toPath(),f.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    public static File child(File root,String name) throws Exception {File f=new File(root,name).getCanonicalFile();if(!f.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("无效资源路径");return f;}
    public static void remove(File f){if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)remove(x);}f.delete();}
    public static String sha(File f) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    public static String hex(byte[] a){StringBuilder b=new StringBuilder();for(byte v:a)b.append(String.format("%02x",v));return b.toString();}
    public static void unzip(File zip,File root) throws Exception {long total=0;int count=0;try(ZipInputStream in=new ZipInputStream(new FileInputStream(zip))){ZipEntry e;while((e=in.getNextEntry())!=null){if(++count>10000)throw new IOException("压缩包文件过多");File out=child(root,e.getName());if(e.isDirectory()){out.mkdirs();continue;}out.getParentFile().mkdirs();try(FileOutputStream dest=new FileOutputStream(out)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>IMPORT_LIMIT*2)throw new IOException("解压内容过大");dest.write(b,0,n);}}}}}
    public static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    public static JSONObject obj(Object... args){JSONObject o=new JSONObject();try{for(int i=0;i<args.length;i+=2)o.put((String)args[i],args[i+1]);}catch(Exception e){throw new IllegalArgumentException(e);}return o;}
}
