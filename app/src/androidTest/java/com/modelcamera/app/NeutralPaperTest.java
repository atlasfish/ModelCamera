package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;

public final class NeutralPaperTest extends InstrumentationTestCase {
    public void testNeutralWhiteBalanceAndExposureVariants(){
        for(int base:new int[]{145,180,215,240})for(int tint:new int[]{-7,0,7}){
            Bitmap photo=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888);
            photo.eraseColor(android.graphics.Color.rgb(base+tint,base,base-tint));
            android.graphics.Canvas canvas=new android.graphics.Canvas(photo);
            android.graphics.Paint paint=new android.graphics.Paint();
            paint.setColor(0xff252530);canvas.drawRect(30,40,90,90,paint);canvas.drawRect(150,40,210,90,paint);
            paint.setColor(0xff2459b5);canvas.drawRect(50,55,75,80,paint);
            paint.setColor(0xfff8f7f5);canvas.drawRect(165,55,190,80,paint);
            BlueKey.Result result=BlueKey.extractFixed(photo,new Rect(0,0,240,120),50);
            assertEquals("Neutral background "+base+" tint "+tint,0,result.bitmap.getPixel(120,60)>>>24);
            assertTrue("Blue pupil",(result.bitmap.getPixel(60,60)>>>24)>200);
            assertTrue("Enclosed eye white",(result.bitmap.getPixel(175,60)>>>24)>200);
            result.bitmap.recycle();photo.recycle();
        }
    }
    public void testQqNeutralPaper() throws Exception {
        Bitmap photo;
        try(java.io.InputStream in=getInstrumentation().getContext().getAssets().open("sample_neutral_sheet.jpg")) {
            photo=BitmapFactory.decodeStream(in);
        }
        Rect crop=BlueKey.suggest(photo);
        assertTrue("First complete eye pair, not mat or face: "+crop,
            crop.left>280 && crop.right<1080 && crop.top<190 && crop.bottom>340 && crop.bottom<450);
        BlueKey.Result result=BlueKey.extractFixed(photo,crop,50);
        assertEquals("Paper between the eyes must clear",0,result.bitmap.getPixel(660-crop.left,250-crop.top)>>>24);
        assertTrue("Purple iris must remain",(result.bitmap.getPixel(510-crop.left,285-crop.top)>>>24)>200);
        assertTrue("Left brow must remain",(result.bitmap.getPixel(495-crop.left,182-crop.top)>>>24)>150);
        assertTrue("Pale printed white must remain",(result.bitmap.getPixel(450-crop.left,310-crop.top)>>>24)>200);
        try(FileOutputStream out=new FileOutputStream(new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"neutral-paper-cutout.png"))) {
            result.bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        result.bitmap.recycle();photo.recycle();
    }

    public void testPreviewFlow() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        File photo=new File(context.getCacheDir(),"neutral-qa.jpg");
        try(java.io.InputStream in=getInstrumentation().getContext().getAssets().open("sample_neutral_sheet.jpg");FileOutputStream out=new FileOutputStream(photo)){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        android.content.Intent intent=new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)getInstrumentation().startActivitySync(intent);
        Throwable[] failure=new Throwable[1];
        getInstrumentation().runOnMainSync(()->{try{
            java.lang.reflect.Method open=MainActivity.class.getDeclaredMethod("openPhoto",File.class,boolean.class);open.setAccessible(true);open.invoke(activity,photo,true);
            java.lang.reflect.Method preview=MainActivity.class.getDeclaredMethod("previewCutout");preview.setAccessible(true);preview.invoke(activity);
            java.lang.reflect.Field editor=MainActivity.class.getDeclaredField("cutoutEditor");editor.setAccessible(true);assertNotNull(editor.get(activity));
        }catch(Throwable e){failure[0]=e;}});
        getInstrumentation().waitForIdleSync();if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
}
