package cn.local.ocr;
import java.io.*;
import java.net.HttpURLConnection;

/** HTTP transport only. Repository selection and SHA-256 verification live in ModelHub/service. */
final class ModelTransfer {
 interface Factory {HttpURLConnection open()throws Exception;}
 interface Observer {void progress(long bytes)throws Exception;}
 static void transfer(File part,long size,Factory factory,Observer observer)throws Exception{
  long offset=part.length();HttpURLConnection c=factory.open();
  try{
   if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");int code=c.getResponseCode();
   // Some ModelScope small-file endpoints send a tail with HTTP 200 and no Content-Range.
   // A fresh request without Range is required; never treat that tail as a complete file.
   if(offset>0&&code==200){c.disconnect();c=factory.open();offset=0;code=c.getResponseCode();}
   if(code!=200&&code!=206)throw new IOException("下载 HTTP "+code);
   boolean append=offset>0;
   if(code==206){java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(String.valueOf(c.getHeaderField("Content-Range")));if(!range.matches()||Long.parseLong(range.group(1))!=offset||Long.parseLong(range.group(3))!=size||Long.parseLong(range.group(2))<offset||Long.parseLong(range.group(2))>=size)throw new IOException("续传位置或文件大小不一致");}
   observer.progress(offset);
   try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(part,append)){
    byte[] buffer=new byte[65536];int count;
    while((count=in.read(buffer))!=-1){observer.progress(offset);if(offset+count>size)throw new IOException("文件大小超过清单");out.write(buffer,0,count);offset+=count;observer.progress(offset);}
   }
   observer.progress(offset);
   if(offset!=size)throw new IOException("连接提前结束，已保留下载进度，请继续下载");
  }finally{c.disconnect();}
 }
}
