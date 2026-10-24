package cn.local.ocr;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class DocumentStore {
    final File root; final Context context;
    DocumentStore(Context c){context=c;root=new File(c.getFilesDir(),"documents");root.mkdirs();}
    File dir(String id)throws Exception{if(!id.matches("[a-zA-Z0-9_-]+"))throw new IOException("无效文档 ID");return FilesUtil.child(root,id);}
    String create(){String id=UUID.randomUUID().toString();new File(root,id).mkdirs();return id;}
    synchronized JSONObject load(String id)throws Exception{return new JSONObject(FilesUtil.read(new File(dir(id),"state.json")));}
    synchronized void save(JSONObject doc)throws Exception{doc.put("updatedAt",System.currentTimeMillis());FilesUtil.write(new File(dir(doc.getString("id")),"state.json"),doc.toString());}
    synchronized JSONArray list()throws Exception{ArrayList<JSONObject> list=new ArrayList<>();File[] files=root.listFiles();if(files!=null)for(File d:files)try{JSONObject o=load(d.getName());list.add(FilesUtil.obj("id",o.getString("id"),"title",o.optString("title"),"mode",o.optString("mode"),"updatedAt",o.optLong("updatedAt"),"progress",o.optInt("progress"),"count",o.optJSONArray("blocks").length(),"status",o.optString("status")));}catch(Exception ignored){}list.sort((a,b)->Long.compare(b.optLong("updatedAt"),a.optLong("updatedAt")));return new JSONArray(list);}
    synchronized void delete(String id)throws Exception{FilesUtil.remove(dir(id));}
    String url(String id,String path){return "https://appassets.androidplatform.net/documents/"+id+"/"+path;}
    JSONObject normalize(String id,String title,JSONObject ir)throws Exception{
        JSONObject doc=FilesUtil.obj("id",id,"title",title,"mode","json-replay","status","ready","progress",0,"blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray(),"schemaVersion",ir.optString("schema_version"));
        JSONArray pages=ir.optJSONArray("pages");if(pages==null||pages.length()==0)throw new IOException("JSON 缺少 DocumentIR pages");
        if(pages.length()>1000)throw new IOException("页数过多");
        JSONObject assets=doc.getJSONObject("assets");JSONArray resources=ir.optJSONArray("resources");if(resources!=null)for(int i=0;i<resources.length();i++){JSONObject r=resources.getJSONObject(i);String path=r.optString("path");if(path.isEmpty())continue;File file=FilesUtil.child(dir(id),path);assets.put(path,FilesUtil.obj("src",url(id,path),"width",r.optInt("width",400),"height",r.optInt("height",240),"missing",!file.isFile()));}
        for(int p=0;p<pages.length();p++){JSONObject page=pages.getJSONObject(p);String pid=page.optString("page_id","p"+p);JSONArray bs=page.optJSONArray("blocks");if(bs==null)continue;HashMap<String,JSONObject> byId=new HashMap<>();for(int b=0;b<bs.length();b++){JSONObject block=bs.getJSONObject(b);byId.put(block.getString("id"),block);}JSONArray order=page.optJSONArray("reading_order");if(order==null){order=new JSONArray();for(int b=0;b<bs.length();b++)order.put(bs.getJSONObject(b).getString("id"));}
            for(int n=0;n<order.length();n++){JSONObject b=byId.get(order.getString(n));if(b==null)continue;JSONObject c=b.optJSONObject("content");
                // 引擎会清空未通过结构/公式校验的表格正文；展示和校对仍使用保留的原始输出。
                // 不修改 DocumentIR，也不把未验证的表格标记为识别成功。
                JSONObject provenance=b.optJSONObject("provenance");Object raw=provenance==null?null:provenance.opt("raw_output");
                if(b.optString("type").equals("table")&&!b.optString("status","ok").equals("ok")&&(c==null||c.optString("text").isEmpty())&&raw instanceof String&&!((String)raw).isEmpty())c=FilesUtil.obj("text",raw,"format","markdown","resource",c==null?"":c.optString("resource"));
                if(c==null){if(b.optString("status","ok").equals("ok"))continue;c=FilesUtil.obj("text","> 此区域未完成识别，请对照原图校对。","format","markdown");}String text=c.optString("text"),type=b.optString("type","text"),resource=c.optString("resource");if(type.equals("image"))text="![插图]("+resource+")";else if(c.optString("format").equals("latex"))text="$$\n"+text+"\n$$";
                doc.getJSONArray("blocks").put(FilesUtil.obj("id",pid+"-"+b.getString("id"),"page",p+1,"type",type,"markdown",text,"resource",resource,"sourceStatus",b.optString("status","ok"),"format",c.optString("format","markdown")));
            }
            doc.getJSONArray("pages").put(FilesUtil.obj("id",pid,"number",p+1,"title",page.optString("title"),"kind",page.optString("kind"),"route","JSON 结果回放","sourceStatus",page.optString("status"),"rasterSize",page.optJSONArray("raster_size")));
        }
        if(doc.getJSONArray("blocks").length()>10000)throw new IOException("区域数超过 10000");
        int pending=0;JSONArray normalized=doc.getJSONArray("blocks");for(int i=0;i<normalized.length();i++)if(normalized.getJSONObject(i).optString("sourceStatus").equals("pending-ocr"))pending++;doc.put("pendingOcr",pending);int pendingLayout=0;for(int i=0;i<normalized.length();i++)if(normalized.getJSONObject(i).optString("sourceStatus").equals("pending-layout"))pendingLayout++;doc.put("pendingAnalysis",pendingLayout);
        boolean partial=ir.optString("status").equals("partial"),emptyPage=normalized.length()==0;
        for(int i=0;i<normalized.length();i++)if(!normalized.getJSONObject(i).optString("sourceStatus","ok").equals("ok"))partial=true;
        for(int i=0;i<pages.length();i++){
            JSONObject page=pages.getJSONObject(i),evidence=page.optJSONObject("reading_order_evidence");
            partial |= page.optString("status").equals("partial");
            emptyPage &= ir.optString("status").equals("blank")||page.optString("status").equals("blank")||evidence!=null&&evidence.optString("reason").equals("empty_page");
        }
        String outcome=emptyPage?"blank":partial?"partial":"succeeded";
        doc.put("recognitionOutcome",outcome);
        if(outcome.equals("partial"))doc.put("notice","部分区域未完整识别，可用内容已保存；请对照原图核验标示区域。");
        return doc;
    }
    synchronized JSONObject copyInput(JSONObject source)throws Exception {
        String id=create();String path=source.getString("input");
        File target=FilesUtil.child(dir(id),path);target.getParentFile().mkdirs();
        java.nio.file.Files.copy(FilesUtil.child(dir(source.getString("id")),path).toPath(),target.toPath());
        JSONObject doc=FilesUtil.obj("id",id,"title",source.optString("title")+" · 重新识别","mode","pending-ocr","status","ready","progress",0,"blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray(),"input",path,"original",url(id,path),"derivedFrom",source.getString("id"));
        if(source.has("rawInput")) {
            String raw=source.getString("rawInput");File rawTarget=FilesUtil.child(dir(id),raw);rawTarget.getParentFile().mkdirs();
            if(!rawTarget.equals(target))java.nio.file.Files.copy(FilesUtil.child(dir(source.getString("id")),raw).toPath(),rawTarget.toPath());
            doc.put("rawInput",raw);
        }
        if(source.has("imagePreparation"))doc.put("imagePreparation",new JSONObject(source.getJSONObject("imagePreparation").toString()));
        if(source.has("documentCrop")) {
            JSONObject crop=new JSONObject(source.getJSONObject("documentCrop").toString());
            for(String key:new String[]{"source","original"}) {
                String cropPath=crop.getString(key);File dest=FilesUtil.child(dir(id),cropPath);dest.getParentFile().mkdirs();
                if(!dest.isFile())java.nio.file.Files.copy(FilesUtil.child(dir(source.getString("id")),cropPath).toPath(),dest.toPath());
            }
            crop.put("sourceUrl",url(id,crop.getString("source"))).remove("preview");doc.put("documentCrop",crop);
        }
        if(source.getJSONObject("assets").has(path))doc.getJSONObject("assets").put(path,
            new JSONObject(source.getJSONObject("assets").getJSONObject(path).toString()).put("src",url(id,path)));
        if(source.has("pages"))doc.put("pages",new JSONArray(source.getJSONArray("pages").toString()));
        if(doc.getJSONArray("pages").length()>0)doc.getJSONArray("pages").getJSONObject(0).put("source",url(id,path));
        save(doc);return doc;
    }
    synchronized JSONObject recognized(String id,File output)throws Exception {
        JSONObject input=load(id);
        JSONObject ir=new JSONObject(FilesUtil.read(new File(output,"document.json")));
        // 资源全部保存成功后才写新的 state；失败仍可从历史取得原始输入。
        // 移植适配：源版此处调用 DocumentImporter.copyTree；DocumentImporter 拖 POI/pdfbox 不随移植，
        // 该方法原实现（DocumentImporter.java 的 static copyTree）内联为本类的 static copyTree（见类尾）。
        copyTree(output,dir(id));
        JSONObject result=normalize(id,input.optString("title"),ir);
        result.put("mode","real-ocr").put("input",input.getString("input")).put("original",input.optString("original"));
        if(input.has("rawInput")) result.put("rawInput",input.getString("rawInput"));
        if(input.has("imagePreparation"))result.put("imagePreparation",input.getJSONObject("imagePreparation"));
        if(input.has("documentCrop"))result.put("documentCrop",input.getJSONObject("documentCrop"));
        if(input.has("derivedFrom")) result.put("derivedFrom",input.getString("derivedFrom"));
        if(input.has("recognition")) result.put("recognition",input.getJSONObject("recognition"));
        for(int i=0;i<result.getJSONArray("pages").length();i++) result.getJSONArray("pages").getJSONObject(i).put("source",input.optString("original")).put("route","本地版面分析 → 识别 → 文档");
        save(result);return result;
    }
    JSONObject sample(String sample)throws Exception{
        if(!sample.equals("odb-13")&&!sample.equals("odb-09"))throw new IOException("未知样本");String id=create();copyAssets("fixtures/"+sample,dir(id));JSONObject doc=normalize(id,sample.equals("odb-13")?"中文 · 表格 · 四张插图":"公式 · 图片",new JSONObject(FilesUtil.read(new File(dir(id),"document.json"))));for(File f:dir(id).listFiles())if(f.getName().startsWith("source."))doc.put("original",url(id,f.getName()));save(doc);return doc;
    }
    void copyAssets(String path,File dest)throws Exception{String[] list=context.getAssets().list(path);if(list!=null&&list.length>0){dest.mkdirs();for(String x:list)copyAssets(path+"/"+x,new File(dest,x));}else{dest.getParentFile().mkdirs();try(InputStream in=context.getAssets().open(path);OutputStream out=new FileOutputStream(dest)){FilesUtil.copy(in,out,FilesUtil.IMPORT_LIMIT);}}}
    synchronized JSONObject update(JSONObject args)throws Exception{JSONObject doc=load(args.getString("id"));if(args.has("progress"))doc.put("progress",Math.max(0,Math.min(args.getInt("progress"),doc.getJSONArray("blocks").length())));if(args.has("status"))doc.put("status",args.getString("status"));if(args.has("edits"))doc.put("edits",args.getJSONObject("edits"));if(args.has("blockEdit"))RegionRetry.edit(doc,args.getJSONObject("blockEdit"));if(args.has("anchor"))doc.put("anchor",args.getJSONObject("anchor"));if(args.has("title"))doc.put("title",args.getString("title"));if(args.has("order"))reorder(doc,args.getJSONArray("order"));save(doc);return doc;}
    static void reorder(JSONObject doc,JSONArray order)throws Exception{
        JSONArray blocks=doc.getJSONArray("blocks");if(order.length()!=blocks.length())throw new IOException("区域列表不完整");
        if(doc.optString("status").equals("running"))throw new IOException("请先暂停输出再调整顺序");
        Map<String,JSONObject> byId=new HashMap<>();for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);if(byId.put(b.getString("id"),b)!=null)throw new IOException("区域 ID 重复，无法调整顺序");}
        JSONArray result=new JSONArray();int completed=doc.optInt("progress");
        for(int i=0;i<order.length();i++){String id=order.getString(i);JSONObject block=byId.remove(id);if(block==null)throw new IOException("区域 ID 无效或重复");if(block.optInt("page",1)!=blocks.getJSONObject(i).optInt("page",1))throw new IOException("只能在同一页内移动区域");if(i>=completed&&!id.equals(blocks.getJSONObject(i).getString("id")))throw new IOException("只能移动已完成区域");result.put(block);}
        doc.put("blocks",result);doc.put("orderEdited",true);
    }
    JSONObject exportIR(JSONObject doc)throws Exception{
        File original=new File(dir(doc.getString("id")),"document.json");if(!original.isFile())return toIR(doc);
        JSONObject ir=new JSONObject(FilesUtil.read(original));if(!doc.optBoolean("orderEdited"))return ir;
        JSONArray pages=ir.getJSONArray("pages"),blocks=doc.getJSONArray("blocks");
        for(int p=0;p<pages.length();p++){
            JSONObject page=pages.getJSONObject(p);String prefix=page.optString("page_id","p"+p)+"-";
            Set<String> sourceIds=new HashSet<>();JSONArray source=page.getJSONArray("blocks");for(int i=0;i<source.length();i++)sourceIds.add(source.getJSONObject(i).getString("id"));
            List<String> ids=new ArrayList<>();for(int i=0;i<blocks.length();i++)if(blocks.getJSONObject(i).optInt("page",1)==p+1)ids.add(blocks.getJSONObject(i).getString("id"));
            boolean direct=sourceIds.containsAll(ids);JSONArray order=new JSONArray();Set<String> added=new HashSet<>();
            for(String id:ids){String originalId=direct?id:id.startsWith(prefix)?id.substring(prefix.length()):"";if(!sourceIds.contains(originalId))throw new IOException("原始区域 ID 无法对应");order.put(originalId);added.add(originalId);}
            JSONArray previous=page.optJSONArray("reading_order");if(previous!=null)for(int i=0;i<previous.length();i++){String id=previous.getString(i);if(added.add(id))order.put(id);}
            page.put("reading_order",order);
        }
        return ir;
    }
    String markdown(JSONObject doc,boolean partial)throws Exception{StringBuilder md=new StringBuilder();JSONObject edits=doc.optJSONObject("edits");JSONArray blocks=doc.getJSONArray("blocks");int count=partial?doc.optInt("progress",0):blocks.length();for(int i=0;i<count;i++){JSONObject b=blocks.getJSONObject(i);md.append(edits!=null?edits.optString(b.getString("id"),b.getString("markdown")):b.getString("markdown")).append("\n\n");}return md.toString();}
    File export(String id,String format)throws Exception{JSONObject doc=load(id);File outdir=new File(context.getCacheDir(),"exports");outdir.mkdirs();boolean partial=doc.optInt("progress")<doc.getJSONArray("blocks").length();String md=markdown(doc,partial);
        if(format.equals("txt")||format.equals("md")){File f=new File(outdir,id+(format.equals("txt")?".txt":".md"));FilesUtil.write(f,format.equals("txt")?md.replaceAll("(?m)^#{1,6}\\s+","").replaceAll("!\\[([^]]*)]\\([^)]+\\)","[图片：$1]").replaceAll("<[^>]*>"," "):md);return f;}
        File f=new File(outdir,id+".zip");try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(f))){put(zip,"document.md",md.getBytes(java.nio.charset.StandardCharsets.UTF_8));put(zip,"app-state.json",portable(doc).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));put(zip,"document.json",exportIR(doc).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));File raw=new File(dir(id),"original-document.json");if(!raw.isFile())raw=new File(dir(id),"document.json");if(raw.isFile())putFile(zip,"original-document.json",raw);File manifest=new File(dir(id),"run-manifest.json");if(manifest.isFile())putFile(zip,"run-manifest.json",manifest);Set<String> included=new HashSet<>(Arrays.asList("document.md","app-state.json","document.json","original-document.json","run-manifest.json"));
            JSONObject assets=doc.getJSONObject("assets");for(Iterator<String> it=assets.keys();it.hasNext();){String path=it.next();File asset=FilesUtil.child(dir(id),path);if(asset.isFile()&&included.add(path))putFile(zip,path,asset);}
            JSONObject crop=doc.optJSONObject("documentCrop");
            if(crop!=null)for(String path:new String[]{crop.getString("source"),crop.getString("original"),doc.optString("rawInput")}) {
                if(!path.isEmpty()){File image=FilesUtil.child(dir(id),path);if(image.isFile()&&included.add(path))putFile(zip,path,image);}
            }
            zipAssets(zip,dir(id),new File(dir(id),"assets"),included);for(File originalImage:dir(id).listFiles())if(originalImage.getName().startsWith("source.")&&included.add(originalImage.getName()))putFile(zip,originalImage.getName(),originalImage);}return f;
    }
    JSONObject portable(JSONObject doc)throws Exception{
        JSONObject copy=new JSONObject(doc.toString());copy.put("appExport",1);
        String prefix="https://appassets.androidplatform.net/documents/"+doc.getString("id")+"/";
        if(copy.optString("original").startsWith(prefix))copy.put("original",copy.getString("original").substring(prefix.length()));
        JSONArray pages=copy.optJSONArray("pages");if(pages!=null)for(int i=0;i<pages.length();i++){JSONObject page=pages.getJSONObject(i);if(page.optString("source").startsWith(prefix))page.put("source",page.getString("source").substring(prefix.length()));}
        JSONObject crop=copy.optJSONObject("documentCrop");if(crop!=null){crop.remove("sourceUrl");crop.remove("preview");}
        return copy;
    }
    JSONObject restoreExport(String id,JSONObject state)throws Exception{
        if(state.optInt("appExport")!=1)throw new IOException("不支持的应用文档版本");
        JSONArray blocks=state.getJSONArray("blocks");if(blocks.length()>10000)throw new IOException("区域过多");
        for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);b.getString("id");b.getString("markdown");}
        JSONObject assets=state.getJSONObject("assets");for(Iterator<String> it=assets.keys();it.hasNext();){String path=it.next();File f=FilesUtil.child(dir(id),path);JSONObject a=assets.getJSONObject(path);a.put("src",url(id,path));a.put("missing",!f.isFile());}
        String source=state.optString("original");if(!source.isEmpty()){FilesUtil.child(dir(id),source);state.put("original",url(id,source));}
        JSONArray pages=state.optJSONArray("pages");if(pages!=null)for(int i=0;i<pages.length();i++){JSONObject p=pages.getJSONObject(i);if(p.has("source")){String path=p.getString("source");FilesUtil.child(dir(id),path);p.put("source",url(id,path));}}
        JSONObject crop=state.optJSONObject("documentCrop");if(crop!=null){FilesUtil.child(dir(id),crop.getString("source"));FilesUtil.child(dir(id),crop.getString("original"));crop.put("sourceUrl",url(id,crop.getString("source")));crop.remove("preview");}
        state.remove("input");state.remove("recognition");state.put("id",id);state.put("mode","json-replay");state.put("progress",0);state.put("status","ready");state.remove("anchor");return state;
    }
    static void putFile(ZipOutputStream zip,String path,File file)throws Exception{zip.putNextEntry(new ZipEntry(path));try(InputStream in=new FileInputStream(file)){FilesUtil.copy(in,zip,Long.MAX_VALUE);}zip.closeEntry();}
    JSONObject toIR(JSONObject doc)throws Exception{
        JSONArray pages=new JSONArray(),resources=new JSONArray();LinkedHashMap<Integer,JSONObject> grouped=new LinkedHashMap<>();
        JSONArray metadata=doc.optJSONArray("pages");if(metadata!=null)for(int i=0;i<metadata.length();i++){JSONObject page=metadata.getJSONObject(i);int number=page.optInt("number",i+1);grouped.put(number,FilesUtil.obj("page_id","p"+number,"title",page.optString("title"),"kind",page.optString("kind"),"blocks",new JSONArray(),"reading_order",new JSONArray()));}
        JSONArray source=doc.getJSONArray("blocks");for(int i=0;i<source.length();i++){
            JSONObject block=source.getJSONObject(i);int number=block.optInt("page",1);JSONObject page=grouped.get(number);if(page==null){page=FilesUtil.obj("page_id","p"+number,"blocks",new JSONArray(),"reading_order",new JSONArray());grouped.put(number,page);}
            String id=block.getString("id");page.getJSONArray("reading_order").put(id);page.getJSONArray("blocks").put(FilesUtil.obj("id",id,"type",block.optString("type","text"),"status",block.optString("sourceStatus","ok"),"content",FilesUtil.obj("format",block.optString("format","markdown"),"text",block.optString("markdown"),"resource",block.optString("resource"))));
        }
        for(JSONObject page:grouped.values())pages.put(page);
        JSONObject assets=doc.getJSONObject("assets");for(Iterator<String> it=assets.keys();it.hasNext();){String path=it.next();JSONObject asset=assets.getJSONObject(path);resources.put(FilesUtil.obj("path",path,"width",asset.optInt("width",400),"height",asset.optInt("height",240)));}
        return FilesUtil.obj("schema_version","app-replay-1","pages",pages,"resources",resources);
    }
    static void put(ZipOutputStream zip,String name,byte[] data)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    static void zipAssets(ZipOutputStream zip,File root,File dir,Set<String> included)throws Exception{if(!dir.isDirectory())return;for(File f:dir.listFiles()){if(f.isDirectory())zipAssets(zip,root,f,included);else{String path=root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');if(included.add(path))putFile(zip,path,f);}}}
    // 移植适配：原 DocumentImporter.copyTree 的实现原样内联（ recognized() 把引擎导出目录并入文档目录）。
    static void copyTree(File from,File to)throws Exception{for(File f:from.listFiles()){File dest=FilesUtil.child(to,f.getName());if(f.isDirectory()){dest.mkdirs();copyTree(f,dest);}else java.nio.file.Files.copy(f.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
}
