package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.util.List;

/** Exercises real MotionEvent pointer sequences on the same board used by the editor. */
public final class PhotoBoardGestureTest extends InstrumentationTestCase {
    private PhotoBoard board(Bitmap photo) {
        PhotoBoard b=new PhotoBoard(getInstrumentation().getTargetContext(),photo,PhotoBoard.EDIT);
        b.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        b.layout(0,0,500,500);return b;
    }
    private Object field(Object object,String name)throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private void event(PhotoBoard board,int action,float... positions){
        int n=positions.length/2;
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];
        for(int i=0;i<n;i++){
            props[i]=new MotionEvent.PointerProperties();props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=positions[2*i];coords[i].y=positions[2*i+1];coords[i].pressure=1;coords[i].size=1;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,action,n,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        board.onTouchEvent(e);e.recycle();
    }
    public void testCanvasPinchKeepsTouchedPhotoPoint()throws Exception {
        Bitmap photo=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888);PhotoBoard b=board(photo);
        event(b,MotionEvent.ACTION_DOWN,100,200);
        event(b,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),100,200,300,200);
        event(b,MotionEvent.ACTION_MOVE,50,200,350,200);
        assertEquals(1.5f,(float)field(b,"zoom"),.01f);
        RectF rect=(RectF)field(b,"photoRect");
        assertEquals(400f,(200-rect.left)/(float)field(b,"scale"),1f);
        event(b,MotionEvent.ACTION_MOVE,100,200,400,200);
        rect=(RectF)field(b,"photoRect");
        assertEquals(400f,(250-rect.left)/(float)field(b,"scale"),1f);
        event(b,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),100,200,400,200);
        float beforePan=((RectF)field(b,"photoRect")).left;
        event(b,MotionEvent.ACTION_MOVE,150,200);
        assertTrue(((RectF)field(b,"photoRect")).left>beforePan);
        event(b,MotionEvent.ACTION_UP,150,200);
        b.resetViewport();assertEquals(1f,(float)field(b,"zoom"),.01f);photo.recycle();
    }
    @SuppressWarnings("unchecked")
    private PhotoBoard.Layer layer(PhotoBoard board)throws Exception {
        return ((List<PhotoBoard.Layer>)field(board,"layers")).get(0);
    }
    private PhotoBoard stickerBoard(){
        Bitmap photo=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888);PhotoBoard b=board(photo);
        Bitmap sticker=Bitmap.createBitmap(200,120,Bitmap.Config.ARGB_8888);sticker.eraseColor(0xFF222222);
        b.setPlacement(500,500,400);
        b.addSticker(sticker,new StickerRepo.Sticker("test","test",0,0,"same",200),0,"same");return b;
    }
    public void testFreeRotationAndCornerResize()throws Exception {
        PhotoBoard rotate=stickerBoard();
        event(rotate,MotionEvent.ACTION_DOWN,325,195);
        event(rotate,MotionEvent.ACTION_MOVE,280,170);
        event(rotate,MotionEvent.ACTION_UP,280,170);
        assertTrue("rotation must follow the finger rather than 15-degree steps",layer(rotate).angle< -25&&layer(rotate).angle> -45);

        PhotoBoard resize=stickerBoard();
        event(resize,MotionEvent.ACTION_DOWN,300,280);
        event(resize,MotionEvent.ACTION_MOVE,380,325);
        event(resize,MotionEvent.ACTION_UP,380,325);
        assertTrue("bottom-right handle should resize",layer(resize).width>300);
    }
    public void testBlankTapDoesNotMoveGrid()throws Exception {
        PhotoBoard b=stickerBoard();
        float before=(float)field(b,"gridX");
        event(b,MotionEvent.ACTION_DOWN,450,450);event(b,MotionEvent.ACTION_UP,450,450);
        assertEquals(before,(float)field(b,"gridX"),.01f);
    }
    public void testTwoFingersOnStickerResizeStickerNotPhoto()throws Exception {
        PhotoBoard b=stickerBoard();
        event(b,MotionEvent.ACTION_DOWN,225,250);
        event(b,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),225,250,275,250);
        event(b,MotionEvent.ACTION_MOVE,200,250,300,250);
        assertTrue(layer(b).width>300);
        assertEquals(1f,(float)field(b,"zoom"),.01f);
    }
    public void testLayerPickerLetsGridMoveFromBlankSpaceAndLockIndependently()throws Exception {
        PhotoBoard b=stickerBoard();PhotoBoard.Layer l=layer(b);
        float x=l.x,width=l.width,angle=l.angle,gridX=(float)field(b,"gridX");
        b.toggleSelectedLocked();assertTrue(b.isSelectedLocked());
        b.scaleSelected(1.3f);b.rotateSelected(30);b.removeSelected();
        assertTrue(b.hasLayers());assertEquals(width,l.width,.01f);assertEquals(angle,l.angle,.01f);
        event(b,MotionEvent.ACTION_DOWN,205,250);
        event(b,MotionEvent.ACTION_MOVE,225,255);
        event(b,MotionEvent.ACTION_UP,225,255);
        assertEquals(x,l.x,.01f);
        b.selectGrid();
        event(b,MotionEvent.ACTION_DOWN,40,40);
        event(b,MotionEvent.ACTION_MOVE,75,60);
        event(b,MotionEvent.ACTION_UP,75,60);
        assertTrue("grid moves when dragging blank canvas",(float)field(b,"gridX")>gridX+20);
        float movedGrid=(float)field(b,"gridX");
        b.toggleGridLocked();assertTrue(b.isGridLocked());
        event(b,MotionEvent.ACTION_DOWN,40,40);
        event(b,MotionEvent.ACTION_MOVE,90,90);
        event(b,MotionEvent.ACTION_UP,90,90);
        assertEquals(movedGrid,(float)field(b,"gridX"),.01f);
        assertEquals(x,l.x,.01f);
        b.selectSticker(0);assertTrue(b.isSelectedLocked());
        b.toggleSelectedLocked();assertFalse(b.isSelectedLocked());
        event(b,MotionEvent.ACTION_DOWN,40,40);
        event(b,MotionEvent.ACTION_MOVE,65,40);
        event(b,MotionEvent.ACTION_UP,65,40);
        assertTrue("selected sticker moves from blank canvas",l.x>x+40);
    }
    public void testLockedStickerPinchZoomsCanvasInstead()throws Exception {
        PhotoBoard b=stickerBoard();PhotoBoard.Layer l=layer(b);
        float width=l.width,x=l.x,angle=l.angle;
        b.toggleSelectedLocked();
        event(b,MotionEvent.ACTION_DOWN,205,250);
        event(b,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),205,250,220,250);
        event(b,MotionEvent.ACTION_MOVE,175,250,250,250);
        assertTrue((float)field(b,"zoom")>1f);
        assertEquals(width,l.width,.01f);assertEquals(x,l.x,.01f);assertEquals(angle,l.angle,.01f);
    }
    public void testPhotoPositionStaysFixedWhileScaleRotationAndExportWork()throws Exception {
        PhotoBoard b=stickerBoard();PhotoBoard.Layer l=layer(b);
        Bitmap photo=(Bitmap)field(b,"photo");photo.setPixel(100,100,0xFFFF0000);
        float stickerX=l.x;
        b.selectPhoto();
        event(b,MotionEvent.ACTION_DOWN,40,40);
        event(b,MotionEvent.ACTION_MOVE,65,55);
        event(b,MotionEvent.ACTION_UP,65,55);
        Bitmap unmoved=b.renderComposite();
        assertEquals(0xFFFF0000,unmoved.getPixel(100,100));unmoved.recycle();
        event(b,MotionEvent.ACTION_DOWN,60,60);
        event(b,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),60,60,100,60);
        event(b,MotionEvent.ACTION_MOVE,110,100,150,100);
        assertEquals(1f,(float)field(b,"photoScale"),.01f);
        assertEquals(0f,(float)field(b,"photoAngle"),.01f);
        Bitmap translatedFingers=b.renderComposite();
        assertEquals(0xFFFF0000,translatedFingers.getPixel(100,100));translatedFingers.recycle();
        event(b,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),110,100,150,100);
        event(b,MotionEvent.ACTION_UP,110,100);
        event(b,MotionEvent.ACTION_DOWN,60,60);
        event(b,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),60,60,100,60);
        event(b,MotionEvent.ACTION_MOVE,60,60,100,100);
        assertEquals(45f,(float)field(b,"photoAngle"),1f);
        assertTrue((float)field(b,"photoScale")>1.3f);
        assertEquals(stickerX,l.x,.01f);
        assertEquals(1f,(float)field(b,"zoom"),.01f);
        Bitmap output=b.renderComposite();
        assertTrue(output.getWidth()>1000);assertTrue(output.getHeight()>1000);
        output.recycle();
    }
}
