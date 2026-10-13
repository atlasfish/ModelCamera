package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import java.util.Collections;
import org.json.JSONObject;

public final class EyeSpacingTest extends InstrumentationTestCase {
    private Bitmap pair(){
        Bitmap bitmap=Bitmap.createBitmap(100,30,Bitmap.Config.ARGB_8888);
        for(int y=5;y<25;y++)for(int x=15;x<=35;x++)bitmap.setPixel(x,y,Color.BLACK);
        for(int y=5;y<25;y++)for(int x=65;x<=85;x++)bitmap.setPixel(x,y,Color.BLACK);
        return bitmap;
    }
    private PhotoBoard board(Bitmap photo,Bitmap eyes){
        PhotoBoard board=new PhotoBoard(getInstrumentation().getTargetContext(),photo,PhotoBoard.EDIT);
        board.setPlacement(100,100,90);
        board.addSticker(eyes,new StickerRepo.Sticker("pair","整组眼睛",0,0,"sample",100),0,"sample");
        return board;
    }
    private int alpha(PhotoBoard board,int x,int y){Bitmap image=board.renderComposite();
        try{return Color.alpha(image.getPixel(x,y));}finally{image.recycle();}}
    public void testPairAutoAxisMovesBothEyesAndWorksWithRotation()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888),eyes=pair();
        try{
            PhotoBoard board=board(photo,eyes);
            assertTrue("the clear gap between two eyes should be detected",board.selectedEyeSpacingEnabled());
            assertEquals(50,board.selectedEyeAxis(),3);
            assertEquals(0,alpha(board,60,100));
            board.setSelectedEyeSpacing(20);
            assertEquals(255,alpha(board,60,100));assertEquals(255,alpha(board,140,100));
            assertEquals(0,alpha(board,80,100));
            board.setSelectedAngle(90);
            assertEquals(255,alpha(board,100,60));assertEquals(255,alpha(board,100,140));
        }finally{photo.recycle();eyes.recycle();}
    }
    public void testManualAxisUndoAndSessionRestore()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888),eyes=pair();
        try{
            PhotoBoard board=board(photo,eyes);board.setSelectedEyeSpacing(18);board.setSelectedEyeAxis(40);
            assertFalse(board.selectedEyeAxisAutomatic());assertEquals(40,board.selectedEyeAxis());
            JSONObject saved=board.snapshot();
            PhotoBoard restored=new PhotoBoard(getInstrumentation().getTargetContext(),photo,PhotoBoard.EDIT);
            restored.restore(saved,Collections.singletonList(eyes));
            assertTrue(restored.selectedEyeSpacingEnabled());assertEquals(18,restored.selectedEyeSpacing());
            assertEquals(40,restored.selectedEyeAxis());assertFalse(restored.selectedEyeAxisAutomatic());
            board.undo();assertTrue(board.selectedEyeAxisAutomatic());assertEquals(18,board.selectedEyeSpacing());
            board.undo();assertEquals(0,board.selectedEyeSpacing());
            JSONObject legacy=board.snapshot();JSONObject layer=legacy.getJSONArray("layers").getJSONObject(0);
            layer.remove("eyeSpacingEnabled");layer.remove("eyeAxis");layer.remove("eyeSpacing");layer.remove("eyeAxisAutomatic");
            restored.restore(legacy,Collections.singletonList(eyes));
            assertTrue("an old saved pair gets an automatic axis",restored.selectedEyeSpacingEnabled());
        }finally{photo.recycle();eyes.recycle();}
    }
    public void testSingleMarkDoesNotEnableEyeSpacingAutomatically()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
        Bitmap single=Bitmap.createBitmap(30,60,Bitmap.Config.ARGB_8888);single.eraseColor(Color.BLACK);
        try{PhotoBoard board=board(photo,single);assertFalse(board.selectedEyeSpacingEnabled());
            board.setSelectedEyeSpacingEnabled(true);assertTrue(board.selectedEyeSpacingEnabled());
            board.toggleSelectedLocked();board.setSelectedEyeSpacing(20);assertEquals(0,board.selectedEyeSpacing());
        }finally{photo.recycle();single.recycle();}
    }
}
