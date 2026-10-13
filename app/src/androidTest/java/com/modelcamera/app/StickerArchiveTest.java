package com.modelcamera.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.test.InstrumentationTestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class StickerArchiveTest extends InstrumentationTestCase {
    private File storage;
    private StickerRepo repo;
    @Override protected void setUp() throws Exception {
        super.setUp();Context target=getInstrumentation().getTargetContext();
        storage=new File(target.getCacheDir(),"archive-test-"+UUID.randomUUID());assertTrue(storage.mkdirs());
        repo=new StickerRepo(new ContextWrapper(target){@Override public File getFilesDir(){return storage;}});
    }
    public void testZipRoundTripGroupsPngInNamedFolder() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0);bitmap.setPixel(4,4,0xFF17384B);
        StickerRepo.Sticker a=repo.save(bitmap,"蓝瞳",5,3,"",0),b=repo.save(bitmap,"蓝瞳",5,3,"",0);bitmap.recycle();
        File archive=new File(storage,"export.zip");StickerArchive.writeZip(repo,Arrays.asList(a,b),archive);
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive.toPath()))){
            ZipEntry first=zip.getNextEntry();assertEquals("蓝瞳.png",first.getName());zip.closeEntry();
            ZipEntry second=zip.getNextEntry();assertEquals("蓝瞳 (2).png",second.getName());
        }
        StickerArchive.Imported imported=StickerArchive.importZip(repo,Files.newInputStream(archive.toPath()),"新角色.zip",storage);
        assertEquals("新角色",imported.folderName);assertEquals(2,imported.count);
        int grouped=0;for(StickerRepo.Sticker item:repo.list())if(imported.folderId.equals(item.folderId))grouped++;
        assertEquals(2,grouped);
    }
    public void testUnsafeZipPathIsRejectedWithoutCreatingFolder() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("../bad.png"));zip.write(new byte[]{1,2,3});zip.closeEntry();}
        try{StickerArchive.importZip(repo,new ByteArrayInputStream(bytes.toByteArray()),"危险.zip",storage);fail("Expected unsafe path rejection");}
        catch(Exception expected){assertNotNull(expected.getMessage());}
        assertTrue(repo.folders().isEmpty());assertTrue(repo.list().isEmpty());
    }
}
