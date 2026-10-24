package cn.local.ocr;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 按所选配置绑定模型工件，加载只复核下载记录，不重新扫描权重。 */
final class RecognitionModels {
    final Context context;
    private JSONObject cachedProfiles;
    RecognitionModels(Context c) { context=c; }
    synchronized JSONObject profiles() throws Exception {
        if(cachedProfiles!=null)return cachedProfiles;
        try(InputStream in=context.getAssets().open("ocr/model-profiles.json")) {
            cachedProfiles=new JSONObject(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));return cachedProfiles;
        }
    }
    String modelChoice() {return context.getSharedPreferences("settings",0).getString("ocrModel","ovis");}
    JSONObject profile() throws Exception {return profiles().getJSONObject(modelChoice());}
    JSONObject profile(String choice) throws Exception {
        if(!profiles().has(choice))throw new IOException("未知识别模型配置");
        return profiles().getJSONObject(choice);
    }
    JSONArray choices() throws Exception {
        JSONObject profiles=profiles();JSONArray out=new JSONArray();
        for(java.util.Iterator<String> keys=profiles.keys();keys.hasNext();) {
            String id=keys.next();JSONObject profile=profiles.getJSONObject(id);
            out.put(FilesUtil.obj("id",id,"name",profile.getString("name"),"repo",profile.getString("repo")));
        }
        return out;
    }
    JSONArray contract() throws Exception {
        return contract(modelChoice());
    }
    JSONArray contract(String choice) throws Exception {
        try(InputStream in=context.getAssets().open("ocr/"+profile(choice).getString("catalog"))) {
            return new JSONArray(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));
        }
    }
    JSONObject downloadedReadiness() throws Exception {
        return downloadedReadiness(modelChoice());
    }
    JSONObject downloadedReadiness(String choice) throws Exception {
        return ModelHub.downloadedReadiness(ModelHub.root(context),contract(choice),ModelHub.state(context));
    }

    static int effectiveCpuThreads(int choice,int processors) {
        if(choice!=0&&choice!=1&&choice!=2&&choice!=4)throw new IllegalArgumentException("推理线程请选择自动、1、2 或 4");
        int available=processors>=4?4:processors>=2?2:1;
        return choice==0?available:Math.min(choice,available);
    }
    int threadChoice() {return context.getSharedPreferences("settings",0).getInt("ocrThreads",0);}
    int cpuThreads() {return effectiveCpuThreads(threadChoice(),Runtime.getRuntime().availableProcessors());}
    String config() throws Exception {
        return config(modelChoice());
    }
    String config(String choice) throws Exception {
        JSONObject config;
        try(InputStream in=context.getAssets().open("ocr/config.json")) {
            config=new JSONObject(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));
        }
        config.getJSONObject("platform").put("threads",cpuThreads());
        config.getJSONObject("models").getJSONObject("layout").put("root",FilesUtil.child(ModelHub.root(context),ModelHub.REPOS[0]+"/files").getPath());
        JSONObject profile=profile(choice);String repo=profile.getString("repo");
        File folder=FilesUtil.child(ModelHub.root(context),repo+"/files");
        String sourceText=FilesUtil.read(new File(folder,"config.json"));
        JSONObject source=new JSONObject(sourceText);
        File runtimeFile=new File(folder,"ocr_runtime.json");
        String sourceHash=smallConfigHash(sourceText);
        JSONObject runtime=runtimeFile.isFile()?new JSONObject(FilesUtil.read(runtimeFile)):null;
        if(runtime==null||!sourceHash.equals(runtime.optString("source_config_sha256"))) {
            runtime=prepareRuntime(source,profile.getJSONObject("config"));
            runtime.put("source_config_sha256",sourceHash);
            FilesUtil.write(runtimeFile,runtime.toString());
        }
        JSONObject recognition=config.getJSONObject("models").getJSONObject("recognition");
        recognition.put("root",folder.getPath());JSONArray artifacts=new JSONArray();
        JSONArray contract=contract(choice);
        for(int i=0;i<contract.length();i++) {
            JSONObject file=contract.getJSONObject(i);
            if(file.getString("repo").equals(repo))artifacts.put(FilesUtil.obj("path",file.getString("path"),"sha256",file.getString("sha256")));
        }
        artifacts.put(FilesUtil.obj("path","ocr_runtime.json","sha256",smallConfigHash(FilesUtil.read(runtimeFile))));
        recognition.put("artifacts",artifacts);
        return config.toString();
    }
    static JSONObject prepareRuntime(JSONObject source,JSONObject defaults)throws Exception {
        JSONObject out=new JSONObject(source.toString());
        defaults=new JSONObject(defaults.toString());
        if(!out.has("ocr"))for(java.util.Iterator<String> keys=defaults.keys();keys.hasNext();) {
            String key=keys.next();out.put(key,defaults.get(key));
        }
        return out;
    }
    static String smallConfigHash(String text)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
}
