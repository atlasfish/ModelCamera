package com.modelcamera.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Each atomic archive owns its photo and sticker pixels independently of the library. */
final class SessionRepo {
    static final class Entry {
        final String id,name;final long time;final int layers;
        Entry(String id,String name,long time,int layers){this.id=id;this.name=name;this.time=time;this.layers=layers;}
    }
    static final class Loaded {
        final JSONObject document;final Bitmap photo;final List<Bitmap> stickers;
        Loaded(JSONObject d,Bitmap p,List<Bitmap> s){document=d;photo=p;stickers=s;}
    }
    static final class Capture {
        final JSONObject document;final Bitmap photo;final List<Bitmap> stickers;
        Capture(JSONObject document,Bitmap photo,List<Bitmap> stickers){this.document=document;this.photo=photo;this.stickers=stickers;}
        void release(){photo.recycle();for(Bitmap b:stickers)b.recycle();}
    }
    private final File directory;
    SessionRepo(Context context){directory=new File(context.getFilesDir(),"sessions");directory.mkdirs();}
    private File file(String id){if(!id.matches("[a-zA-Z0-9-]+"))throw new IllegalArgumentException("无效会话");return new File(directory,id+".mcs");}
    List<Entry> list(){
        List<Entry> list=new ArrayList<>();File[] files=directory.listFiles((d,n)->n.endsWith(".mcs"));if(files==null)return list;
        for(File file:files)try(ZipFile z=new ZipFile(file)){JSONObject d=read(z);
            list.add(new Entry(d.getString("id"),d.getString("name"),d.getLong("updated"),d.getJSONObject("board").getJSONArray("layers").length()));
        }catch(Exception ignored){}
        list.sort(Comparator.comparingLong((Entry e)->e.time).reversed());return list;
    }
    Capture capture(PhotoBoard board,String identity,Calibration.Result calibration)throws Exception {
        JSONObject d=new JSONObject();d.put("version",1);d.put("identity",identity);d.put("board",board.snapshot());
        if(calibration!=null){JSONObject c=new JSONObject();c.put("ppm",calibration.pixelsPerMm);c.put("dots",calibration.dotCount);c.put("auto",calibration.automatic);d.put("calibration",c);}
        Bitmap photo=board.photoBitmap().copy(Bitmap.Config.ARGB_8888,false);List<Bitmap> layers=new ArrayList<>();
        try{for(int i=0;i<board.stickerCount();i++)layers.add(board.layerBitmap(i).copy(Bitmap.Config.ARGB_8888,false));
            return new Capture(d,photo,layers);
        }catch(RuntimeException e){photo.recycle();for(Bitmap b:layers)b.recycle();throw e;}
    }
    String save(String id,String name,PhotoBoard board,String identity,Calibration.Result calibration)throws Exception {
        Capture captured=capture(board,identity,calibration);try{return save(id,name,captured);}finally{captured.release();}
    }
    String save(String id,String name,Capture captured)throws Exception {
        if(name.trim().isEmpty())throw new IllegalArgumentException("请输入会话名称");
        if(id==null)id=UUID.randomUUID().toString();
        File destination=file(id);
        JSONObject d=captured.document;d.put("id",id);d.put("name",name.trim());d.put("updated",System.currentTimeMillis());
        File temp=new File(directory,id+".tmp");
        try{
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(temp.toPath()))){
                out.putNextEntry(new ZipEntry("state.json"));out.write(d.toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
                bitmap(out,"photo.png",captured.photo);
                for(int i=0;i<captured.stickers.size();i++)bitmap(out,"layer"+i+".png",captured.stickers.get(i));
            }
            Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }finally{temp.delete();}
        return id;
    }
    private void bitmap(ZipOutputStream out,String name,Bitmap bitmap)throws Exception{
        out.putNextEntry(new ZipEntry(name));if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new Exception("无法保存会话图片");out.closeEntry();
    }
    private JSONObject read(ZipFile zip)throws Exception{try(java.io.InputStream in=zip.getInputStream(zip.getEntry("state.json"));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
        byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));}}
    Loaded load(String id)throws Exception {
        List<Bitmap> bitmaps=new ArrayList<>();Bitmap photo=null;
        try(ZipFile z=new ZipFile(file(id))){JSONObject d=read(z);photo=decode(z,"photo.png");
            int count=d.getJSONObject("board").getJSONArray("layers").length();
            for(int i=0;i<count;i++)bitmaps.add(decode(z,"layer"+i+".png"));return new Loaded(d,photo,bitmaps);
        }catch(Exception e){if(photo!=null)photo.recycle();for(Bitmap b:bitmaps)b.recycle();throw e;}
    }
    private Bitmap decode(ZipFile z,String name)throws Exception{try(java.io.InputStream in=z.getInputStream(z.getEntry(name))){Bitmap b=BitmapFactory.decodeStream(in);if(b==null)throw new Exception("会话图片损坏");return b;}}
    void delete(String id)throws Exception{Files.deleteIfExists(file(id).toPath());}
}
