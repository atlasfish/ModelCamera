package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;

public final class CutoutEditorTest extends InstrumentationTestCase {
    private Bitmap twoEyes(){
        Bitmap b=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888);
        for(int y=22;y<58;y++){
            for(int x=20;x<65;x++)b.setPixel(x,y,Color.BLACK);
            for(int x=135;x<180;x++)b.setPixel(x,y,Color.BLACK);
        }
        return b;
    }
    private void touch(CutoutEditorView view,int action,float x,float y){
        long t=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(t,t,action,x,y,0);
        view.onTouchEvent(event);event.recycle();
    }
    private boolean hasColour(Bitmap bitmap,boolean red){
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++){
            int c=bitmap.getPixel(x,y);
            if((c>>>24)>150&&(red?Color.red(c)>180&&Color.blue(c)<70:Color.blue(c)>180&&Color.red(c)<70))return true;
        }
        return false;
    }
    public void testAutomaticAndManualSplitProduceTwoIndependentTransparentStickers(){
        Bitmap input=twoEyes();
        CutoutOps.SplitSuggestion suggestion=CutoutOps.suggestSplit(input);
        assertTrue(suggestion.reliable);
        assertTrue(suggestion.x>75&&suggestion.x<125);
        Bitmap[] pair=CutoutOps.render(input,0,0,suggestion.x);
        assertEquals(2,pair.length);
        assertTrue(pair[0].getWidth()<70&&pair[1].getWidth()<70);
        assertEquals(0,pair[0].getPixel(0,0)>>>24);
        for(Bitmap b:pair)b.recycle();
        Bitmap[] rotated=CutoutOps.render(input,0,45,100);
        assertEquals(2,rotated.length);
        assertTrue(rotated[0].getWidth()>50);
        assertEquals(0,rotated[0].getPixel(0,0)>>>24);
        for(Bitmap b:rotated)b.recycle();input.recycle();
    }
    public void testEraserUndoAndManualSplitLineGesture(){
        Bitmap image=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLACK);
        CutoutEditorView view=new CutoutEditorView(getInstrumentation().getTargetContext(),image);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,500,500);
        view.setEraser(true);touch(view,MotionEvent.ACTION_DOWN,250,250);touch(view,MotionEvent.ACTION_UP,250,250);
        assertEquals(0,view.working().getPixel(100,50)>>>24);
        touch(view,MotionEvent.ACTION_DOWN,300,250);touch(view,MotionEvent.ACTION_UP,300,250);
        assertEquals(0,view.working().getPixel(122,50)>>>24);
        view.undoErase();assertEquals(255,view.working().getPixel(122,50)>>>24);
        assertEquals(0,view.working().getPixel(100,50)>>>24);
        view.undoErase();assertEquals(255,view.working().getPixel(100,50)>>>24);
        view.setEraser(false);view.setSplitX(100);
        touch(view,MotionEvent.ACTION_DOWN,250,250);
        touch(view,MotionEvent.ACTION_MOVE,303,250);
        touch(view,MotionEvent.ACTION_UP,303,250);
        assertTrue(view.splitX()>100);
        assertEquals(.606f,view.splitFraction(),.002f);
        view.release();
    }
    public void testRekeyPreservesEraseAndUndo(){
        Bitmap original=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.BLACK);
        CutoutEditorView view=new CutoutEditorView(getInstrumentation().getTargetContext(),original);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,500,500);
        view.setEraser(true);touch(view,MotionEvent.ACTION_DOWN,250,250);touch(view,MotionEvent.ACTION_UP,250,250);
        Bitmap replacement=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888);
        replacement.eraseColor(Color.rgb(20,80,180));
        view.replaceBase(replacement);
        assertEquals(0,view.working().getPixel(50,30)>>>24);
        assertEquals(Color.rgb(20,80,180),view.working().getPixel(10,10));
        view.undoErase();
        assertEquals(Color.rgb(20,80,180),view.working().getPixel(50,30));
        view.release();
    }
    public void testFullPreviewGridStaysFixedAndCustomGuidesDrag(){
        Bitmap art=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888);
        CutoutEditorView view=new CutoutEditorView(getInstrumentation().getTargetContext(),art);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,500,400);
        view.setGridDivisions(4);
        view.setSplitPercent(60);
        Bitmap before=Bitmap.createBitmap(500,400,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(before));
        assertTrue(before.getPixel(8,100)!=0xFFECD4C7);
        assertTrue(view.addGuide(true));
        touch(view,MotionEvent.ACTION_DOWN,100,200);
        touch(view,MotionEvent.ACTION_MOVE,100,275);
        touch(view,MotionEvent.ACTION_UP,100,275);
        assertEquals(.6875f,view.guideFraction(0),.02f);
        view.setAngle(34);
        Bitmap after=Bitmap.createBitmap(500,400,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(after));
        assertEquals(before.getPixel(8,100),after.getPixel(8,100));
        assertEquals(before.getPixel(300,10),after.getPixel(300,10));
        assertEquals(.6f,view.splitFraction(),.001f);
        assertEquals(.6875f,view.guideFraction(0),.02f);
        assertTrue(view.addGuide(false));
        assertEquals(2,view.guideCount());
        assertTrue(view.removeLastGuide());
        assertEquals(1,view.guideCount());
        view.clearGuides();assertEquals(0,view.guideCount());
        before.recycle();after.recycle();view.release();
    }
    public void testRotationKeepsPreviewMagnificationConstant(){
        Bitmap art=Bitmap.createBitmap(200,80,Bitmap.Config.ARGB_8888);
        CutoutEditorView view=new CutoutEditorView(getInstrumentation().getTargetContext(),art);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,500,400);
        float originalScale=view.previewScale();
        view.setAngle(45);assertEquals(originalScale,view.previewScale(),.0001f);
        view.setAngle(90);assertEquals(originalScale,view.previewScale(),.0001f);
        view.setAngle(-37);assertEquals(originalScale,view.previewScale(),.0001f);
        view.release();
    }
    public void testSplitCutsRotatedPreviewRatherThanOriginalCoordinates(){
        Bitmap art=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888);
        for(int y=5;y<16;y++)for(int x=10;x<22;x++)art.setPixel(x,y,Color.RED);
        for(int y=44;y<55;y++)for(int x=10;x<22;x++)art.setPixel(x,y,Color.BLUE);
        Bitmap[] halves=CutoutOps.render(art,0,90,30);
        assertEquals(2,halves.length);
        assertTrue(hasColour(halves[0],true)!=hasColour(halves[1],true));
        assertTrue(hasColour(halves[0],false)!=hasColour(halves[1],false));
        for(Bitmap half:halves)half.recycle();art.recycle();
    }

    public void testToneControlsRefreshPreviewAndMatchSavedSticker(){
        Bitmap art=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        for(int y=30;y<70;y++)for(int x=30;x<70;x++)art.setPixel(x,y,Color.rgb(80,110,160));
        for(int y=30;y<70;y++)for(int x=70;x<90;x++)art.setPixel(x,y,Color.argb(96,80,110,160));
        CutoutEditorView view=new CutoutEditorView(getInstrumentation().getTargetContext(),art);
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(400,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,400,400);
        Bitmap before=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(before));
        view.setContrast(20);view.setSaturation(35);view.setBrightness(15);
        Bitmap after=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(after));
        assertTrue("Sliders must update the visible preview immediately",before.getPixel(200,200)!=after.getPixel(200,200));
        Bitmap[] saved=CutoutOps.render(view.working(),view.sharpen(),view.contrast(),view.saturation(),view.brightness(),0,-1);
        assertEquals(1,saved.length);
        int visible=after.getPixel(200,200),exported=saved[0].getPixel(saved[0].getWidth()/2,saved[0].getHeight()/2);
        assertEquals("Preview and saved colour should match",visible,exported);
        Bitmap toned=CutoutOps.adjustColors(view.working(),view.contrast(),view.saturation(),view.brightness());
        assertEquals("Partial transparency must remain unchanged",96,toned.getPixel(80,50)>>>24);
        assertEquals("Clear backing must stay transparent",0,toned.getPixel(5,5)>>>24);
        view.setComparing(true);Bitmap compared=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(compared));
        assertEquals("Hold to compare should show original colour",before.getPixel(200,200),compared.getPixel(200,200));
        for(Bitmap image:saved)image.recycle();toned.recycle();before.recycle();after.recycle();compared.recycle();view.release();
    }

    public void testToneControlsCoverGrayscaleBrightnessAndContrast(){
        Bitmap art=Bitmap.createBitmap(24,12,Bitmap.Config.ARGB_8888);
        for(int y=0;y<12;y++)for(int x=0;x<24;x++)art.setPixel(x,y,x<12?Color.rgb(60,90,140):Color.rgb(170,200,235));
        Bitmap gray=CutoutOps.adjustColors(art,0,-100,0);
        int pixel=gray.getPixel(3,5);
        assertEquals(Color.red(pixel),Color.green(pixel));assertEquals(Color.green(pixel),Color.blue(pixel));
        Bitmap bright=CutoutOps.adjustColors(art,0,0,35);
        assertTrue(Color.red(bright.getPixel(3,5))>Color.red(art.getPixel(3,5))+25);
        Bitmap contrast=CutoutOps.adjustColors(art,65,0,0);
        int originalRange=Color.red(art.getPixel(17,5))-Color.red(art.getPixel(3,5));
        int increasedRange=Color.red(contrast.getPixel(17,5))-Color.red(contrast.getPixel(3,5));
        assertTrue(increasedRange>originalRange);
        gray.recycle();bright.recycle();contrast.recycle();art.recycle();
    }
}
