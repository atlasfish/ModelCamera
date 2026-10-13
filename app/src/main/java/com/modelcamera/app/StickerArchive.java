package com.modelcamera.app;

import android.graphics.Bitmap;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Flat PNG archives. ZIP paths are never used as filesystem paths. */
final class StickerArchive {
    static final class Imported {
        final String folderId,folderName;final int count;
        Imported(String folderId,String folderName,int count){this.folderId=folderId;this.folderName=folderName;this.count=count;}
    }
    private static final int MAX_ITEMS=200;
    private static final long MAX_ENTRY=20L*1024*1024,MAX_TOTAL=150L*1024*1024;
    private static String safeName(String raw){
        String name=raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        if(name.isEmpty()||name.equals(".")||name.equals(".."))name="贴纸";
        return name.length()>60?name.substring(0,60):name;
    }
    static boolean isPng(File file)throws Exception{
        byte[] expected={(byte)137,80,78,71,13,10,26,10},actual=new byte[8];
        try(InputStream in=new FileInputStream(file)){if(in.read(actual)!=8)return false;}
        return java.util.Arrays.equals(expected,actual);
    }
    static void writeZip(StickerRepo repo,List<StickerRepo.Sticker> stickers,File target)throws Exception{
        if(stickers.isEmpty())throw new IllegalArgumentException("没有可导出的贴纸");
        Set<String> used=new HashSet<>();byte[] buffer=new byte[8192];
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(target))){
            for(StickerRepo.Sticker sticker:stickers){
                File file=repo.file(sticker.id);if(!file.isFile())throw new Exception("贴纸文件缺失："+sticker.name);
                String base=safeName(sticker.name),entryName=base+".png";int suffix=2;
                while(!used.add(entryName.toLowerCase(java.util.Locale.ROOT)))entryName=base+" ("+(suffix++)+").png";
                ZipEntry entry=new ZipEntry(entryName);if(sticker.updatedAt>0)entry.setTime(sticker.updatedAt);
                out.putNextEntry(entry);
                try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
                out.closeEntry();
            }
        }catch(Exception e){target.delete();throw e;}
    }
    static Imported importZip(StickerRepo repo,InputStream source,String zipDisplayName,File cacheDir)throws Exception{
        String folderName=zipDisplayName==null?"导入贴纸":zipDisplayName.replaceFirst("(?i)\\.zip$","").trim();
        folderName=folderName.replaceAll("[\\\\/\\p{Cntrl}]","_");
        if(folderName.isEmpty())folderName="导入贴纸";
        if(folderName.length()>30)folderName=folderName.substring(0,30);
        List<File> staged=new ArrayList<>();List<String> names=new ArrayList<>();long total=0;
        try{
            try(ZipInputStream zip=new ZipInputStream(source)){
                ZipEntry entry;byte[] buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null){
                    String path=entry.getName().replace('\\','/');
                    if(path.startsWith("/")||path.matches("(?i)^[a-z]:.*")||path.contains("../")||path.startsWith(".."))throw new Exception("ZIP 含有不安全的文件路径");
                    if(entry.isDirectory()||!path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")){zip.closeEntry();continue;}
                    if(staged.size()>=MAX_ITEMS)throw new Exception("ZIP 最多导入 "+MAX_ITEMS+" 张 PNG");
                    String leaf=path.substring(path.lastIndexOf('/')+1);if(leaf.isEmpty())continue;
                    File temp=new File(cacheDir,"zip_sticker_"+UUID.randomUUID()+".png");staged.add(temp);
                    long size=0;
                    try(OutputStream out=new FileOutputStream(temp)){
                        int n;while((n=zip.read(buffer))!=-1){size+=n;total+=n;
                            if(size>MAX_ENTRY||total>MAX_TOTAL)throw new Exception("ZIP 图片或总大小超出限制");out.write(buffer,0,n);
                        }
                    }
                    names.add(leaf.substring(0,leaf.length()-4));zip.closeEntry();
                }
            }
            if(staged.isEmpty())throw new Exception("ZIP 内没有 PNG 图片");
            String folderId=null;for(StickerRepo.Folder folder:repo.folders())if(folder.name.equalsIgnoreCase(folderName)){folderId=folder.id;break;}
            boolean created=false;if(folderId==null){folderId=repo.createFolder(folderName).id;created=true;}
            List<StickerRepo.Sticker> imported=new ArrayList<>();
            try{
                for(int i=0;i<staged.size();i++){
                    if(!isPng(staged.get(i)))throw new Exception("ZIP 内含非 PNG 图片："+names.get(i));
                    Bitmap bitmap=ImageTools.load(staged.get(i),2048);
                    try{imported.add(repo.save(bitmap,safeName(names.get(i)),0,0,"",0,folderId));}
                    finally{bitmap.recycle();}
                }
            }catch(Exception e){
                for(StickerRepo.Sticker sticker:imported)try{repo.deleteSticker(sticker.id);}catch(Exception ignored){}
                if(created)try{repo.deleteFolder(folderId);}catch(Exception ignored){}
                throw e;
            }
            return new Imported(folderId,folderName,imported.size());
        }finally{for(File file:staged)file.delete();}
    }
}
