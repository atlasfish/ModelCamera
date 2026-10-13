package com.modelcamera.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.Arrays;
import java.util.List;

public final class StickerRepoTest extends InstrumentationTestCase {
    private File storage;
    private StickerRepo repo;
    @Override protected void setUp() throws Exception {
        super.setUp();
        Context target=getInstrumentation().getTargetContext();
        storage=new File(target.getCacheDir(),"sticker-repo-test-"+UUID.randomUUID());
        assertTrue(storage.mkdirs());
        repo=new StickerRepo(new ContextWrapper(target){@Override public File getFilesDir(){return storage;}});
    }
    private Bitmap sample(){
        Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);b.eraseColor(0);
        b.setPixel(4,4,0xFF123456);return b;
    }
    public void testSaveMoveRenameDeleteAndPreserveTransparency() throws Exception {
        StickerRepo.Folder eyes=repo.createFolder("眼睛");
        StickerRepo.Folder brows=repo.createFolder("眉毛");
        Bitmap bitmap=sample();
        StickerRepo.Sticker saved=repo.save(bitmap,"蓝瞳",0,0,"",0,eyes.id);bitmap.recycle();
        assertEquals(eyes.id,repo.list().get(0).folderId);
        assertTrue(repo.list().get(0).createdAt>0);
        assertTrue(repo.list().get(0).updatedAt>=repo.list().get(0).createdAt);
        Bitmap decoded=BitmapFactory.decodeFile(repo.file(saved.id).getAbsolutePath());
        assertEquals(0,decoded.getPixel(0,0)>>>24);
        assertEquals(0xFF123456,decoded.getPixel(4,4));decoded.recycle();
        repo.renameSticker(saved.id,"新名字");repo.moveSticker(saved.id,brows.id);
        assertEquals("新名字",repo.list().get(0).name);
        assertEquals(brows.id,repo.list().get(0).folderId);
        repo.deleteFolder(brows.id);
        assertEquals("",repo.list().get(0).folderId);
        assertEquals(1,repo.folders().size());
        repo.deleteSticker(saved.id);
        assertTrue(repo.list().isEmpty());assertFalse(repo.file(saved.id).exists());
    }
    public void testMigratesOriginalArrayIndexWithoutLosingSticker() throws Exception {
        File directory=new File(storage,"stickers");
        Bitmap bitmap=sample();try(FileOutputStream out=new FileOutputStream(new File(directory,"old.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        JSONObject old=new JSONObject();old.put("id","old");old.put("name","旧贴纸");old.put("widthMm",8);
        JSONArray array=new JSONArray();array.put(old);
        Files.write(new File(directory,"index.json").toPath(),array.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("旧贴纸",repo.list().get(0).name);
        assertEquals("",repo.list().get(0).folderId);
        StickerRepo.Folder folder=repo.createFolder("收藏");repo.moveSticker("old",folder.id);
        assertEquals(folder.id,repo.list().get(0).folderId);
        assertEquals("旧贴纸",repo.list().get(0).name);
    }
    public void testBatchMoveAndCopyKeepImagesAndMetadata() throws Exception {
        StickerRepo.Folder source=repo.createFolder("原文件夹"),target=repo.createFolder("目标文件夹");
        Bitmap bitmap=sample();
        StickerRepo.Sticker first=repo.save(bitmap,"左眼",4,2,"same-photo",100,source.id);
        StickerRepo.Sticker second=repo.save(bitmap,"右眼",5,2,"same-photo",120,source.id);bitmap.recycle();
        List<String> ids=Arrays.asList(first.id,second.id);
        repo.moveStickers(ids,target.id);
        assertEquals(target.id,repo.list().get(0).folderId);
        assertEquals(target.id,repo.list().get(1).folderId);
        List<StickerRepo.Sticker> copies=repo.copyStickers(ids,source.id);
        assertEquals(2,copies.size());assertEquals(4,repo.list().size());
        assertEquals(source.id,copies.get(0).folderId);assertEquals(4f,copies.get(0).widthMm);
        assertEquals("same-photo",copies.get(1).sourcePhoto);
        Bitmap copied=BitmapFactory.decodeFile(repo.file(copies.get(0).id).getAbsolutePath());
        assertEquals(0,copied.getPixel(0,0)>>>24);assertEquals(0xFF123456,copied.getPixel(4,4));copied.recycle();
        try{repo.moveStickers(Arrays.asList(first.id,"missing"),source.id);fail("Expected missing sticker error");}
        catch(IllegalArgumentException expected){}
        for(StickerRepo.Sticker item:repo.list())if(item.id.equals(first.id))assertEquals(target.id,item.folderId);
    }
}
