package com.modelcamera.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.IntConsumer;

/** Edits the extracted transparent pixels while keeping the original for reset. */
final class CutoutEditorView extends View {
    private static final class Guide {
        final boolean horizontal;
        float fraction;
        Guide(boolean horizontal,float fraction){this.horizontal=horizontal;this.fraction=fraction;}
    }
    private Bitmap original;
    private Bitmap working,display;
    private final ArrayDeque<Bitmap> undoStack=new ArrayDeque<>();
    private final ArrayList<Guide> guides=new ArrayList<>();
    private Guide activeGuide;
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private ColorFilter toneFilter;
    private final Paint overlay=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float zoom=1f,panX,panY,lastX,lastY,angle;
    private int sharpen,contrast,saturation,brightness,brushRadius=8,gridDivisions;
    private float splitFraction=.5f;
    private boolean splitEnabled,eraser,draggingSplit,erasedThisStroke,eraserGesture,cursorVisible,comparing;
    private float cursorX,cursorY;
    private PointF lastImagePoint;
    private IntConsumer splitListener;

    CutoutEditorView(Context context,Bitmap extracted){
        super(context);original=extracted;working=extracted.copy(Bitmap.Config.ARGB_8888,true);display=working;
        setBackgroundColor(0xFFECD4C7);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clearPaint.setStyle(Paint.Style.STROKE);clearPaint.setStrokeCap(Paint.Cap.ROUND);
        clearPaint.setStrokeJoin(Paint.Join.ROUND);
        scaleDetector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){
                float old=zoom;zoom=Math.max(.25f,Math.min(10f,zoom*detector.getScaleFactor()));
                float ratio=zoom/old;
                panX=detector.getFocusX()-getWidth()/2f-(detector.getFocusX()-getWidth()/2f-panX)*ratio;
                panY=detector.getFocusY()-getHeight()/2f-(detector.getFocusY()-getHeight()/2f-panY)*ratio;
                clampPan();invalidate();return true;
            }
        });
        gestureDetector=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(eraser)return false;
                if(Math.abs(zoom-1f)>.1f){zoom=1f;panX=panY=0;}
                else{
                    zoom=Math.min(10f,Math.max(2.5f,1f/Math.max(.01f,fitScale())));
                    panX=(getWidth()/2f-e.getX())*(zoom-1f);
                    panY=(getHeight()/2f-e.getY())*(zoom-1f);clampPan();
                }
                invalidate();return true;
            }
        });
    }

    Bitmap working(){return working;}
    int splitX(){
        if(!splitEnabled)return -1;
        int w=CutoutOps.rotatedWidth(imageWidth(),original.getHeight(),angle);
        if(getWidth()<=0)return Math.round(w*splitFraction);
        float imageX=(splitFraction*getWidth()-getWidth()/2f-panX)/Math.max(.001f,scale())+w/2f;
        return Math.max(0,Math.min(w,Math.round(imageX)));
    }
    boolean splitEnabled(){return splitEnabled;}
    float splitFraction(){return splitFraction;}
    int imageWidth(){return original.getWidth();}
    float angle(){return angle;}
    float previewScale(){return scale();}
    int sharpen(){return sharpen;}
    int contrast(){return contrast;}
    int saturation(){return saturation;}
    int brightness(){return brightness;}
    int gridDivisions(){return gridDivisions;}
    void onSplitChanged(IntConsumer listener){splitListener=listener;}
    void setSplitX(int x){
        if(x<0){splitEnabled=false;invalidate();return;}
        int w=CutoutOps.rotatedWidth(imageWidth(),original.getHeight(),angle);
        float screen=getWidth()/2f+panX+(x-w/2f)*scale();
        splitFraction=getWidth()>0?screen/getWidth():x/(float)w;
        splitFraction=Math.max(.02f,Math.min(.98f,splitFraction));
        splitEnabled=true;invalidate();
    }
    void setSplitPercent(int percent){
        splitFraction=Math.max(.02f,Math.min(.98f,percent/100f));
        splitEnabled=true;invalidate();
    }
    private void setSplitScreenX(float x){
        splitFraction=Math.max(.02f,Math.min(.98f,x/Math.max(1,getWidth())));
        splitEnabled=true;invalidate();
    }
    void setAngle(float degrees){angle=AngleKnob.quantize(degrees);clampPan();invalidate();}
    void setSharpen(int value){sharpen=Math.max(0,Math.min(100,value));rebuildDisplay();}
    void setContrast(int value){contrast=Math.max(-100,Math.min(100,value));updateTone();}
    void setSaturation(int value){saturation=Math.max(-100,Math.min(100,value));updateTone();}
    void setBrightness(int value){brightness=Math.max(-100,Math.min(100,value));updateTone();}
    private void updateTone(){toneFilter=CutoutOps.colorFilter(contrast,saturation,brightness);invalidate();}
    void setGridDivisions(int divisions){gridDivisions=divisions>=2?divisions:0;invalidate();}
    boolean addGuide(boolean horizontal){
        if(guides.size()>=8)return false;
        int same=0;for(Guide guide:guides)if(guide.horizontal==horizontal)same++;
        float[] positions={.5f,.38f,.62f,.25f,.75f,.15f,.85f,.44f};
        guides.add(new Guide(horizontal,positions[same]));invalidate();return true;
    }
    boolean removeLastGuide(){
        if(guides.isEmpty())return false;
        Guide removed=guides.remove(guides.size()-1);
        if(activeGuide==removed)activeGuide=null;
        invalidate();return true;
    }
    void clearGuides(){guides.clear();activeGuide=null;invalidate();}
    int guideCount(){return guides.size();}
    float guideFraction(int index){return guides.get(index).fraction;}
    void setBrushRadius(int pixels){brushRadius=Math.max(2,Math.min(60,pixels));invalidate();}
    void setEraser(boolean enabled){eraser=enabled;cursorVisible=false;invalidate();}
    void setComparing(boolean value){comparing=value;invalidate();}
    /** Re-key the same crop without losing the eraser mask, undo history, or alignment. */
    void replaceBase(Bitmap replacement){
        if(replacement.getWidth()!=imageWidth()||replacement.getHeight()!=original.getHeight())
            throw new IllegalArgumentException("重新抠图的尺寸不一致");
        Bitmap nextWorking=null;
        ArrayDeque<Bitmap> nextUndo=new ArrayDeque<>();
        try{
            nextWorking=remapErasure(working,original,replacement);
            for(Bitmap previous:undoStack)nextUndo.addLast(remapErasure(previous,original,replacement));
        }catch(RuntimeException e){
            if(nextWorking!=null)nextWorking.recycle();
            while(!nextUndo.isEmpty())nextUndo.removeLast().recycle();
            throw e;
        }
        Bitmap oldOriginal=original,oldWorking=working,oldDisplay=display;
        original=replacement;working=nextWorking;display=working;
        if(oldDisplay!=oldWorking)oldDisplay.recycle();oldWorking.recycle();oldOriginal.recycle();clearUndo();
        undoStack.addAll(nextUndo);rebuildDisplay();
    }
    private static Bitmap remapErasure(Bitmap edited,Bitmap before,Bitmap after){
        int w=before.getWidth(),h=before.getHeight(),n=w*h;
        int[] previous=new int[n],erased=new int[n],replacement=new int[n];
        before.getPixels(previous,0,w,0,0,w,h);
        edited.getPixels(erased,0,w,0,0,w,h);
        after.getPixels(replacement,0,w,0,0,w,h);
        for(int i=0;i<n;i++){
            int oldAlpha=previous[i]>>>24,editAlpha=erased[i]>>>24,newAlpha=replacement[i]>>>24;
            if(oldAlpha>0&&editAlpha<oldAlpha)newAlpha=Math.round(newAlpha*editAlpha/(float)oldAlpha);
            replacement[i]=(newAlpha<<24)|(replacement[i]&0x00FFFFFF);
        }
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        result.setPixels(replacement,0,w,0,0,w,h);return result;
    }
    boolean isEraser(){return eraser;}
    boolean canUndoErase(){return !undoStack.isEmpty();}
    void undoErase(){
        if(undoStack.isEmpty())return;
        Bitmap oldWorking=working,oldDisplay=display;
        working=undoStack.removeLast();display=working;
        if(oldDisplay!=oldWorking)oldDisplay.recycle();oldWorking.recycle();
        rebuildDisplay();
    }
    void resetErase(){
        Bitmap oldWorking=working,oldDisplay=display;
        working=original.copy(Bitmap.Config.ARGB_8888,true);display=working;
        if(oldDisplay!=oldWorking)oldDisplay.recycle();oldWorking.recycle();
        clearUndo();
        rebuildDisplay();
    }
    void release(){
        if(display!=null&&display!=working)display.recycle();
        if(working!=null)working.recycle();
        clearUndo();original.recycle();display=working=null;
    }
    private void clearUndo(){while(!undoStack.isEmpty())undoStack.removeLast().recycle();}
    private void rebuildDisplay(){
        if(display!=working)display.recycle();
        display=sharpen==0?working:BlueKey.sharpen(working,sharpen);
        invalidate();
    }
    private float fitScale(){
        return Math.min(getWidth()*.90f/imageWidth(),getHeight()*.74f/original.getHeight());
    }
    private float scale(){return fitScale()*zoom;}
    private void clampPan(){
        float halfW=CutoutOps.rotatedWidth(imageWidth(),original.getHeight(),angle)*scale()/2f;
        float halfH=CutoutOps.rotatedHeight(imageWidth(),original.getHeight(),angle)*scale()/2f;
        float visibleX=zoom>1f?getWidth()*.12f:getWidth()/2f;
        float visibleY=zoom>1f?getHeight()*.12f:getHeight()/2f;
        float limitX=Math.max(0,halfW-visibleX),limitY=Math.max(0,halfH-visibleY);
        panX=Math.max(-limitX,Math.min(limitX,panX));panY=Math.max(-limitY,Math.min(limitY,panY));
    }
    private PointF imagePoint(float sx,float sy){
        float dx=(sx-getWidth()/2f-panX)/scale(),dy=(sy-getHeight()/2f-panY)/scale();
        double r=Math.toRadians(-angle),co=Math.cos(r),si=Math.sin(r);
        return new PointF((float)(dx*co-dy*si)+imageWidth()/2f,
                (float)(dx*si+dy*co)+original.getHeight()/2f);
    }
    private boolean inside(PointF p){return p.x>=0&&p.x<imageWidth()&&p.y>=0&&p.y<original.getHeight();}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(display==null)return;
        float s=scale();
        c.save();c.translate(getWidth()/2f+panX,getHeight()/2f+panY);c.scale(s,s);c.rotate(angle);
        float w=imageWidth(),h=original.getHeight();
        imagePaint.setColorFilter(comparing?null:toneFilter);
        c.drawBitmap(comparing?original:display,-w/2f,-h/2f,imagePaint);
        c.restore();
        drawReferenceGrid(c);
        drawGuides(c);
        drawSplitLine(c);
        if(eraser&&cursorVisible){
            overlay.setColor(Color.WHITE);overlay.setStyle(Paint.Style.STROKE);overlay.setStrokeWidth(dp(1.5f));
            c.drawCircle(cursorX,cursorY,brushRadius*s,overlay);overlay.setStyle(Paint.Style.FILL);
        }
        if(Math.abs(zoom-1f)>.01f){
            overlay.setColor(0xB81B3633);overlay.setStyle(Paint.Style.FILL);
            c.drawRoundRect(getWidth()-dp(75),dp(12),getWidth()-dp(12),dp(44),dp(14),dp(14),overlay);
            overlay.setColor(Color.WHITE);overlay.setTextSize(dp(12));overlay.setTextAlign(Paint.Align.CENTER);
            c.drawText(Math.round(zoom*100)+"%",getWidth()-dp(43),dp(33),overlay);
            overlay.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawReferenceGrid(Canvas c){
        if(gridDivisions<2)return;
        float w=getWidth(),h=getHeight(),left=dp(.6f),top=dp(.6f),right=w-dp(.6f),bottom=h-dp(.6f);
        overlay.setStyle(Paint.Style.STROKE);
        for(int pass=0;pass<2;pass++){
            overlay.setColor(pass==0?0x8C24413E:0xDAFFFFFF);
            overlay.setStrokeWidth(dp(pass==0?2.7f:1.1f));
            c.drawRect(left,top,right,bottom,overlay);
            for(int i=1;i<gridDivisions;i++){
                float x=left+w*i/gridDivisions,y=top+h*i/gridDivisions;
                c.drawLine(x,top,x,bottom,overlay);
                c.drawLine(left,y,right,y,overlay);
            }
        }
        overlay.setColor(0xCC328A80);
        overlay.setStrokeWidth(dp(1.6f));
        c.drawLine(w/2f,top,w/2f,bottom,overlay);
        c.drawLine(left,h/2f,right,h/2f,overlay);
        overlay.setStyle(Paint.Style.FILL);
    }

    private void drawGuides(Canvas c){
        for(Guide guide:guides){
            float position=guide.fraction*(guide.horizontal?getHeight():getWidth());
            float hx=guide.horizontal?getWidth()-dp(22):position;
            float hy=guide.horizontal?position:dp(22);
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setColor(0xB527243A);overlay.setStrokeWidth(dp(3.6f));
            if(guide.horizontal)c.drawLine(0,position,getWidth(),position,overlay);
            else c.drawLine(position,0,position,getHeight(),overlay);
            overlay.setColor(0xFFF05A9B);overlay.setStrokeWidth(dp(2f));
            if(guide.horizontal)c.drawLine(0,position,getWidth(),position,overlay);
            else c.drawLine(position,0,position,getHeight(),overlay);
            overlay.setStyle(Paint.Style.FILL);overlay.setColor(0xFFF05A9B);
            c.drawCircle(hx,hy,dp(11),overlay);
            overlay.setColor(Color.WHITE);overlay.setTextSize(dp(11));overlay.setTextAlign(Paint.Align.CENTER);
            c.drawText(guide.horizontal?"H":"V",hx,hy+dp(4),overlay);
        }
        overlay.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSplitLine(Canvas c){
        if(!splitEnabled)return;
        float x=splitFraction*getWidth(),y=getHeight()/2f;
        overlay.setStyle(Paint.Style.STROKE);
        overlay.setColor(0xB52D2924);overlay.setStrokeWidth(dp(4));
        c.drawLine(x,0,x,getHeight(),overlay);
        overlay.setColor(0xFFFFB75C);overlay.setStrokeWidth(dp(2.3f));
        c.drawLine(x,0,x,getHeight(),overlay);
        overlay.setStyle(Paint.Style.FILL);overlay.setColor(0xFFFFB75C);
        c.drawCircle(x,y,dp(10),overlay);
        overlay.setColor(Color.WHITE);c.drawCircle(x,y,dp(3.5f),overlay);
    }

    private Guide guideAt(float x,float y){
        Guide closest=null;float distance=dp(19);
        for(Guide guide:guides){
            float position=guide.fraction*(guide.horizontal?getHeight():getWidth());
            float d=Math.abs((guide.horizontal?y:x)-position);
            if(d<distance){closest=guide;distance=d;}
        }
        return closest;
    }
    private boolean onGuideHandle(Guide guide,float x,float y){
        if(guide==null)return false;
        float hx=guide.horizontal?getWidth()-dp(22):guide.fraction*getWidth();
        float hy=guide.horizontal?guide.fraction*getHeight():dp(22);
        return Math.hypot(x-hx,y-hy)<dp(25);
    }

    private void erase(PointF from,PointF to){
        if(!inside(from)&&!inside(to))return;
        if(!erasedThisStroke){
            long bytes=(long)imageWidth()*original.getHeight()*4;
            int limit=(int)Math.max(1,Math.min(8,(24L*1024*1024)/Math.max(1,bytes)));
            undoStack.addLast(working.copy(Bitmap.Config.ARGB_8888,true));
            while(undoStack.size()>limit)undoStack.removeFirst().recycle();
            erasedThisStroke=true;
        }
        clearPaint.setStrokeWidth(brushRadius*2f);
        Canvas base=new Canvas(working);base.drawLine(from.x,from.y,to.x,to.y,clearPaint);
        clearPaint.setStyle(Paint.Style.FILL);
        base.drawCircle(to.x,to.y,brushRadius,clearPaint);
        if(display!=working){
            Canvas shown=new Canvas(display);clearPaint.setStyle(Paint.Style.STROKE);
            shown.drawLine(from.x,from.y,to.x,to.y,clearPaint);
            clearPaint.setStyle(Paint.Style.FILL);shown.drawCircle(to.x,to.y,brushRadius,clearPaint);
        }
        clearPaint.setStyle(Paint.Style.STROKE);
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(display==null)return false;
        scaleDetector.onTouchEvent(e);gestureDetector.onTouchEvent(e);
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            lastX=e.getX();lastY=e.getY();lastImagePoint=imagePoint(lastX,lastY);
            erasedThisStroke=false;
            cursorX=lastX;cursorY=lastY;
            draggingSplit=!eraser&&splitEnabled
                    &&Math.abs(lastX-splitFraction*getWidth())<dp(25);
            Guide hit=guideAt(lastX,lastY);
            activeGuide=onGuideHandle(hit,lastX,lastY)||(!draggingSplit&&!(eraser&&inside(lastImagePoint)))?hit:null;
            if(activeGuide!=null)draggingSplit=false;
            eraserGesture=eraser&&activeGuide==null;cursorVisible=eraserGesture;
            invalidate();return true;
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN){eraserGesture=false;draggingSplit=false;activeGuide=null;cursorVisible=false;invalidate();return true;}
        if(action==MotionEvent.ACTION_MOVE){
            if(e.getPointerCount()==1&&!scaleDetector.isInProgress()){
                PointF now=imagePoint(e.getX(),e.getY());
                if(activeGuide!=null){
                    float extent=activeGuide.horizontal?getHeight():getWidth();
                    float coordinate=activeGuide.horizontal?e.getY():e.getX();
                    activeGuide.fraction=Math.max(.02f,Math.min(.98f,coordinate/Math.max(1,extent)));
                    invalidate();
                }
                else if(eraser&&eraserGesture){erase(lastImagePoint,now);cursorX=e.getX();cursorY=e.getY();cursorVisible=true;}
                else if(draggingSplit){
                    setSplitScreenX(e.getX());
                    if(splitListener!=null)splitListener.accept(splitX());
                }
                else if(zoom>1.01f||zoom<.99f||Math.abs(angle)>.01f){panX+=e.getX()-lastX;panY+=e.getY()-lastY;clampPan();invalidate();}
                lastImagePoint=now;lastX=e.getX();lastY=e.getY();
            }
            return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){
            int remaining=e.getActionIndex()==0?1:0;
            lastX=e.getX(remaining);lastY=e.getY(remaining);lastImagePoint=imagePoint(lastX,lastY);return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            if(action==MotionEvent.ACTION_UP&&eraser&&eraserGesture&&!erasedThisStroke){PointF q=imagePoint(e.getX(),e.getY());erase(q,q);}
            if(erasedThisStroke)rebuildDisplay();
            cursorVisible=false;draggingSplit=false;activeGuide=null;invalidate();return true;
        }
        return true;
    }
}
