package com.modelcamera.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

public final class ExportStoreTest extends InstrumentationTestCase {
    public void testDirectDownloadPublishesTransparentPngWithoutOverwritingExistingExport()throws Exception{
        if(Build.VERSION.SDK_INT<29)return;
        Context target=getInstrumentation().getTargetContext();String namespace="export-test-"+UUID.randomUUID();
        Context isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(namespace+name,mode);}};
        ExportStore store=new ExportStore(isolated);assertTrue(store.usesDownloads());
        Bitmap image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);image.setPixel(3,4,0xFF123456);
        Uri first=null,second=null;
        try{
            String name="qa_export_"+UUID.randomUUID()+".png";
            first=store.save(image,name).uri;image.setPixel(3,4,0xFFABCDEF);second=new ExportStore(isolated).save(image,name).uri;
            assertFalse("An export must not overwrite the previous image",first.equals(second));
            try(Cursor c=target.getContentResolver().query(first,new String[]{MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.IS_PENDING},null,null,null)){
                assertNotNull(c);assertTrue(c.moveToFirst());assertEquals("Download/",c.getString(0));assertEquals("image/png",c.getString(1));assertEquals(0,c.getInt(2));
            }
            assertPixel(target,first,0xFF123456);assertPixel(target,second,0xFFABCDEF);
            assertTrue(new ExportStore(isolated).usesDownloads());store.useDownloads();assertTrue(store.usesDownloads());
        }finally{
            if(first!=null)target.getContentResolver().delete(first,null,null);if(second!=null)target.getContentResolver().delete(second,null,null);
            image.recycle();isolated.getSharedPreferences("export_settings",0).edit().clear().commit();
        }
    }
    private void assertPixel(Context context,Uri uri,int color)throws Exception{
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            Bitmap png=BitmapFactory.decodeStream(input);assertNotNull(png);assertEquals(8,png.getWidth());assertEquals(8,png.getHeight());
            assertEquals(color,png.getPixel(3,4));assertEquals(0,png.getPixel(0,0));png.recycle();
        }
    }
    public void testZipSavedToDownloadWithZipMime()throws Exception{
        if(Build.VERSION.SDK_INT<29)return;
        Context target=getInstrumentation().getTargetContext();String namespace="zip-export-test-"+UUID.randomUUID();
        Context isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(namespace+name,mode);}};
        ExportStore store=new ExportStore(isolated);
        File zip=new File(target.getCacheDir(),"qa_zip_"+UUID.randomUUID()+".zip");
        byte[] expected=new byte[]{80,75,3,4,0,0,0,0};Files.write(zip.toPath(),expected);Uri uri=null;
        try{
            uri=store.saveZip(zip,"qa_stickers_"+UUID.randomUUID()+".zip").uri;
            try(Cursor c=target.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.MIME_TYPE},null,null,null)){
                assertNotNull(c);assertTrue(c.moveToFirst());assertEquals("Download/",c.getString(0));assertEquals("application/zip",c.getString(1));
            }
            try(InputStream in=target.getContentResolver().openInputStream(uri)){byte[] actual=new byte[expected.length];assertEquals(expected.length,in.read(actual));assertTrue(java.util.Arrays.equals(expected,actual));}
        }finally{if(uri!=null)target.getContentResolver().delete(uri,null,null);zip.delete();}
    }
}
