package com.modelcamera.app;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Exercises the assist canvas gestures and selection that caused user-visible mistakes. */
public final class CameraAssistInteractionTest extends InstrumentationTestCase {
    private CameraActivity.AssistOverlayView overlay(){
        CameraActivity.AssistOverlayView view=new CameraActivity.AssistOverlayView(getInstrumentation().getTargetContext());
        CameraActivity.LevelView guides=new CameraActivity.LevelView(getInstrumentation().getTargetContext());
        view.guides=guides;
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,500,500);
        guides.measure(android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500,android.view.View.MeasureSpec.EXACTLY));
        guides.layout(0,0,500,500);
        return view;
    }
    private void event(CameraActivity.AssistOverlayView view,int action,float... positions){
        int count=positions.length/2;
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=positions[i*2];coordinates[i].y=positions[i*2+1];coordinates[i].pressure=1;coordinates[i].size=1;
        }
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,action,count,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        view.onTouchEvent(e);e.recycle();
    }
    public void testTwoFingerGestureOnlyChangesScale(){
        CameraActivity.AssistOverlayView view=overlay();view.setBitmap(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888));
        event(view,MotionEvent.ACTION_DOWN,100,200);
        event(view,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),100,200,300,200);
        event(view,MotionEvent.ACTION_MOVE,200,100,200,300);
        assertEquals(0f,view.angle,.001f);assertEquals(1f,view.scale,.001f);
        assertEquals(0f,view.offsetX,.001f);assertEquals(0f,view.offsetY,.001f);
        event(view,MotionEvent.ACTION_MOVE,150,100,250,300);
        assertTrue(view.scale>1f);assertEquals(0f,view.angle,.001f);
        view.rotateBy(90);assertEquals(90f,view.angle,.001f);
    }
    public void testEarlierGuideCanBeReselectedAndMovedFromAnywhere(){
        CameraActivity.AssistOverlayView view=overlay();
        view.guides.horizontalGuides.add(.2f);view.guides.horizontalGuides.add(.8f);
        view.setGuideMode(CameraActivity.AssistOverlayView.MODE_H,0);
        event(view,MotionEvent.ACTION_DOWN,400,400);event(view,MotionEvent.ACTION_MOVE,400,450);
        assertEquals(.3f,view.guides.horizontalGuides.get(0),.001f);
        assertEquals(.8f,view.guides.horizontalGuides.get(1),.001f);
        view.setGuideMode(CameraActivity.AssistOverlayView.MODE_H,1);
        event(view,MotionEvent.ACTION_DOWN,100,100);event(view,MotionEvent.ACTION_MOVE,100,50);
        assertEquals(.3f,view.guides.horizontalGuides.get(0),.001f);
        assertEquals(.7f,view.guides.horizontalGuides.get(1),.001f);
        assertTrue(view.deleteSelectedGuide());assertEquals(1,view.guides.horizontalGuides.size());
    }
    public void testMovingMarkerDisappearsOutsideFrame(){
        assertTrue(CameraActivity.LevelView.markerWithinFrame(32,0,57,24));
        assertFalse(CameraActivity.LevelView.markerWithinFrame(34,0,57,24));
        assertFalse(CameraActivity.LevelView.markerWithinFrame(0,-40,57,24));
    }
    public void testGuideDistanceSortsLinesAndUpdatesAfterDragging(){
        CameraActivity.AssistOverlayView view=overlay();
        view.guides.horizontalGuides.add(.8f);
        view.guides.horizontalGuides.add(.2f);
        view.guides.horizontalGuides.add(.5f);
        java.util.ArrayList<CameraActivity.LevelView.GuideGap> initial=
                CameraActivity.LevelView.guideGaps(view.guides.horizontalGuides,500);
        assertEquals(2,initial.size());
        assertEquals(150,initial.get(0).pixels);assertEquals(.35f,initial.get(0).midpoint,.001f);
        assertEquals(150,initial.get(1).pixels);assertEquals(.65f,initial.get(1).midpoint,.001f);
        view.setGuideMode(CameraActivity.AssistOverlayView.MODE_H,2);
        event(view,MotionEvent.ACTION_DOWN,400,400);event(view,MotionEvent.ACTION_MOVE,400,450);
        java.util.ArrayList<CameraActivity.LevelView.GuideGap> moved=
                CameraActivity.LevelView.guideGaps(view.guides.horizontalGuides,500);
        assertEquals(200,moved.get(0).pixels);assertEquals(100,moved.get(1).pixels);
    }
}
