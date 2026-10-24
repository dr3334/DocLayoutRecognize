package cn.local.ocr;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ModelHub {
    public static final String[] REPOS={"dr3334/PP-DocLayoutV3-mnn","dr3334/ovrics-ocrv2_mnn","MNN/GLM-OCR-MNN"};
    static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
    static String normalizeRepo(String input)throws Exception{
        String repo=input.trim();
        if(repo.startsWith("https://")){
            URI uri=new URI(repo);if(!Arrays.asList("modelscope.cn","www.modelscope.cn").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IOException("请输入 ModelScope 模型仓库链接");
            String[] parts=uri.getPath().split("/");if(parts.length<4||!parts[1].equals("models"))throw new IOException("链接应包含 /models/作者/仓库");repo=parts[2]+"/"+parts[3];
        }
        checkRepo(repo);return repo;
    }
    static void checkRepo(String repo)throws IOException{
        if(repo==null||!repo.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}/[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))throw new IOException("仓库格式应为 作者/仓库名");
    }
    static void checkPath(String path)throws IOException{
        if(path.isEmpty()||path.startsWith("/")||path.contains("\\")||path.contains(":"))throw new IOException("无效模型文件路径");
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.chars().anyMatch(c->c<32))throw new IOException("无效模型文件路径");
    }
    static HttpURLConnection connection(String url)throws Exception{if(!url.startsWith("https://"))throw new IOException("下载必须使用 HTTPS");HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestProperty("Accept-Encoding","identity");c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","SuiShouOCR/0.4");return c;}
    static JSONArray parseCatalog(String repo,JSONObject response)throws Exception{
        checkRepo(repo);if(response.optInt("Code",200)!=200||!response.optBoolean("Success",true))throw new IOException("仓库清单失败："+response.optString("Message"));
        JSONArray files=response.getJSONObject("Data").getJSONArray("Files"),out=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);if(!f.optString("Type").equals("blob"))continue;
            String path=f.getString("Path");checkPath(path);if(!seen.add(path))continue;
            String sha=f.optString("Sha256","");if(!sha.isEmpty()&&!sha.matches("[a-fA-F0-9]{64}"))throw new IOException("文件校验值无效："+path);
            long size=f.getLong("Size");if(size<0)throw new IOException("文件大小无效："+path);
            String revision=f.getString("Revision");if(revision.isEmpty())throw new IOException("文件缺少版本："+path);
            out.put(FilesUtil.obj("repo",repo,"path",path,"size",size,"sha256",sha.toLowerCase(Locale.ROOT),"revision",revision));
        }
        if(out.length()==0)throw new IOException("仓库没有可下载的文件");return out;
    }
    public static JSONArray catalog(String repo)throws Exception{checkRepo(repo);HttpURLConnection c=connection("https://modelscope.cn/api/v1/models/"+repo+"/repo/files?Revision=master&Recursive=true");try{int code=c.getResponseCode();if(code!=200)throw new IOException("ModelScope HTTP "+code+(code==401||code==403?"：本版仅支持公开仓库":""));return parseCatalog(repo,new JSONObject(new String(FilesUtil.bytes(c.getInputStream(),8*1024*1024),StandardCharsets.UTF_8)));}finally{c.disconnect();}}
    static JSONArray catalog(Context c,String repo)throws Exception{JSONArray files=catalog(repo);FilesUtil.write(FilesUtil.child(root(c),repo+"/catalog.json"),files.toString());return files;}
    static JSONArray cachedCatalog(Context c,String repo)throws Exception{checkRepo(repo);File file=FilesUtil.child(root(c),repo+"/catalog.json");if(!file.isFile())throw new IOException("请先查看仓库文件清单");return new JSONArray(FilesUtil.read(file));}
    static JSONArray select(JSONArray catalog,JSONArray paths)throws Exception{
        Set<String> wanted=new HashSet<>();for(int i=0;i<paths.length();i++)wanted.add(paths.getString(i));if(wanted.isEmpty())throw new IOException("请至少选择一个文件");
        JSONArray out=new JSONArray();for(int i=0;i<catalog.length();i++){JSONObject f=catalog.getJSONObject(i);if(wanted.remove(f.getString("path")))out.put(new JSONObject(f.toString()));}if(!wanted.isEmpty())throw new IOException("文件清单已变化，请刷新后重试");return out;
    }
    static JSONArray merge(JSONArray previous,JSONArray selected)throws Exception{
        Set<String> replacing=new HashSet<>();for(int i=0;i<selected.length();i++){JSONObject f=selected.getJSONObject(i);replacing.add(f.getString("repo")+"/"+f.getString("path"));}
        JSONArray out=new JSONArray();for(int i=0;i<previous.length();i++){JSONObject f=previous.getJSONObject(i);if(!replacing.contains(f.optString("repo")+"/"+f.optString("path")))out.put(f);}
        for(int i=0;i<selected.length();i++){JSONObject f=new JSONObject(selected.getJSONObject(i).toString());for(int j=0;j<previous.length();j++){JSONObject old=previous.getJSONObject(j);if(old.optString("repo").equals(f.getString("repo"))&&old.optString("path").equals(f.getString("path"))&&old.optString("revision").equals(f.getString("revision"))&&old.optLong("size",-1)==f.getLong("size")&&Arrays.asList("verified","downloaded").contains(old.optString("status")))f.put("cachedRevision",old.getString("revision"));}f.put("status","queued");f.put("downloaded",0);out.put(f);}return out;
    }
    static String url(JSONObject f)throws Exception{checkRepo(f.getString("repo"));checkPath(f.getString("path"));return "https://modelscope.cn/api/v1/models/"+f.getString("repo")+"/repo?Revision="+enc(f.getString("revision"))+"&FilePath="+enc(f.getString("path"));}
    static File root(Context c){File f=new File(c.getFilesDir(),"models");f.mkdirs();return f;}
    static File file(Context c,JSONObject f)throws Exception{return file(root(c),f);}
    static File file(File root,JSONObject f)throws Exception{checkRepo(f.getString("repo"));checkPath(f.getString("path"));return FilesUtil.child(FilesUtil.child(root,f.getString("repo")+"/files"),f.getString("path"));}
    static File partial(Context c,JSONObject f)throws Exception{String key=f.optString("sha256");if(key.isEmpty())key=java.util.UUID.nameUUIDFromBytes((f.getString("revision")+"/"+f.getString("path")).getBytes(StandardCharsets.UTF_8)).toString();return FilesUtil.child(root(c),f.getString("repo")+"/partial/"+key+"/"+f.getString("path"));}
    static File legacy(Context c,JSONObject f)throws Exception{String sha=f.optString("sha256");return sha.matches("[a-f0-9]{64}")?FilesUtil.child(root(c),f.getString("repo")+"/"+sha+"/"+new File(f.getString("path")).getName()):null;}
    static synchronized JSONArray state(Context c)throws Exception{File f=new File(root(c),"state.json");return f.exists()?new JSONArray(FilesUtil.read(f)):new JSONArray();}
    static synchronized void save(Context c,JSONArray tasks)throws Exception{FilesUtil.write(new File(root(c),"state.json"),tasks.toString());}
    static synchronized JSONArray repos(Context c)throws Exception{LinkedHashSet<String> all=new LinkedHashSet<>(Arrays.asList(REPOS));JSONArray saved=new JSONArray(c.getSharedPreferences("models",0).getString("repos","[]"));for(int i=0;i<saved.length();i++)all.add(saved.getString(i));JSONArray tasks=state(c);for(int i=0;i<tasks.length();i++)all.add(tasks.getJSONObject(i).getString("repo"));return new JSONArray(all);}
    static synchronized JSONArray addRepo(Context c,String input)throws Exception{String repo=normalizeRepo(input);JSONArray all=repos(c);boolean found=false;for(int i=0;i<all.length();i++)if(all.getString(i).equals(repo))found=true;if(!found)all.put(repo);c.getSharedPreferences("models",0).edit().putString("repos",all.toString()).apply();return all;}
    static synchronized JSONArray forgetRepo(Context c,String input)throws Exception{String repo=normalizeRepo(input);if(Arrays.asList(REPOS).contains(repo))throw new IOException("默认仓库保留入口");if(DownloadService.active||DownloadService.reserved)throw new IOException("请先暂停下载");JSONArray tasks=state(c);for(int i=0;i<tasks.length();i++)if(tasks.getJSONObject(i).getString("repo").equals(repo))throw new IOException("请先删除此仓库的下载文件");JSONArray all=repos(c),out=new JSONArray();for(int i=0;i<all.length();i++)if(!all.getString(i).equals(repo))out.put(all.get(i));c.getSharedPreferences("models",0).edit().putString("repos",out.toString()).apply();return out;}
    /** 仅由下载完成且内容校验成功的分支调用，记录最终文件而非 .part 的元数据。 */
    static void finishDownloaded(File root,JSONObject task)throws Exception {
        File actual=file(root,task);
        if(!actual.isFile()||actual.length()!=task.getLong("size")) throw new IOException("下载文件尚未完整保存");
        String sha=task.optString("sha256");
        task.put("status",sha.isEmpty()?"downloaded":"verified").put("downloaded",actual.length());
        task.put("verifiedSize",actual.length()).put("verifiedModified",actual.lastModified()).put("verifiedSha256",sha);
        task.remove("error");
    }
    /** 加载仅检查下载记录和文件元数据；SHA-256 在下载完成时计算。 */
    static JSONObject downloadedReadiness(File root,JSONArray required,JSONArray tasks)throws Exception {
        Map<String,JSONObject> downloaded=new HashMap<>();
        for(int i=0;i<tasks.length();i++) {
            JSONObject task=tasks.getJSONObject(i);
            downloaded.put(task.optString("repo")+"/"+task.optString("path"),task);
        }
        JSONArray problems=new JSONArray();
        for(int i=0;i<required.length();i++) {
            JSONObject expected=required.getJSONObject(i);File actual=file(root,expected);
            JSONObject task=downloaded.get(expected.getString("repo")+"/"+expected.getString("path"));
            String reason=!actual.isFile()?"缺少文件":actual.length()!=expected.getLong("size")?"文件大小不符":
                task==null||!task.optString("status").equals("verified")?"请在下载页完成文件校验":
                !task.optString("sha256").equals(expected.getString("sha256"))||task.optLong("size",-1)!=expected.getLong("size")?"下载工件版本不符":
                task.has("verifiedModified")&&(task.optLong("verifiedModified",-1)!=actual.lastModified()||
                    task.optLong("verifiedSize",-1)!=actual.length()||!task.optString("verifiedSha256").equals(expected.getString("sha256")))?"文件已变化，请在下载页重新校验":"";
            if(!reason.isEmpty()) problems.put(FilesUtil.obj("path",expected.getString("path"),"reason",reason));
        }
        return FilesUtil.obj("state",problems.length()==0?"files-ready":"missing-models","problems",problems,
            "message",problems.length()==0?"模型下载校验已完成，尚未加载引擎":"识别模型未就绪："+problems);
    }
    static synchronized JSONObject summary(Context c)throws Exception{
        JSONArray tasks=state(c);if(!DownloadService.active)for(int i=0;i<tasks.length();i++){JSONObject f=tasks.getJSONObject(i);if(Arrays.asList("queued","downloading","verifying").contains(f.optString("status"))){f.put("status","paused");f.put("error","任务已停止，可继续下载");}}
        return FilesUtil.obj("tasks",tasks,"repos",repos(c),"running",DownloadService.active,"activity",DownloadService.activity,"freeBytes",root(c).getUsableSpace());
    }
    static synchronized void remove(Context c,String repo)throws Exception{checkRepo(repo);if(DownloadService.active||DownloadService.reserved)throw new IOException("请先暂停下载");FilesUtil.remove(FilesUtil.child(root(c),repo));JSONArray all=state(c),out=new JSONArray();for(int i=0;i<all.length();i++)if(!all.getJSONObject(i).getString("repo").equals(repo))out.put(all.get(i));save(c,out);}
}
