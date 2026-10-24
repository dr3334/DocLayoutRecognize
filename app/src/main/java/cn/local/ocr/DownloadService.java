package cn.local.ocr;
import android.app.*;
import android.content.*;
import android.net.ConnectivityManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class DownloadService extends Service {
    static volatile boolean active=false,reserved=false,pause=false;
    static volatile JSONObject activity=new JSONObject();
    static volatile HttpURLConnection connection;
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    String repo;
    @Override public IBinder onBind(Intent i){return null;}
    static void requestPause(){pause=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    void stage(String state,String file,String message,long bytes,long total){activity=FilesUtil.obj("repo",repo,"stage",state,"path",file,"message",message,"bytes",bytes,"total",total,"updatedAt",System.currentTimeMillis());}
    @Override public int onStartCommand(Intent i,int flags,int startId){
        if(i==null)return START_NOT_STICKY;
        if("pause".equals(i.getAction())){requestPause();if(!active)stopSelf();return START_NOT_STICKY;}
        if(active)return START_NOT_STICKY;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("models","模型下载",NotificationManager.IMPORTANCE_LOW));
        startForeground(11,notification("准备下载"));active=true;reserved=false;pause=false;repo=i.getStringExtra("repo");stage("preparing","","正在准备已选文件",0,0);
        String paths=i.getStringExtra("paths");executor.execute(()->run(paths,startId));return START_NOT_STICKY;
    }
    // 移植适配：源版通知打开源 App 的 MainActivity；本包随 DocLayoutRecognize 分发，改为宿主 Activity，
    // 通知标题同步改为宿主品牌文案「试卷识别 · 模型下载」。
    Notification notification(String text){Intent open=new Intent(this,com.doclayout.recognize.MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DownloadService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,"models").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("试卷识别 · 模型下载").setContentText(text).setContentIntent(pi).setOngoing(true).addAction(new Notification.Action.Builder(null,"暂停",stop).build()).build();}
    void notifyText(String s){getSystemService(NotificationManager.class).notify(11,notification(s));}
    void checkNetwork()throws Exception{if(pause)throw new IOException("已暂停");ConnectivityManager cm=getSystemService(ConnectivityManager.class);if(cm.getActiveNetwork()==null)throw new IOException("网络不可用，进度已保留");if(getSharedPreferences("settings",0).getBoolean("wifiOnly",true)&&cm.isActiveNetworkMetered())throw new IOException("等待 Wi-Fi：可在设置中允许移动网络");}
    boolean verified(File file,JSONObject task)throws Exception{
        if(!file.isFile()||file.length()!=task.getLong("size"))return false;
        String expected=task.optString("sha256");if(expected.isEmpty())return true;
        stage("verifying",task.getString("path"),"正在校验 SHA-256",0,file.length());
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,last=0;
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[262144];int n;while((n=in.read(buffer))!=-1){if(pause)throw new IOException("已暂停");digest.update(buffer,0,n);count+=n;if(System.currentTimeMillis()-last>250){stage("verifying",task.getString("path"),"正在校验 SHA-256",count,file.length());last=System.currentTimeMillis();}}}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return expected.equals(hex.toString());
    }
    void finishTask(JSONObject task,JSONArray tasks)throws Exception{ModelHub.finishDownloaded(ModelHub.root(this),task);ModelHub.save(this,tasks);}
    void run(String paths,int startId){JSONArray tasks=null;JSONObject current=null;try{
        ModelHub.checkRepo(repo);JSONArray files=ModelHub.select(ModelHub.cachedCatalog(this,repo),new JSONArray(paths));tasks=ModelHub.merge(ModelHub.state(this),files);ModelHub.save(this,tasks);Set<String> selected=new HashSet<>();for(int i=0;i<files.length();i++)selected.add(files.getJSONObject(i).getString("path"));
        for(int n=0;n<tasks.length();n++){
            JSONObject candidate=tasks.getJSONObject(n);if(!candidate.getString("repo").equals(repo)||!selected.contains(candidate.getString("path")))continue;current=candidate;checkNetwork();
            File target=ModelHub.file(this,current),part=ModelHub.partial(this,current);target.getParentFile().mkdirs();part.getParentFile().mkdirs();long size=current.getLong("size");
            current.put("status","verifying");ModelHub.save(this,tasks);
            if((!current.optString("sha256").isEmpty()||current.optString("cachedRevision").equals(current.getString("revision")))&&verified(target,current)){finishTask(current,tasks);continue;}
            File old=ModelHub.legacy(this,current);
            if(old!=null&&verified(old,current)){Files.copy(old.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);finishTask(current,tasks);continue;}
            if(old!=null&&!part.exists()){File oldPart=new File(old+".part");if(oldPart.isFile())Files.move(oldPart.toPath(),part.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            if(part.length()>size)Files.deleteIfExists(part.toPath());
            if(part.isFile()&&part.length()==size){if(verified(part,current)){Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);finishTask(current,tasks);continue;}Files.delete(part.toPath());}
            long offset=part.length();if(target.getParentFile().getUsableSpace()<size-offset+32L*1024*1024)throw new IOException("存储空间不足");current.put("status","downloading");current.put("downloaded",offset);current.remove("error");ModelHub.save(this,tasks);
            stage("connecting",current.getString("path"),"正在连接下载服务器",offset,size);
            JSONObject downloading=current;JSONArray allTasks=tasks;long[] last={0},lastBytes={offset},lastTime={System.currentTimeMillis()};
            ModelTransfer.transfer(part,size,()->{checkNetwork();connection=ModelHub.connection(ModelHub.url(downloading));return connection;},count->{
                checkNetwork();long now=System.currentTimeMillis();
                // Live state is independent of disk persistence, so even a slow next read exposes received bytes.
                stage("downloading",downloading.getString("path"),"正在下载",count,size);
                if(now-last[0]>=700||count==size){long speed=Math.max(0,count-lastBytes[0])*1000/Math.max(1,now-lastTime[0]);downloading.put("downloaded",count);downloading.put("bytesPerSecond",speed);ModelHub.save(this,allTasks);notifyText(downloading.getString("path")+" · "+(size==0?100:count*100/size)+"%");last[0]=now;lastTime[0]=now;lastBytes[0]=count;}
            });connection=null;
            checkNetwork();current.put("status","verifying");current.put("downloaded",part.length());ModelHub.save(this,tasks);
            if(!verified(part,current)){Files.deleteIfExists(part.toPath());current.put("downloaded",0);throw new IOException("SHA-256 校验失败，请重试");}
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);finishTask(current,tasks);
        }
        stage("complete","","所选文件已下载完成",0,0);
        // 下载完成后立即做一次磁盘对账，把内存 readiness 推进到 files-ready/missing-models，
        // 让设置页/模型页的「完成下载后自动检查」名副其实（否则它永远停在 unchecked）。
        // 单独吞异常：对账失败不能把已完成的下载改判成 error。
        try{RecognitionController.get(this).refreshReadiness();}catch(Exception ignored){}
    }catch(Exception e){String message=String.valueOf(e.getMessage());stage(pause?"paused":"error",current==null?"":current.optString("path"),pause?"已暂停，进度已保留":message,0,0);try{
        if(tasks==null)tasks=ModelHub.state(this);
        if(current!=null){current.put("status",pause?"paused":"error");current.put("error",pause?"已暂停，进度已保留":message);File part=ModelHub.partial(this,current);if(part.exists())current.put("downloaded",part.length());}
        for(int j=0;j<tasks.length();j++){JSONObject t=tasks.getJSONObject(j);if(t.optString("repo").equals(repo)&&t.optString("status").equals("queued"))t.put("status","paused");}
        ModelHub.save(this,tasks);
    }catch(Exception ignored){}
    }finally{if(connection!=null)connection.disconnect();connection=null;active=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}}
    @Override public void onTimeout(int startId,int fgsType){requestPause();stopSelf(startId);}
    @Override public void onDestroy(){requestPause();executor.shutdown();super.onDestroy();}
}
