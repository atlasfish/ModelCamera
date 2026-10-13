package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import java.util.Collections;
import org.json.JSONObject;

public final class PhotoBoardUndoOpacityTest extends InstrumentationTestCase {
    private PhotoBoard board(Bitmap photo,Bitmap sticker){
        PhotoBoard board=new PhotoBoard(getInstrumentation().getTargetContext(),photo,PhotoBoard.EDIT);
        board.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        board.layout(0,0,500,500);board.setPlacement(100,100,120);
        board.addSticker(sticker,new StickerRepo.Sticker("id","eye",0,0,"sample",80),0,"sample");return board;
    }
    private void touch(PhotoBoard board,int action,float x,float y){
        long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
        board.onTouchEvent(event);event.recycle();
    }
    public void testOpacityAffectsExportAndSessionWithLegacyDefault()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
        Bitmap sticker=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);sticker.eraseColor(Color.BLACK);
        try{
            PhotoBoard board=board(photo,sticker);board.setSelectedOpacity(50);
            assertEquals(50,board.selectedOpacity());
            Bitmap exported=board.renderComposite();assertEquals(128,Color.alpha(exported.getPixel(100,100)));exported.recycle();
            JSONObject saved=board.snapshot();assertEquals(.5,saved.getJSONArray("layers").getJSONObject(0).getDouble("opacity"),.001);
            PhotoBoard restored=new PhotoBoard(getInstrumentation().getTargetContext(),photo,PhotoBoard.EDIT);
            restored.restore(saved,Collections.singletonList(sticker));assertEquals(50,restored.selectedOpacity());
            Bitmap again=restored.renderComposite();assertEquals(128,Color.alpha(again.getPixel(100,100)));again.recycle();
            saved.getJSONArray("layers").getJSONObject(0).remove("opacity");
            restored.restore(saved,Collections.singletonList(sticker));assertEquals(100,restored.selectedOpacity());
        }finally{photo.recycle();sticker.recycle();}
    }
    public void testUndoGroupsSliderAndGestureAndRestoresRemovedSticker()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
        Bitmap sticker=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);sticker.eraseColor(Color.BLACK);
        try{
            PhotoBoard board=board(photo,sticker);board.beginHistoryTransaction();
            board.setSelectedOpacity(90);board.setSelectedOpacity(60);board.setSelectedOpacity(35);board.endHistoryTransaction();
            assertEquals(35,board.selectedOpacity());board.undo();assertEquals(100,board.selectedOpacity());
            float oldX=board.selectedX();
            touch(board,MotionEvent.ACTION_DOWN,20,20);
            touch(board,MotionEvent.ACTION_MOVE,40,20);touch(board,MotionEvent.ACTION_MOVE,60,20);
            touch(board,MotionEvent.ACTION_UP,60,20);
            assertTrue(board.selectedX()>oldX+10);board.undo();assertEquals(oldX,board.selectedX(),.01f);
            board.removeSelected();assertEquals(0,board.stickerCount());
            board.undo();assertEquals(1,board.stickerCount());assertEquals(100,board.selectedOpacity());
        }finally{photo.recycle();sticker.recycle();}
    }
    public void testUndoRestoresGridPhotoAndLockIndependently()throws Exception{
        Bitmap photo=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);
        Bitmap sticker=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);sticker.eraseColor(Color.BLACK);
        try{
            PhotoBoard board=board(photo,sticker);
            board.toggleSelectedLocked();assertTrue(board.isSelectedLocked());
            board.undo();assertFalse(board.isSelectedLocked());
            board.selectGrid();board.setGrid(6);assertEquals(6,board.getGrid());
            board.undo();assertEquals(3,board.getGrid());
            board.selectPhoto();board.setSelectedAngle(12.5f);assertEquals(12.5f,board.selectedAngle(),.01f);
            board.undo();assertEquals(0f,board.selectedAngle(),.01f);
        }finally{photo.recycle();sticker.recycle();}
    }
}
