package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;

public final class BlueKeyReflectionTest extends InstrumentationTestCase {
    public void testFlashTintedSheetKeepsBrowsAndEyeWhites()throws Exception{
        Bitmap full;try(java.io.InputStream in=getInstrumentation().getTargetContext().getAssets().open("sample_lavender_sheet.jpg")){full=BitmapFactory.decodeStream(in);}
        Bitmap photo=Bitmap.createScaledBitmap(full,1536,2048,true);full.recycle();
        Calibration.Result scale=Calibration.detect(photo);
        assertNotNull("Visible major lines on the partly covered green mat should still calibrate",scale);
        assertEquals(33.8f,scale.pixelsPerMm,3f);
        Rect suggestion=BlueKey.suggest(photo);
        assertTrue("Select the first complete expression rather than the sheet edge: "+suggestion,
            suggestion.left>850&&suggestion.right>1400&&suggestion.top<100&&suggestion.bottom>330&&suggestion.bottom<420);
        BlueKey.Result result=BlueKey.extractFixed(photo,suggestion,50);
        assertTrue(result.blueFraction>.65f);
        assertEquals(0,result.bitmap.getPixel(40,40)>>>24);
        assertTrue("The faded eyebrows should survive cleanup",opaqueInRows(result.bitmap,70,115)>500);
        assertTrue("Gray-channel contrast should retain the faint brow edge",(result.bitmap.getPixel(166,103)>>>24)>70);
        assertTrue("Gray-channel recovery must not lift a wide halo above the brows",opaqueInRows(result.bitmap,65,85)<450);
        assertTrue("Both eyes should survive the key",opaqueInRows(result.bitmap,135,235)>5000);
        assertTrue("Warm printed eye white must not turn into a hole: rgba="+Integer.toHexString(result.bitmap.getPixel(120,195))+
            " source="+Integer.toHexString(photo.getPixel(suggestion.left+120,suggestion.top+195)),
            (result.bitmap.getPixel(120,195)>>>24)>160);
        File file=new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"lavender-sheet-cutout.png");
        try(FileOutputStream out=new FileOutputStream(file)){result.bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        BlueKey.Result lower=BlueKey.extractFixed(photo,new Rect(930,830,1510,1110),50);
        assertEquals(0,lower.bitmap.getPixel(20,20)>>>24);
        assertTrue("The reflected lower row should remain usable",opaqueInRows(lower.bitmap,20,250)>5000);
        File lowerFile=new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"lavender-sheet-lower.png");
        try(FileOutputStream out=new FileOutputStream(lowerFile)){lower.bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        result.bitmap.recycle();lower.bitmap.recycle();photo.recycle();
    }
    private int opaqueInRows(Bitmap image,int first,int last){
        int count=0;for(int y=first;y<Math.min(last,image.getHeight());y++)for(int x=0;x<image.getWidth();x++)if((image.getPixel(x,y)>>>24)>160)count++;
        return count;
    }
    public void testReflectedPaperClearsWithoutRemovingPrintedWhiteOrBluePupil(){
        int paper=Color.rgb(170,210,230);
        Bitmap photo=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);
        photo.eraseColor(paper);
        for(int y=42;y<65;y++)for(int x=17;x<67;x++)photo.setPixel(x,y,Color.rgb(35,35,40));
        for(int y=42;y<65;y++)for(int x=113;x<163;x++)photo.setPixel(x,y,Color.rgb(35,35,40));
        for(int y=49;y<59;y++)for(int x=31;x<51;x++)photo.setPixel(x,y,Color.rgb(245,244,241));
        for(int y=51;y<58;y++)for(int x=42;x<48;x++)photo.setPixel(x,y,Color.rgb(30,75,180));
        for(int y=16;y<39;y++)for(int x=75;x<106;x++)photo.setPixel(x,y,Color.rgb(204,228,240));
        for(int y=20;y<35;y++)for(int x=81;x<101;x++)photo.setPixel(x,y,Color.rgb(248,249,251));
        for(int y=68;y<74;y++)for(int x=81;x<101;x++)photo.setPixel(x,y,Color.rgb(140,205,228));
        Rect crop=new Rect(0,0,180,100);
        BlueKey.Result low=BlueKey.extractFixed(photo,crop,0);
        BlueKey.Result high=BlueKey.extractFixed(photo,crop,100);
        assertEquals(180,low.bitmap.getWidth());
        assertEquals(100,low.bitmap.getHeight());
        assertEquals(0,low.bitmap.getPixel(90,27)>>>24);
        assertEquals(0,low.bitmap.getPixel(77,18)>>>24);
        assertTrue((low.bitmap.getPixel(35,53)>>>24)>230);
        assertTrue((low.bitmap.getPixel(44,54)>>>24)>230);
        assertTrue((low.bitmap.getPixel(20,45)>>>24)>230);
        assertTrue((low.bitmap.getPixel(90,70)>>>24)>(high.bitmap.getPixel(90,70)>>>24));
        low.bitmap.recycle();high.bitmap.recycle();photo.recycle();
    }

    public void testSideLightingAndGlossDoNotBecomeStickerInk(){
        int w=240,h=120;
        Bitmap photo=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int shift=Math.round((x-120)*.2f);
            photo.setPixel(x,y,Color.rgb(210+shift,202+shift,210+shift));
        }
        for(int y=58;y<91;y++)for(int x=36;x<88;x++)photo.setPixel(x,y,Color.rgb(55,50,58));
        for(int y=58;y<91;y++)for(int x=152;x<204;x++)photo.setPixel(x,y,Color.rgb(55,50,58));
        for(int y=68;y<84;y++)for(int x=48;x<76;x++)photo.setPixel(x,y,Color.rgb(246,233,224));
        for(int y=68;y<84;y++)for(int x=164;x<192;x++)photo.setPixel(x,y,Color.rgb(246,233,224));
        for(int y=70;y<82;y++)for(int x=59;x<66;x++)photo.setPixel(x,y,Color.rgb(39,91,175));
        // A broad, smooth white flash patch sits between the two printed eyes.
        for(int y=37;y<88;y++)for(int x=100;x<140;x++){
            float radius=(x-120f)*(x-120f)/400f+(y-62f)*(y-62f)/625f;
            if(radius>=1f)continue;
            int original=photo.getPixel(x,y),r=Color.red(original),g=Color.green(original),b=Color.blue(original);
            float amount=.72f*(1f-radius);
            photo.setPixel(x,y,Color.rgb(Math.round(r+(255-r)*amount),Math.round(g+(255-g)*amount),Math.round(b+(255-b)*amount)));
        }
        BlueKey.Result result=BlueKey.extractFixed(photo,new Rect(0,0,w,h),50);
        assertTrue("Left paper shadow must clear",(result.bitmap.getPixel(25,66)>>>24)<20);
        assertTrue("Right paper highlight must clear",(result.bitmap.getPixel(215,66)>>>24)<20);
        assertTrue("Flash reflection between the eyes must clear",(result.bitmap.getPixel(120,62)>>>24)<20);
        assertTrue("Printed warm eye white must remain",(result.bitmap.getPixel(53,74)>>>24)>200);
        assertTrue("Blue iris must remain",(result.bitmap.getPixel(62,75)>>>24)>200);
        result.bitmap.recycle();photo.recycle();
    }

    public void testEarlierBlueSheetStillKeepsPrintedWhites()throws Exception{
        Bitmap full;try(java.io.InputStream in=getInstrumentation().getTargetContext().getAssets().open("sample_sheet.jpg")){full=BitmapFactory.decodeStream(in);}
        Bitmap photo=Bitmap.createScaledBitmap(full,1536,2048,true);full.recycle();
        BlueKey.Result result=BlueKey.extractFixed(photo,new Rect(920,500,1400,680),50);
        assertTrue("Blue backing must clear",(result.bitmap.getPixel(220,120)>>>24)<20);
        assertTrue("Printed white on blue backing must survive",(result.bitmap.getPixel(75,135)>>>24)>160);
        assertTrue("Dark pupil and lash must survive",(result.bitmap.getPixel(120,135)>>>24)>200
            &&(result.bitmap.getPixel(50,110)>>>24)>200);
        File file=new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"blue-sheet-local.png");
        try(FileOutputStream out=new FileOutputStream(file)){result.bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
        result.bitmap.recycle();photo.recycle();
    }
}
