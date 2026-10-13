package com.modelcamera.app;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class StickerRepo {
    static final class Sticker {
        final String id,name,folderId;
        final float widthMm,heightMm,sourceWidthPx;
        final String sourcePhoto;
        final long createdAt,updatedAt;
        Sticker(String id,String name,float widthMm,float heightMm,String sourcePhoto,float sourceWidthPx){
            this(id,name,widthMm,heightMm,sourcePhoto,sourceWidthPx,"");
        }
        Sticker(String id,String name,float widthMm,float heightMm,String sourcePhoto,float sourceWidthPx,String folderId){
            this(id,name,widthMm,heightMm,sourcePhoto,sourceWidthPx,folderId,0,0);
        }
        Sticker(String id,String name,float widthMm,float heightMm,String sourcePhoto,float sourceWidthPx,String folderId,long createdAt,long updatedAt){
            this.id=id;this.name=name;this.widthMm=widthMm;this.heightMm=heightMm;
            this.sourcePhoto=sourcePhoto;this.sourceWidthPx=sourceWidthPx;this.folderId=folderId;
            this.createdAt=createdAt;this.updatedAt=updatedAt;
        }
    }
    static final class Folder {
        final String id,name;
        Folder(String id,String name){this.id=id;this.name=name;}
    }
    private static final class Data {
        final JSONArray stickers,folders;
        Data(JSONArray stickers,JSONArray folders){this.stickers=stickers;this.folders=folders;}
    }
    private final File directory,index;
    StickerRepo(Context context) {
        directory=new File(context.getFilesDir(),"stickers");directory.mkdirs();index=new File(directory,"index.json");
    }
    private Data read() throws Exception {
        if(!index.exists())return new Data(new JSONArray(),new JSONArray());
        String raw=new String(Files.readAllBytes(index.toPath()),StandardCharsets.UTF_8).trim();
        if(raw.startsWith("["))return new Data(new JSONArray(raw),new JSONArray()); // Original library format.
        JSONObject root=new JSONObject(raw);
        return new Data(root.getJSONArray("stickers"),root.getJSONArray("folders"));
    }
    private void write(Data data) throws Exception {
        JSONObject root=new JSONObject();root.put("version",2);root.put("stickers",data.stickers);root.put("folders",data.folders);
        File temp=new File(directory,"index.tmp");
        Files.write(temp.toPath(),root.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(),index.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    private int find(JSONArray array,String id) throws Exception {
        for(int i=0;i<array.length();i++)if(id.equals(array.getJSONObject(i).getString("id")))return i;
        return -1;
    }
    private void checkFolder(Data data,String folderId) throws Exception {
        if(!folderId.isEmpty()&&find(data.folders,folderId)<0)throw new IllegalArgumentException("文件夹不存在");
    }
    List<Sticker> list() {
        List<Sticker> out=new ArrayList<>();
        try {
            JSONArray arr=read().stickers;
            for(int i=arr.length()-1;i>=0;i--){
                JSONObject o=arr.getJSONObject(i);String id=o.getString("id");
                if(file(id).exists())out.add(new Sticker(id,o.optString("name","水贴"),
                        (float)o.optDouble("widthMm",0),(float)o.optDouble("heightMm",0),
                        o.optString("sourcePhoto",""),(float)o.optDouble("sourceWidthPx",0),o.optString("folderId",""),
                        o.optLong("createdAt",file(id).lastModified()),o.optLong("updatedAt",file(id).lastModified())));
            }
        }catch(Exception ignored){}
        return out;
    }
    List<Folder> folders() {
        List<Folder> out=new ArrayList<>();
        try{JSONArray arr=read().folders;for(int i=0;i<arr.length();i++){
            JSONObject o=arr.getJSONObject(i);out.add(new Folder(o.getString("id"),o.getString("name")));
        }}catch(Exception ignored){}
        return out;
    }
    Sticker save(Bitmap bitmap,String name,float widthMm,float heightMm,String sourcePhoto,float sourceWidthPx) throws Exception {
        return save(bitmap,name,widthMm,heightMm,sourcePhoto,sourceWidthPx,"");
    }
    Sticker save(Bitmap bitmap,String name,float widthMm,float heightMm,String sourcePhoto,float sourceWidthPx,String folderId) throws Exception {
        Data data=read();checkFolder(data,folderId);
        String id=UUID.randomUUID().toString();File target=file(id);
        long now=System.currentTimeMillis();
        try{
            try(FileOutputStream stream=new FileOutputStream(target)){
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new Exception("无法保存图片");
            }
            JSONObject o=new JSONObject();o.put("id",id);o.put("name",name.trim());o.put("widthMm",widthMm);
            o.put("heightMm",heightMm);o.put("sourcePhoto",sourcePhoto);o.put("sourceWidthPx",sourceWidthPx);
            o.put("folderId",folderId);o.put("createdAt",now);o.put("updatedAt",now);data.stickers.put(o);write(data);
            return new Sticker(id,name.trim(),widthMm,heightMm,sourcePhoto,sourceWidthPx,folderId,now,now);
        }catch(Exception e){target.delete();throw e;}
    }
    void renameSticker(String id,String name) throws Exception {
        String trimmed=name.trim();if(trimmed.isEmpty())throw new IllegalArgumentException("请输入贴纸名称");
        Data data=read();int i=find(data.stickers,id);if(i<0)throw new IllegalArgumentException("贴纸不存在");
        data.stickers.getJSONObject(i).put("name",trimmed).put("updatedAt",System.currentTimeMillis());write(data);
    }
    void update(Sticker sticker,Bitmap bitmap,float widthMm,float heightMm,float sourceWidthPx)throws Exception{
        Data data=read();int i=find(data.stickers,sticker.id);if(i<0)throw new IllegalArgumentException("贴纸已删除，请另存副本");
        File target=file(sticker.id),temp=new File(directory,sticker.id+".tmp"),backup=new File(directory,sticker.id+".bak");
        try{
            try(FileOutputStream out=new FileOutputStream(temp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new Exception("图片保存失败");}
            Files.copy(target.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
            JSONObject o=data.stickers.getJSONObject(i);o.put("widthMm",widthMm);o.put("heightMm",heightMm);o.put("sourceWidthPx",sourceWidthPx).put("updatedAt",System.currentTimeMillis());write(data);
            backup.delete();
        }catch(Exception e){if(backup.exists())Files.move(backup.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);throw e;}
        finally{temp.delete();}
    }
    void moveSticker(String id,String folderId) throws Exception {
        Data data=read();checkFolder(data,folderId);int i=find(data.stickers,id);
        if(i<0)throw new IllegalArgumentException("贴纸不存在");
        data.stickers.getJSONObject(i).put("folderId",folderId).put("updatedAt",System.currentTimeMillis());write(data);
    }
    void moveStickers(List<String> ids,String folderId) throws Exception {
        Data data=read();checkFolder(data,folderId);
        for(String id:ids)if(find(data.stickers,id)<0)throw new IllegalArgumentException("有贴纸已不存在，请重新选择");
        long now=System.currentTimeMillis();for(String id:ids)data.stickers.getJSONObject(find(data.stickers,id)).put("folderId",folderId).put("updatedAt",now);
        write(data);
    }
    List<Sticker> copyStickers(List<String> ids,String folderId) throws Exception {
        Data data=read();checkFolder(data,folderId);
        List<JSONObject> originals=new ArrayList<>();
        for(String id:ids){int i=find(data.stickers,id);if(i<0||!file(id).exists())throw new IllegalArgumentException("有贴纸已不存在，请重新选择");originals.add(data.stickers.getJSONObject(i));}
        List<File> created=new ArrayList<>();List<Sticker> copies=new ArrayList<>();
        try {
            for(JSONObject original:originals){
                String id=UUID.randomUUID().toString();File target=file(id);
                Files.copy(file(original.getString("id")).toPath(),target.toPath());created.add(target);
                String name=original.optString("name","水贴")+" · 副本";
                long now=System.currentTimeMillis();JSONObject copy=new JSONObject(original.toString());copy.put("id",id);copy.put("name",name);copy.put("folderId",folderId);
                copy.put("createdAt",now);copy.put("updatedAt",now);
                data.stickers.put(copy);
                copies.add(new Sticker(id,name,(float)copy.optDouble("widthMm",0),(float)copy.optDouble("heightMm",0),
                        copy.optString("sourcePhoto",""),(float)copy.optDouble("sourceWidthPx",0),folderId,now,now));
            }
            write(data);return copies;
        }catch(Exception e){for(File target:created)target.delete();throw e;}
    }
    void deleteSticker(String id) throws Exception {
        Data data=read();int i=find(data.stickers,id);if(i<0)return;
        data.stickers.remove(i);write(data);
        if(!file(id).delete()&&file(id).exists())throw new Exception("索引已移除，但图片文件清理失败");
    }
    Folder createFolder(String name) throws Exception {
        Data data=read();String trimmed=validFolderName(data,name,"");
        String id=UUID.randomUUID().toString();JSONObject o=new JSONObject();o.put("id",id);o.put("name",trimmed);
        data.folders.put(o);write(data);return new Folder(id,trimmed);
    }
    void renameFolder(String id,String name) throws Exception {
        Data data=read();int i=find(data.folders,id);if(i<0)throw new IllegalArgumentException("文件夹不存在");
        data.folders.getJSONObject(i).put("name",validFolderName(data,name,id));write(data);
    }
    private String validFolderName(Data data,String name,String exceptId) throws Exception {
        String trimmed=name.trim();if(trimmed.isEmpty())throw new IllegalArgumentException("请输入文件夹名称");
        if(trimmed.length()>30)throw new IllegalArgumentException("文件夹名称最多 30 字");
        for(int i=0;i<data.folders.length();i++){
            JSONObject o=data.folders.getJSONObject(i);
            if(!o.getString("id").equals(exceptId)&&o.getString("name").equalsIgnoreCase(trimmed))
                throw new IllegalArgumentException("已有同名文件夹");
        }
        return trimmed;
    }
    void deleteFolder(String id) throws Exception {
        Data data=read();int i=find(data.folders,id);if(i<0)return;
        data.folders.remove(i);
        long now=System.currentTimeMillis();for(int n=0;n<data.stickers.length();n++){
            JSONObject sticker=data.stickers.getJSONObject(n);
            if(id.equals(sticker.optString("folderId","")))sticker.put("folderId","").put("updatedAt",now);
        }
        write(data);
    }
    File file(String id){return new File(directory,id+".png");}
}
