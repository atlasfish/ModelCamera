package com.modelcamera.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Direct PNG exports, with a persistent user-selected directory or public Download by default. */
final class ExportStore {
    static final class Saved {
        final Uri uri;final String name,directory;
        Saved(Uri uri,String name,String directory){this.uri=uri;this.name=name;this.directory=directory;}
    }
    private final Context context;private final SharedPreferences preferences;
    ExportStore(Context context){this.context=context;preferences=context.getSharedPreferences("export_settings",Context.MODE_PRIVATE);}
    Uri directoryUri(){String value=preferences.getString("directory","");return value.isEmpty()?null:Uri.parse(value);}
    boolean usesDownloads(){return directoryUri()==null;}
    String directoryLabel(){return preferences.getString("label","内部存储 / Download");}
    void useDownloads()throws Exception{
        Uri old=directoryUri();if(!preferences.edit().remove("directory").remove("label").commit())throw new Exception("目录设置未能保存，请重试");release(old);
    }
    void selectDirectory(Uri tree,int flags)throws Exception{
        if(tree==null||!DocumentsContract.isTreeUri(tree))throw new Exception("请选择一个文件夹");
        int granted=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((granted&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new Exception("该文件夹未允许写入，请重新选择");
        ContentResolver resolver=context.getContentResolver();Uri old=directoryUri();boolean acquired=false;
        try{
            resolver.takePersistableUriPermission(tree,granted);acquired=true;
            Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));String name;
            try(Cursor c=resolver.query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
                if(c==null||!c.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1))||(c.getInt(2)&DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)==0)
                    throw new Exception("此文件夹不可写入，请选择其他位置");
                name=c.getString(0);
            }
            String label=name;
            if("com.android.externalstorage.documents".equals(tree.getAuthority())){
                String id=DocumentsContract.getTreeDocumentId(tree);int colon=id.indexOf(':');
                if(colon>=0)label=(id.startsWith("primary:")?"内部存储 / ":"外部存储 / ")+id.substring(colon+1);
            }
            if(!preferences.edit().putString("directory",tree.toString()).putString("label",label).commit())throw new Exception("目录设置未能保存，请重试");
            if(old!=null&&!old.equals(tree))release(old);
        }catch(Exception e){if(acquired&&(old==null||!old.equals(tree)))release(tree);throw e;}
    }
    private void release(Uri uri){if(uri!=null)try{
        for(android.content.UriPermission permission:context.getContentResolver().getPersistedUriPermissions())if(permission.getUri().equals(uri)){
            int flags=(permission.isReadPermission()?Intent.FLAG_GRANT_READ_URI_PERMISSION:0)|(permission.isWritePermission()?Intent.FLAG_GRANT_WRITE_URI_PERMISSION:0);
            context.getContentResolver().releasePersistableUriPermission(uri,flags);break;
        }
    }catch(SecurityException ignored){}}
    Saved save(Bitmap bitmap)throws Exception{
        String name="水贴试贴_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.CHINA).format(new Date())+"_"+UUID.randomUUID().toString().substring(0,6)+".png";
        return save(bitmap,name);
    }
    Saved saveSculpt(Bitmap bitmap)throws Exception{
        String name="水贴面雕_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.CHINA).format(new Date())+"_"+UUID.randomUUID().toString().substring(0,6)+".png";
        return save(bitmap,name);
    }
    Saved save(Bitmap bitmap,String name)throws Exception{
        if(bitmap==null||bitmap.isRecycled())throw new IllegalArgumentException("预览图片已失效，请重新导出");
        if(!name.endsWith(".png")||name.contains("/")||name.contains("\\"))throw new IllegalArgumentException("无效导出文件名");
        Uri tree=directoryUri();String label=directoryLabel();
        if(tree!=null)return saveDocument(bitmap,name,tree,label);
        if(Build.VERSION.SDK_INT>=29)return saveDownload(bitmap,name);
        return saveLegacy(bitmap,name);
    }
    Saved saveZip(File source,String name)throws Exception{
        if(source==null||!source.isFile()||!name.toLowerCase(Locale.ROOT).endsWith(".zip")||name.contains("/")||name.contains("\\"))
            throw new IllegalArgumentException("无效 ZIP 文件");
        Uri tree=directoryUri();if(tree!=null)return saveZipDocument(source,name,tree,directoryLabel());
        if(Build.VERSION.SDK_INT>=29)return saveZipDownload(source,name);
        return saveZipLegacy(source,name);
    }
    private Saved saveZipDownload(File source,String name)throws Exception{
        ContentResolver resolver=context.getContentResolver();ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new Exception("无法创建 Download 文件");
        try{
            try(OutputStream out=resolver.openOutputStream(uri,"w")){copy(source,out);}
            ContentValues finished=new ContentValues();finished.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(uri,finished,null,null)!=1)throw new Exception("ZIP 写入完成，但无法发布文件");
            return new Saved(uri,name,"内部存储 / Download");
        }catch(Exception e){try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw e;}
    }
    private Saved saveZipDocument(File source,String name,Uri tree,String label)throws Exception{
        ContentResolver resolver=context.getContentResolver();Uri uri=null;
        try{
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            uri=DocumentsContract.createDocument(resolver,parent,"application/zip",name);if(uri==null)throw new Exception("无法创建 ZIP 文件");
            try(OutputStream out=resolver.openOutputStream(uri,"w")){copy(source,out);}
            return new Saved(uri,name,label);
        }catch(Exception e){if(uri!=null)try{DocumentsContract.deleteDocument(resolver,uri);}catch(Exception ignored){}
            throw new Exception("默认目录不可用或无法写入 ZIP。可在首页设置中重新选择目录。",e);}
    }
    @SuppressWarnings("deprecation")
    private Saved saveZipLegacy(File source,String name)throws Exception{
        File directory=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new Exception("Download 目录无法创建");
        File target=new File(directory,name);
        while(target.exists())target=new File(directory,name.substring(0,name.length()-4)+"_"+UUID.randomUUID().toString().substring(0,6)+".zip");
        try(OutputStream out=new FileOutputStream(target)){copy(source,out);}
        catch(Exception e){target.delete();throw e;}
        MediaScannerConnection.scanFile(context,new String[]{target.getAbsolutePath()},new String[]{"application/zip"},null);
        return new Saved(Uri.fromFile(target),target.getName(),"内部存储 / Download");
    }
    private void copy(File source,OutputStream out)throws Exception{
        if(out==null)throw new Exception("无法打开导出文件");
        try(FileInputStream in=new FileInputStream(source)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
    }
    private Saved saveDownload(Bitmap bitmap,String name)throws Exception{
        ContentResolver resolver=context.getContentResolver();ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/");values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new Exception("无法创建 Download 文件，请检查存储空间");
        try{
            try(OutputStream stream=resolver.openOutputStream(uri,"w")){write(bitmap,stream);}
            ContentValues finished=new ContentValues();finished.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(uri,finished,null,null)!=1)throw new Exception("图片写入完成，但无法发布文件");
            return new Saved(uri,name,"内部存储 / Download");
        }catch(Exception e){try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw e;}
    }
    private Saved saveDocument(Bitmap bitmap,String name,Uri tree,String label)throws Exception{
        ContentResolver resolver=context.getContentResolver();Uri uri=null;
        try{
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            uri=DocumentsContract.createDocument(resolver,parent,"image/png",name);if(uri==null)throw new Exception("无法在默认目录创建图片");
            try(OutputStream stream=resolver.openOutputStream(uri,"w")){write(bitmap,stream);}
            return new Saved(uri,name,label);
        }catch(Exception e){if(uri!=null)try{DocumentsContract.deleteDocument(resolver,uri);}catch(Exception ignored){}
            throw new Exception("默认目录不可用或无法写入。可在首页设置中重新选择目录。",e);}
    }
    @SuppressWarnings("deprecation")
    private Saved saveLegacy(Bitmap bitmap,String name)throws Exception{
        File directory=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if(!directory.isDirectory()&&!directory.mkdirs())throw new Exception("Download 目录无法创建");
        File file=new File(directory,name);
        // Never replace an existing export, including on older Android versions.
        while(file.exists())file=new File(directory,name.substring(0,name.length()-4)+"_"+UUID.randomUUID().toString().substring(0,6)+".png");
        try{
            try(OutputStream stream=new FileOutputStream(file)){write(bitmap,stream);}
            MediaScannerConnection.scanFile(context,new String[]{file.getAbsolutePath()},new String[]{"image/png"},null);
            return new Saved(Uri.fromFile(file),file.getName(),"内部存储 / Download");
        }catch(Exception e){file.delete();throw e;}
    }
    private void write(Bitmap bitmap,OutputStream stream)throws Exception{
        if(stream==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new Exception("图片写入失败，请检查存储空间");
    }
}
