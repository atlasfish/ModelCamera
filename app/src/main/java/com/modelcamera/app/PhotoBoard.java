package com.modelcamera.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

final class PhotoBoard extends View {
    static final int CROP=1, EDIT=2, CALIBRATE=3;
    static final int TARGET_VIEW=0,TARGET_PHOTO=1,TARGET_GRID=2,TARGET_STICKER=3;
    static final class Layer {
        final Bitmap bitmap;
        final StickerRepo.Sticker sticker;
        float x,y,width,angle,opacity=1f,eyeAxis=.5f,eyeSpacing;
        boolean eyeSpacingEnabled,eyeAxisAutomatic=true;
        boolean locked;
        Layer(Bitmap b,StickerRepo.Sticker s,float x,float y,float width){bitmap=b;sticker=s;this.x=x;this.y=y;this.width=width;}
        float height(){return width*bitmap.getHeight()/bitmap.getWidth();}
        float displayWidth(){return width*(eyeSpacingEnabled?1f+eyeSpacing/100f:1f);}
    }
    interface CalibrationListener {void onPoints(PointF a,PointF b);}
    private final Paint p=new Paint(3);
    private Bitmap photo;
    private int mode;
    private RectF photoRect=new RectF(),crop=new RectF();
    private final List<Layer> layers=new ArrayList<>();
    private int selected=-1,grid=3,target=TARGET_VIEW;
    private boolean gridLocked;
    private float photoScale=1f,photoAngle;
    private float initialX,initialY,gridX,gridY,gridSize;
    private float scale,fitScale,zoom=1f,panX,panY;
    private float downX,downY,lastX,lastY,lastScreenX,lastScreenY;
    private float initialDistance,initialAngle,initialWidth,initialRotation,initialLayerX,initialLayerY;
    private float pinchZoom,pinchImageX,pinchImageY,pinchMidX,pinchMidY;
    private float pinchPhotoScale,pinchPhotoAngle,pinchGridSize;
    private float lastRotationAngle,resizeDistance;
    private int gesture;
    private boolean suppressTap;
    private static final int NONE=0,CROP_MOVE=1,CROP_RESIZE=2,CROP_CREATE=3,STICKER_MOVE=4,
            STICKER_ROTATE=5,STICKER_RESIZE=6,GRID_MOVE=7,CANVAS_PAN=8,CANVAS_PINCH=9,STICKER_PINCH=10,
            PHOTO_PINCH=12,GRID_PINCH=13;
    private PointF firstPoint;
    private CalibrationListener calibrationListener;
    private Runnable selectionListener;
    private Runnable transformListener;
    private Runnable historyListener;
    private static final int MAX_UNDO=40;
    private static final class HistoryFrame {
        final JSONObject state;
        final List<Bitmap> bitmaps;
        HistoryFrame(JSONObject state,List<Bitmap> bitmaps){this.state=state;this.bitmaps=bitmaps;}
    }
    private final ArrayDeque<HistoryFrame> undoStack=new ArrayDeque<>();
    private HistoryFrame historyTransaction,gestureStart;
    private final List<PointF> faceOutline=new ArrayList<>();
    private boolean outlineVisible=true,pendingViewport;
    private float outlineOffset;
    private float restorePanX,restorePanY;
    private Consumer<PointF> facePick;

    PhotoBoard(Context context,Bitmap photo,int mode){super(context);this.photo=photo;this.mode=mode;initialX=gridX=photo.getWidth()/2f;initialY=gridY=photo.getHeight()*.54f;gridSize=photo.getWidth()*.5f;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    void setMode(int mode){this.mode=mode;firstPoint=null;invalidate();}
    void setPhoto(Bitmap photo){this.photo=photo;invalidate();}
    void setCrop(Rect box){crop.set(box);invalidate();}
    Rect cropRect(){return ImageTools.clamp(new Rect(Math.round(crop.left),Math.round(crop.top),Math.round(crop.right),Math.round(crop.bottom)),photo);}
    void setGrid(int divisions){edit(()->{grid=divisions;invalidate();});}
    int getGrid(){return grid;}
    int activeTarget(){return target;}
    int activeStickerIndex(){return target==TARGET_STICKER?selected:-1;}
    int stickerCount(){return layers.size();}
    String stickerName(int index){return layers.get(index).sticker.name;}
    void selectView(){setTarget(TARGET_VIEW,-1);}
    void selectPhoto(){setTarget(TARGET_PHOTO,-1);}
    void selectGrid(){setTarget(TARGET_GRID,-1);}
    void selectSticker(int index){if(index>=0&&index<layers.size())setTarget(TARGET_STICKER,index);}
    private void setTarget(int next,int index){target=next;selected=index;gesture=NONE;selectionChanged();invalidate();}
    boolean hasSelection(){return target==TARGET_STICKER&&selected>=0&&selected<layers.size();}
    boolean isSelectedLocked(){return hasSelection()&&layers.get(selected).locked;}
    int selectedOpacity(){return hasSelection()?Math.round(layers.get(selected).opacity*100):100;}
    void setSelectedOpacity(int percent){if(!hasSelection())return;edit(()->{
        layers.get(selected).opacity=clamp(percent/100f,0f,1f);transformed();invalidate();
    });}
    boolean selectedEyeSpacingEnabled(){return hasSelection()&&layers.get(selected).eyeSpacingEnabled;}
    int selectedEyeSpacing(){return hasSelection()?Math.round(layers.get(selected).eyeSpacing):0;}
    int selectedEyeAxis(){return hasSelection()?Math.round(layers.get(selected).eyeAxis*100):50;}
    boolean selectedEyeAxisAutomatic(){return hasSelection()&&layers.get(selected).eyeAxisAutomatic;}
    void setSelectedEyeSpacingEnabled(boolean enabled){if(!hasSelection()||isSelectedLocked())return;
        edit(()->{Layer l=layers.get(selected);l.eyeSpacingEnabled=enabled;transformed();selectionChanged();invalidate();});
    }
    void setSelectedEyeSpacing(int percent){if(!hasSelection()||isSelectedLocked())return;
        edit(()->{layers.get(selected).eyeSpacing=clamp(percent,-30,60);transformed();invalidate();});
    }
    void setSelectedEyeAxis(int percent){if(!hasSelection()||isSelectedLocked())return;
        edit(()->{Layer l=layers.get(selected);l.eyeAxis=clamp(percent/100f,.2f,.8f);l.eyeAxisAutomatic=false;transformed();invalidate();});
    }
    boolean autoSelectedEyeAxis(){if(!hasSelection()||isSelectedLocked())return false;
        Layer l=layers.get(selected);CutoutOps.SplitSuggestion suggested=CutoutOps.suggestSplit(l.bitmap);
        edit(()->{l.eyeAxis=clamp(suggested.x/(float)l.bitmap.getWidth(),.2f,.8f);
            l.eyeAxisAutomatic=true;l.eyeSpacingEnabled=true;transformed();selectionChanged();invalidate();});
        return suggested.reliable;
    }
    private void suggestEyePair(Layer l){
        if(l.bitmap.getWidth()<l.bitmap.getHeight()*1.3f)return;
        CutoutOps.SplitSuggestion suggested=CutoutOps.suggestSplit(l.bitmap);
        l.eyeAxis=clamp(suggested.x/(float)l.bitmap.getWidth(),.2f,.8f);
        if(!suggested.reliable||l.eyeAxis<.3f||l.eyeAxis>.7f)return;
        int w=l.bitmap.getWidth(),h=l.bitmap.getHeight(),left=0,right=0;
        for(int y=0;y<h;y+=2)for(int x=0;x<w;x+=2){int pixel=l.bitmap.getPixel(x,y);
            if((pixel>>>24)<50)continue;
            int brightness=(((pixel>>16)&255)+((pixel>>8)&255)+(pixel&255))/3;
            if(brightness<185){if(x<suggested.x)left++;else right++;}
        }
        l.eyeSpacingEnabled=left>20&&right>20&&Math.min(left,right)*4>=Math.max(left,right);
    }
    boolean isGridLocked(){return gridLocked;}
    void toggleGridLocked(){edit(()->{gridLocked=!gridLocked;gesture=NONE;selectionChanged();invalidate();});}
    void onSelectionChanged(Runnable listener){selectionListener=listener;}
    void onTransformChanged(Runnable listener){transformListener=listener;}
    void onHistoryChanged(Runnable listener){historyListener=listener;}
    boolean canUndo(){return !undoStack.isEmpty();}
    void undo(){
        if(undoStack.isEmpty())return;
        HistoryFrame previous=undoStack.removeLast();historyTransaction=gestureStart=null;
        try{applyState(previous.state,previous.bitmaps);}catch(Exception e){throw new IllegalStateException("无法撤回试贴操作",e);}
        if(historyListener!=null)historyListener.run();
    }
    void beginHistoryTransaction(){if(historyTransaction==null)historyTransaction=captureHistory();}
    void endHistoryTransaction(){if(historyTransaction!=null){HistoryFrame before=historyTransaction;historyTransaction=null;recordHistory(before);}}
    private HistoryFrame captureHistory(){
        try{List<Bitmap> bitmaps=new ArrayList<>();for(Layer l:layers)bitmaps.add(l.bitmap);return new HistoryFrame(snapshot(),bitmaps);}
        catch(Exception e){throw new IllegalStateException("无法记录试贴操作",e);}
    }
    private void recordHistory(HistoryFrame before){
        if(before==null||historyTransaction!=null)return;
        try{if(before.state.toString().equals(snapshot().toString()))return;}
        catch(Exception e){throw new IllegalStateException("无法检查试贴操作",e);}
        if(undoStack.size()>=MAX_UNDO)undoStack.removeFirst();undoStack.addLast(before);
        if(historyListener!=null)historyListener.run();
    }
    private void edit(Runnable change){HistoryFrame before=historyTransaction==null?captureHistory():null;change.run();recordHistory(before);}
    private void transformed(){if(transformListener!=null)transformListener.run();}
    Bitmap photoBitmap(){return photo;}
    Bitmap layerBitmap(int index){return layers.get(index).bitmap;}
    float selectedX(){return hasSelection()?layers.get(selected).x:gridCenter().x;}
    float selectedY(){return hasSelection()?layers.get(selected).y:gridCenter().y;}
    int photoWidth(){return photo.getWidth();}
    int photoHeight(){return photo.getHeight();}
    void setSelectedCoordinate(boolean horizontal,float value){
        edit(()->setSelectedCoordinateInternal(horizontal,value));
    }
    private void setSelectedCoordinateInternal(boolean horizontal,float value){
        if(hasSelection()&&!isSelectedLocked()){
            Layer l=layers.get(selected);if(horizontal)l.x=clamp(value,0,photoWidth());else l.y=clamp(value,0,photoHeight());
        }else if(target==TARGET_GRID&&!gridLocked){
            float half=Math.min(gridSize,Math.min(photoWidth(),photoHeight()))/2f;
            if(horizontal)gridX=clamp(value,half,photoWidth()-half);else gridY=clamp(value,half,photoHeight()-half);
        }
        transformed();invalidate();
    }
    PointF faceSeed(){return new PointF(initialX,initialY);}
    float faceRegionSize(){return gridSize;}
    void pickFace(Consumer<PointF> listener){facePick=listener;}
    boolean hasOutline(){return !faceOutline.isEmpty();}
    float outlineOffset(){return outlineOffset;}
    float maxOutlineOffset(){return Math.max(4f,Math.min(photoWidth(),photoHeight())*.025f);}
    void setOutlineOffset(float pixels){if(!hasOutline())return;edit(()->{
        outlineOffset=clamp(pixels,-maxOutlineOffset(),maxOutlineOffset());transformed();invalidate();
    });}
    boolean outlineVisible(){return outlineVisible;}
    void toggleOutline(){edit(()->{outlineVisible=!outlineVisible;invalidate();});}
    void setOutline(List<PointF> points){edit(()->{faceOutline.clear();faceOutline.addAll(points);outlineOffset=0;outlineVisible=true;selectionChanged();invalidate();});}
    JSONObject snapshot()throws Exception{
        JSONObject o=new JSONObject();o.put("initialX",initialX);o.put("initialY",initialY);o.put("gridX",gridX);o.put("gridY",gridY);
        o.put("gridSize",gridSize);o.put("grid",grid);o.put("gridLocked",gridLocked);o.put("photoScale",photoScale);o.put("photoAngle",photoAngle);
        o.put("zoom",zoom);o.put("panX",getWidth()>0?panX/getWidth():restorePanX);o.put("panY",getHeight()>0?panY/getHeight():restorePanY);
        o.put("target",target);o.put("selected",selected);o.put("outlineVisible",outlineVisible);o.put("outlineOffset",outlineOffset);
        JSONArray points=new JSONArray();for(PointF point:faceOutline)points.put(new JSONArray().put(point.x).put(point.y));o.put("outline",points);
        JSONArray list=new JSONArray();for(Layer l:layers){
            JSONObject s=new JSONObject();s.put("id",l.sticker.id);s.put("name",l.sticker.name);s.put("folder",l.sticker.folderId);
            s.put("widthMm",l.sticker.widthMm);s.put("heightMm",l.sticker.heightMm);s.put("source",l.sticker.sourcePhoto);s.put("sourceWidth",l.sticker.sourceWidthPx);
            s.put("x",l.x);s.put("y",l.y);s.put("width",l.width);s.put("angle",l.angle);s.put("locked",l.locked);s.put("opacity",l.opacity);
            s.put("eyeSpacingEnabled",l.eyeSpacingEnabled);s.put("eyeAxis",l.eyeAxis);s.put("eyeSpacing",l.eyeSpacing);
            s.put("eyeAxisAutomatic",l.eyeAxisAutomatic);list.put(s);
        }o.put("layers",list);return o;
    }
    void restore(JSONObject o,List<Bitmap> bitmaps)throws Exception{
        undoStack.clear();historyTransaction=gestureStart=null;applyState(o,bitmaps);
        if(historyListener!=null)historyListener.run();
    }
    private void applyState(JSONObject o,List<Bitmap> bitmaps)throws Exception{
        initialX=(float)o.getDouble("initialX");initialY=(float)o.getDouble("initialY");gridX=(float)o.getDouble("gridX");gridY=(float)o.getDouble("gridY");
        gridSize=(float)o.getDouble("gridSize");grid=o.getInt("grid");gridLocked=o.getBoolean("gridLocked");
        photoScale=(float)o.getDouble("photoScale");photoAngle=(float)o.getDouble("photoAngle");zoom=(float)o.getDouble("zoom");
        restorePanX=(float)o.optDouble("panX",0);restorePanY=(float)o.optDouble("panY",0);pendingViewport=true;
        target=o.optInt("target",TARGET_VIEW);selected=o.optInt("selected",-1);
        JSONArray list=o.getJSONArray("layers");if(list.length()!=bitmaps.size())throw new IllegalArgumentException("会话图层数量不一致");
        layers.clear();for(int i=0;i<list.length();i++){
            JSONObject s=list.getJSONObject(i);StickerRepo.Sticker sticker=new StickerRepo.Sticker(s.getString("id"),s.getString("name"),
                (float)s.optDouble("widthMm",0),(float)s.optDouble("heightMm",0),s.optString("source",""),(float)s.optDouble("sourceWidth",0),s.optString("folder",""));
            Layer l=new Layer(bitmaps.get(i),sticker,(float)s.getDouble("x"),(float)s.getDouble("y"),(float)s.getDouble("width"));
            l.angle=(float)s.getDouble("angle");l.locked=s.getBoolean("locked");l.opacity=clamp((float)s.optDouble("opacity",1),0,1);
            if(s.has("eyeSpacingEnabled")){
                l.eyeSpacingEnabled=s.optBoolean("eyeSpacingEnabled",false);
                l.eyeAxis=clamp((float)s.optDouble("eyeAxis",.5),.2f,.8f);
                l.eyeSpacing=clamp((float)s.optDouble("eyeSpacing",0),-30,60);
                l.eyeAxisAutomatic=s.optBoolean("eyeAxisAutomatic",true);
            }else suggestEyePair(l);
            layers.add(l);
        }
        faceOutline.clear();JSONArray points=o.optJSONArray("outline");if(points!=null)for(int i=0;i<points.length();i++){
            JSONArray point=points.getJSONArray(i);faceOutline.add(new PointF((float)point.getDouble(0),(float)point.getDouble(1)));}
        outlineVisible=o.optBoolean("outlineVisible",true);outlineOffset=clamp((float)o.optDouble("outlineOffset",0),-maxOutlineOffset(),maxOutlineOffset());updateViewport();selectionChanged();transformed();invalidate();
    }
    private void selectionChanged(){if(selectionListener!=null)selectionListener.run();}
    void toggleSelectedLocked(){
        if(!hasSelection())return;
        edit(()->{layers.get(selected).locked=!layers.get(selected).locked;
            gesture=NONE;selectionChanged();invalidate();});
    }
    void resetViewport(){edit(()->{zoom=1f;panX=0;panY=0;updateViewport();invalidate();});}
    void resetPhoto(){edit(()->{photoScale=1f;photoAngle=0;transformed();selectionChanged();invalidate();});}
    void zoomViewport(float multiplier){edit(()->{updateViewport();zoomAround(zoom*multiplier,photo.getWidth()/2f,photo.getHeight()/2f,getWidth()/2f,getHeight()/2f);});}
    float selectedAngle(){return target==TARGET_PHOTO?photoAngle:hasSelection()?layers.get(selected).angle:0;}
    void setSelectedAngle(float degrees){
        edit(()->setSelectedAngleInternal(degrees));
    }
    private void setSelectedAngleInternal(float degrees){
        if(target==TARGET_PHOTO)photoAngle=AngleKnob.quantize(degrees);
        else if(hasSelection()&&!isSelectedLocked())layers.get(selected).angle=AngleKnob.quantize(degrees);
        transformed();invalidate();
    }
    void setPlacement(float x,float y,float size){initialX=x;initialY=y;gridX=x;gridY=y;gridSize=size;invalidate();}
    void onCalibration(CalibrationListener l){calibrationListener=l;}
    void addSticker(Bitmap bitmap,StickerRepo.Sticker sticker,float facePpm,String currentPhoto){
        edit(()->addStickerInternal(bitmap,sticker,facePpm,currentPhoto));
    }
    private void addStickerInternal(Bitmap bitmap,StickerRepo.Sticker sticker,float facePpm,String currentPhoto){
        float width=currentPhoto.equals(sticker.sourcePhoto)&&sticker.sourceWidthPx>0 ? sticker.sourceWidthPx :
                facePpm>0&&sticker.widthMm>0 ? sticker.widthMm*facePpm : photo.getWidth()*.38f;
        width=Math.max(photo.getWidth()*.07f,Math.min(photo.getWidth()*.8f,width));
        Layer layer=new Layer(bitmap,sticker,initialX,initialY,width);
        suggestEyePair(layer);
        layers.add(layer);setTarget(TARGET_STICKER,layers.size()-1);
    }
    void removeSelected(){if(hasSelection()&&!isSelectedLocked())edit(()->{layers.remove(selected);setTarget(layers.isEmpty()?TARGET_VIEW:TARGET_STICKER,layers.size()-1);});}
    boolean hasLayers(){return !layers.isEmpty();}
    void rotateSelected(float degrees){setSelectedAngle(selectedAngle()+degrees);}
    void scaleSelected(float multiplier){
        if(target==TARGET_VIEW){zoomViewport(multiplier);return;}
        edit(()->scaleSelectedInternal(multiplier));
    }
    private void scaleSelectedInternal(float multiplier){
        if(target==TARGET_PHOTO)photoScale=clamp(photoScale*multiplier,.25f,2f);
        else if(target==TARGET_GRID&&!gridLocked)gridSize=clamp(gridSize*multiplier,photo.getWidth()*.08f,Math.min(photo.getWidth(),photo.getHeight()));
        else if(hasSelection()&&!isSelectedLocked()){
            Layer l=layers.get(selected);l.width=clamp(l.width*multiplier,20,photo.getWidth()*1.5f);
        }
        transformed();invalidate();
    }
    void moveSelectedBy(float dx,float dy){if(hasSelection()&&!isSelectedLocked())edit(()->{
        Layer l=layers.get(selected);l.x=Math.max(0,Math.min(photo.getWidth(),l.x+dx));
        l.y=Math.max(0,Math.min(photo.getHeight(),l.y+dy));transformed();invalidate();
    });}
    Bitmap renderComposite(){
        RectF bounds=contentBounds();float minX=bounds.left,minY=bounds.top,maxX=bounds.right,maxY=bounds.bottom;
        float fullW=maxX-minX,fullH=maxY-minY;
        float exportScale=Math.min(1f,4096f/Math.max(fullW,fullH));
        Bitmap out=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(fullW*exportScale)),Math.max(1,(int)Math.ceil(fullH*exportScale)),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);c.scale(exportScale,exportScale);c.translate(-minX,-minY);
        drawPhoto(c);
        for(Layer l:layers)drawLayer(c,l,1);
        return out;
    }
    Bitmap renderSculptCutout(){
        if(faceOutline.size()<3)throw new IllegalStateException("请先识别面雕边界，再导出透明面雕");
        Path face=outlinePath();Matrix photoTransform=new Matrix();
        photoTransform.postTranslate(-photoWidth()/2f,-photoHeight()/2f);
        photoTransform.postScale(photoScale,photoScale);
        photoTransform.postRotate(photoAngle);
        photoTransform.postTranslate(photoWidth()/2f,photoHeight()/2f);
        face.transform(photoTransform);
        RectF bounds=new RectF();face.computeBounds(bounds,true);
        for(Layer l:layers){
            float hw=l.displayWidth()/2f,hh=l.height()/2f;
            double rad=Math.toRadians(l.angle),co=Math.cos(rad),si=Math.sin(rad);
            for(float x:new float[]{-hw,hw})for(float y:new float[]{-hh,hh}){
                float tx=l.x+(float)(x*co-y*si),ty=l.y+(float)(x*si+y*co);
                bounds.union(tx,ty);
            }
        }
        bounds.inset(-2f,-2f);
        float exportScale=Math.min(1f,4096f/Math.max(bounds.width(),bounds.height()));
        Bitmap out=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(bounds.width()*exportScale)),
                Math.max(1,(int)Math.ceil(bounds.height()*exportScale)),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);c.save();c.scale(exportScale,exportScale);c.translate(-bounds.left,-bounds.top);drawPhoto(c);c.restore();
        Bitmap maskBitmap=Bitmap.createBitmap(out.getWidth(),out.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas maskCanvas=new Canvas(maskBitmap);maskCanvas.scale(exportScale,exportScale);maskCanvas.translate(-bounds.left,-bounds.top);
        Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);mask.setColor(Color.WHITE);maskCanvas.drawPath(face,mask);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));c.drawBitmap(maskBitmap,0,0,mask);
        mask.setXfermode(null);maskBitmap.recycle();
        c.save();c.scale(exportScale,exportScale);c.translate(-bounds.left,-bounds.top);
        for(Layer l:layers)drawLayer(c,l,1);c.restore();
        return out;
    }
    private RectF contentBounds(){
        float w=photo.getWidth(),h=photo.getHeight();
        double a=Math.toRadians(photoAngle),co=Math.cos(a),si=Math.sin(a);
        float minX=0,minY=0,maxX=w,maxY=h;
        for(int i=0;i<4;i++){
            float x=(i%2==0?-w/2:w/2)*photoScale,y=(i<2?-h/2:h/2)*photoScale;
            float tx=w/2+(float)(x*co-y*si),ty=h/2+(float)(x*si+y*co);
            minX=Math.min(minX,tx);maxX=Math.max(maxX,tx);minY=Math.min(minY,ty);maxY=Math.max(maxY,ty);
        }
        for(Layer l:layers){float r=(float)Math.hypot(l.displayWidth(),l.height())/2;
            minX=Math.min(minX,l.x-r);maxX=Math.max(maxX,l.x+r);
            minY=Math.min(minY,l.y-r);maxY=Math.max(maxY,l.y+r);
        }
        return new RectF(minX,minY,maxX,maxY);
    }

    private void drawPhoto(Canvas c){
        c.save();c.translate(photo.getWidth()/2f,photo.getHeight()/2f);
        c.rotate(photoAngle);c.scale(photoScale,photoScale);
        p.setColor(Color.WHITE);p.setAlpha(255);p.setStyle(Paint.Style.FILL);
        c.drawBitmap(photo,-photo.getWidth()/2f,-photo.getHeight()/2f,p);c.restore();
    }

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);c.drawColor(Color.rgb(31,42,42));if(photo==null)return;
        updateViewport();
        c.save();c.clipRect(0,0,getWidth(),getHeight());
        c.save();c.translate(photoRect.left,photoRect.top);c.scale(scale,scale);
        if(mode==EDIT){drawPhoto(c);drawFaceOutline(c);}
        else{p.setColor(Color.WHITE);p.setAlpha(255);p.setStyle(Paint.Style.FILL);c.drawBitmap(photo,0,0,p);}
        if(mode==CROP)drawCrop(c);
        if(mode==EDIT){
            for(Layer l:layers)drawLayer(c,l,1);
            drawGrid(c);
            if(target==TARGET_PHOTO)drawPhotoSelection(c);
            if(target==TARGET_GRID)drawGridSelection(c);
            if(hasSelection())drawSelection(c,layers.get(selected));
        }
        if(mode==CALIBRATE)drawCalibration(c);
        c.restore();
        c.restore();
        if(Math.abs(zoom-1f)>.01f){
            p.setStyle(Paint.Style.FILL);p.setColor(0xC819302D);
            RectF badge=new RectF(getWidth()-dp(74),dp(12),getWidth()-dp(12),dp(43));c.drawRoundRect(badge,dp(14),dp(14),p);
            p.setColor(Color.WHITE);p.setTextSize(dp(12));p.setTextAlign(Paint.Align.CENTER);
            c.drawText(Math.round(zoom*100)+"%",badge.centerX(),badge.centerY()+dp(4),p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private void drawFaceOutline(Canvas c){
        if(!outlineVisible||faceOutline.size()<3)return;
        c.save();c.translate(photoWidth()/2f,photoHeight()/2f);c.rotate(photoAngle);c.scale(photoScale,photoScale);c.translate(-photoWidth()/2f,-photoHeight()/2f);
        Path path=outlinePath();
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4/(scale*photoScale));p.setColor(0xC51B3733);c.drawPath(path,p);
        p.setStrokeWidth(1.8f/(scale*photoScale));p.setColor(0xFF78E5CF);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);c.restore();
    }
    private Path outlinePath(){
        Path path=new Path();int n=faceOutline.size();if(n<3)return path;
        float signedArea=0;for(int i=0;i<n;i++){PointF a=faceOutline.get(i),b=faceOutline.get((i+1)%n);signedArea+=a.x*b.y-b.x*a.y;}
        float sign=signedArea>=0?1f:-1f;
        List<PointF> adjusted=new ArrayList<>(n);
        for(int i=0;i<n;i++){
            PointF prev=faceOutline.get((i+n-1)%n),a=faceOutline.get(i),next=faceOutline.get((i+1)%n);
            float dx1=a.x-prev.x,dy1=a.y-prev.y,dx2=next.x-a.x,dy2=next.y-a.y;
            float len1=Math.max(.001f,(float)Math.hypot(dx1,dy1)),len2=Math.max(.001f,(float)Math.hypot(dx2,dy2));
            float nx1=sign*dy1/len1,ny1=-sign*dx1/len1,nx2=sign*dy2/len2,ny2=-sign*dx2/len2;
            float nx=nx1+nx2,ny=ny1+ny2,len=Math.max(.001f,(float)Math.hypot(nx,ny));nx/=len;ny/=len;
            float miter=clamp(1f/Math.max(.5f,nx*nx2+ny*ny2),1f,2f);
            adjusted.add(new PointF(a.x+nx*outlineOffset*miter,a.y+ny*outlineOffset*miter));
        }
        path.moveTo(adjusted.get(0).x,adjusted.get(0).y);
        for(int i=0;i<n;i++){
            PointF before=adjusted.get((i+n-1)%n),a=adjusted.get(i),b=adjusted.get((i+1)%n),after=adjusted.get((i+2)%n);
            path.cubicTo(a.x+(b.x-before.x)/6f,a.y+(b.y-before.y)/6f,
                b.x-(after.x-a.x)/6f,b.y-(after.y-a.y)/6f,b.x,b.y);
        }path.close();
        return path;
    }
    private void updateViewport(){
        if(photo==null||getWidth()==0||getHeight()==0)return;
        if(pendingViewport){panX=restorePanX*getWidth();panY=restorePanY*getHeight();pendingViewport=false;}
        fitScale=Math.min(getWidth()/(float)photo.getWidth(),getHeight()/(float)photo.getHeight());
        scale=fitScale*zoom;
        RectF bounds=mode==EDIT?contentBounds():new RectF(0,0,photo.getWidth(),photo.getHeight());
        float baseLeft=(getWidth()-photo.getWidth()*scale)/2f,baseTop=(getHeight()-photo.getHeight()*scale)/2f;
        float left=baseLeft+panX,top=baseTop+panY;
        float sceneW=bounds.width()*scale,sceneH=bounds.height()*scale;
        if(sceneW<=getWidth()){left=(getWidth()-sceneW)/2f-bounds.left*scale;panX=left-baseLeft;}
        else{left=clamp(left,getWidth()-bounds.right*scale,-bounds.left*scale);panX=left-baseLeft;}
        if(sceneH<=getHeight()){top=(getHeight()-sceneH)/2f-bounds.top*scale;panY=top-baseTop;}
        else{top=clamp(top,getHeight()-bounds.bottom*scale,-bounds.top*scale);panY=top-baseTop;}
        photoRect.set(left,top,left+photo.getWidth()*scale,top+photo.getHeight()*scale);
    }
    private void zoomAround(float value,float imageX,float imageY,float screenX,float screenY){
        zoom=Math.max(.25f,Math.min(8f,value));
        float newScale=fitScale*zoom,dw=photo.getWidth()*newScale,dh=photo.getHeight()*newScale;
        panX=screenX-imageX*newScale-(getWidth()-dw)/2f;
        panY=screenY-imageY*newScale-(getHeight()-dh)/2f;
        updateViewport();invalidate();
    }
    private void drawCrop(Canvas c){
        p.setStyle(Paint.Style.FILL);p.setColor(0x990E2524);
        c.drawRect(0,0,photo.getWidth(),crop.top,p);c.drawRect(0,crop.bottom,photo.getWidth(),photo.getHeight(),p);
        c.drawRect(0,crop.top,crop.left,crop.bottom,p);c.drawRect(crop.right,crop.top,photo.getWidth(),crop.bottom,p);
        p.setColor(0xFF67D5C6);p.setStrokeWidth(2/scale);p.setStyle(Paint.Style.STROKE);c.drawRoundRect(crop,6/scale,6/scale,p);
        p.setStyle(Paint.Style.FILL);for(float x:new float[]{crop.left,crop.right})for(float y:new float[]{crop.top,crop.bottom})c.drawCircle(x,y,6/scale,p);
    }
    private void drawGrid(Canvas c){
        if(grid<2)return;
        float size=Math.min(gridSize,Math.min(photo.getWidth(),photo.getHeight()));
        float x0=Math.max(0,Math.min(photo.getWidth()-size,gridX-size/2));
        float y0=Math.max(0,Math.min(photo.getHeight()-size,gridY-size/2));
        p.setColor(0x96FFFFFF);p.setStrokeWidth(1/scale);p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(new RectF(x0,y0,x0+size,y0+size),5/scale,5/scale,p);
        for(int i=1;i<grid;i++){
            float x=x0+size*i/(float)grid,y=y0+size*i/(float)grid;
            c.drawLine(x,y0,x,y0+size,p);c.drawLine(x0,y,x0+size,y,p);
        }
        p.setColor(0xD3E8A981);p.setStrokeWidth(1.6f/scale);
        c.drawLine(x0+size/2,y0,x0+size/2,y0+size,p);
        p.setStyle(Paint.Style.FILL);p.setColor(0xFFF4D09B);
        c.drawCircle(x0+size/2,y0+size/2,7/scale,p);
        p.setColor(0xFF6B5846);c.drawCircle(x0+size/2,y0+size/2,2/scale,p);
    }
    private void drawGridSelection(Canvas c){
        if(grid<2)return;
        PointF center=gridCenter();float size=Math.min(gridSize,Math.min(photo.getWidth(),photo.getHeight()));
        p.setColor(gridLocked?0xFFF4D09B:0xFF67D5C6);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f/scale);
        c.drawRect(center.x-size/2,center.y-size/2,center.x+size/2,center.y+size/2,p);
        p.setStyle(Paint.Style.FILL);
    }
    private void drawPhotoSelection(Canvas c){
        c.save();c.translate(photo.getWidth()/2f,photo.getHeight()/2f);
        c.rotate(photoAngle);c.scale(photoScale,photoScale);
        p.setColor(0xFF67D5C6);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f/(scale*photoScale));
        c.drawRect(-photo.getWidth()/2f,-photo.getHeight()/2f,photo.getWidth()/2f,photo.getHeight()/2f,p);
        p.setStyle(Paint.Style.FILL);c.restore();
    }
    private void drawLayer(Canvas c,Layer l,float factor){
        c.save();c.translate(l.x*factor,l.y*factor);c.rotate(l.angle);
        p.setColor(Color.WHITE);p.setAlpha(Math.round(l.opacity*255));p.setStyle(Paint.Style.FILL);
        float w=l.width*factor,h=l.height()*factor;
        if(!l.eyeSpacingEnabled||Math.abs(l.eyeSpacing)<.01f){
            c.drawBitmap(l.bitmap,null,new RectF(-w/2,-h/2,w/2,h/2),p);
        }else{
            int sourceWidth=l.bitmap.getWidth(),sourceHeight=l.bitmap.getHeight();
            int split=Math.max(1,Math.min(sourceWidth-1,Math.round(sourceWidth*l.eyeAxis)));
            float cut=-w/2+w*split/sourceWidth,halfOffset=w*l.eyeSpacing/200f;
            c.drawBitmap(l.bitmap,new Rect(0,0,split,sourceHeight),new RectF(-w/2-halfOffset,-h/2,cut-halfOffset,h/2),p);
            c.drawBitmap(l.bitmap,new Rect(split,0,sourceWidth,sourceHeight),new RectF(cut+halfOffset,-h/2,w/2+halfOffset,h/2),p);
        }
        c.restore();
    }
    private void drawSelection(Canvas c,Layer l){
        c.save();c.translate(l.x,l.y);c.rotate(l.angle);
        p.setColor(l.locked?0xFFF4D09B:0xFF67D5C6);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f/scale);
        float visibleWidth=l.displayWidth();
        c.drawRoundRect(new RectF(-visibleWidth/2,-l.height()/2,visibleWidth/2,l.height()/2),5/scale,5/scale,p);
        if(l.eyeSpacingEnabled){
            float axis=(l.eyeAxis-.5f)*l.width;
            p.setColor(0xFFD89131);p.setStrokeWidth(2/scale);
            c.drawLine(axis,-l.height()/2,axis,l.height()/2,p);
            p.setStyle(Paint.Style.FILL);c.drawCircle(axis,-l.height()/2,4/scale,p);
            c.drawCircle(axis,l.height()/2,4/scale,p);
            p.setColor(l.locked?0xFFF4D09B:0xFF67D5C6);p.setStyle(Paint.Style.STROKE);
        }
        float gap=25/scale,rx=visibleWidth/2+gap,ry=-l.height()/2-gap;
        if(l.locked){
            p.setStyle(Paint.Style.FILL);p.setColor(0xFF183F3B);
            c.drawCircle(rx,ry,15/scale,p);
            p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(15/scale);
            c.drawText("锁",rx,ry+5/scale,p);
            p.setTextAlign(Paint.Align.LEFT);c.restore();return;
        }
        c.drawLine(visibleWidth/2,-l.height()/2,rx,ry,p);
        p.setStyle(Paint.Style.FILL);p.setColor(0xFF183F3B);
        c.drawCircle(rx,ry,15/scale,p);c.drawCircle(visibleWidth/2,l.height()/2,15/scale,p);
        p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(19/scale);
        c.drawText("↻",rx,ry+6/scale,p);p.setTextSize(16/scale);
        c.drawText("↗",visibleWidth/2,l.height()/2+5/scale,p);
        p.setTextAlign(Paint.Align.LEFT);c.restore();
    }
    private void drawCalibration(Canvas c){
        if(firstPoint!=null){p.setColor(0xFF67D5C6);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2/scale);c.drawCircle(firstPoint.x,firstPoint.y,13/scale,p);p.setStyle(Paint.Style.FILL);}
    }
    private final RectF savedCrop=new RectF();
    private int cropCorner;
    private PointF imagePoint(float x,float y){return new PointF((x-photoRect.left)/scale,(y-photoRect.top)/scale);}
    private float clamp(float value,float low,float high){return Math.max(low,Math.min(high,value));}
    private PointF layerPoint(Layer l,float x,float y){
        double a=Math.toRadians(l.angle);
        return new PointF(l.x+(float)(x*Math.cos(a)-y*Math.sin(a)),l.y+(float)(x*Math.sin(a)+y*Math.cos(a)));
    }
    private PointF localPoint(Layer l,PointF q){
        float x=q.x-l.x,y=q.y-l.y;double a=Math.toRadians(-l.angle);
        return new PointF((float)(x*Math.cos(a)-y*Math.sin(a)),(float)(x*Math.sin(a)+y*Math.cos(a)));
    }
    private PointF rotateHandle(Layer l){return layerPoint(l,l.displayWidth()/2+25/scale,-l.height()/2-25/scale);}
    private PointF resizeHandle(Layer l){return layerPoint(l,l.displayWidth()/2,l.height()/2);}
    private boolean near(PointF a,PointF b,float radius){return Math.hypot(a.x-b.x,a.y-b.y)<=radius/scale;}
    private PointF gridCenter(){
        float size=Math.min(gridSize,Math.min(photo.getWidth(),photo.getHeight()));
        return new PointF(clamp(gridX-size/2,0,photo.getWidth()-size)+size/2,
                clamp(gridY-size/2,0,photo.getHeight()-size)+size/2);
    }
    private boolean hitInk(Layer l,PointF q){
        PointF local=localPoint(l,q);float radius=dp(9)/scale;
        if(Math.abs(local.x)>l.width/2+radius||Math.abs(local.y)>l.height()/2+radius)return false;
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
            float x=local.x+dx*radius,y=local.y+dy*radius;
            int bx=(int)((x/l.width+.5f)*l.bitmap.getWidth());
            int by=(int)((y/l.height()+.5f)*l.bitmap.getHeight());
            if(bx>=0&&bx<l.bitmap.getWidth()&&by>=0&&by<l.bitmap.getHeight()
                    &&(l.bitmap.getPixel(bx,by)>>>24)>35)return true;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(photo==null)return false;
        updateViewport();int action=e.getActionMasked();
        if(mode==EDIT&&facePick!=null){
            if(action==MotionEvent.ACTION_UP){PointF q=imagePoint(e.getX(),e.getY());
                double a=Math.toRadians(-photoAngle);float dx=(q.x-photoWidth()/2f)/photoScale,dy=(q.y-photoHeight()/2f)/photoScale;
                PointF seed=new PointF(photoWidth()/2f+(float)(dx*Math.cos(a)-dy*Math.sin(a)),photoHeight()/2f+(float)(dx*Math.sin(a)+dy*Math.cos(a)));
                Consumer<PointF> listener=facePick;facePick=null;listener.accept(seed);}
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN){
            suppressTap=false;
            if(mode==CROP)return cropDown(e);
            if(mode==EDIT){gestureStart=captureHistory();return editDown(e);}
            gesture=NONE;return true;
        }
        if(action==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()>=2){startPinch(e);return true;}
        if(action==MotionEvent.ACTION_MOVE){
            if(gesture==CANVAS_PINCH){
                float mx=(e.getX(0)+e.getX(1))/2,my=(e.getY(0)+e.getY(1))/2;
                zoomAround(pinchZoom*distance(e)/initialDistance,pinchImageX,pinchImageY,mx,my);return true;
            }
            if(gesture==STICKER_PINCH&&hasSelection()){
                Layer l=layers.get(selected);if(l.locked)return true;
                PointF mid=imagePoint((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);
                l.width=clamp(initialWidth*distance(e)/initialDistance,20,photo.getWidth()*1.5f);
                l.angle=AngleKnob.quantize(initialRotation+normalizedAngle(angle(e)-initialAngle));
                l.x=clamp(initialLayerX+mid.x-pinchImageX,0,photo.getWidth());
                l.y=clamp(initialLayerY+mid.y-pinchImageY,0,photo.getHeight());transformed();invalidate();return true;
            }
            if(gesture==PHOTO_PINCH){
                photoScale=clamp(pinchPhotoScale*distance(e)/initialDistance,.25f,2f);
                photoAngle=AngleKnob.quantize(pinchPhotoAngle+normalizedAngle(angle(e)-initialAngle));
                transformed();invalidate();return true;
            }
            if(gesture==GRID_PINCH){
                PointF mid=imagePoint((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);
                gridSize=clamp(pinchGridSize*distance(e)/initialDistance,photo.getWidth()*.08f,Math.min(photo.getWidth(),photo.getHeight()));
                gridX=clamp(initialLayerX+mid.x-pinchImageX,0,photo.getWidth());
                gridY=clamp(initialLayerY+mid.y-pinchImageY,0,photo.getHeight());
                transformed();invalidate();return true;
            }
            if(mode==CROP)return cropMove(e);
            if(mode==EDIT)return editMove(e);
            return true;
        }
        if(action==MotionEvent.ACTION_POINTER_UP){
            if(gesture==CANVAS_PINCH||gesture==STICKER_PINCH||gesture==PHOTO_PINCH||gesture==GRID_PINCH){
                int remaining=e.getActionIndex()==0?1:0;
                lastScreenX=e.getX(remaining);lastScreenY=e.getY(remaining);
                PointF q=imagePoint(lastScreenX,lastScreenY);lastX=q.x;lastY=q.y;
                gesture=gesture==STICKER_PINCH?STICKER_MOVE:gesture==PHOTO_PINCH?NONE:gesture==GRID_PINCH?GRID_MOVE:CANVAS_PAN;
                suppressTap=true;
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP){
            if(mode==CROP&&gesture==CROP_CREATE&&(crop.width()<24||crop.height()<16))crop.set(savedCrop);
            if(mode==CALIBRATE&&!suppressTap&&photoRect.contains(e.getX(),e.getY())){
                PointF q=imagePoint(e.getX(),e.getY());
                if(firstPoint==null)firstPoint=q;
                else{PointF a=firstPoint;firstPoint=null;if(calibrationListener!=null)calibrationListener.onPoints(a,q);}
            }
            gesture=NONE;if(mode==EDIT){recordHistory(gestureStart);gestureStart=null;selectionChanged();}invalidate();return true;
        }
        if(action==MotionEvent.ACTION_CANCEL){if(mode==CROP&&gesture==CROP_CREATE)crop.set(savedCrop);
            if(mode==EDIT){recordHistory(gestureStart);gestureStart=null;}gesture=NONE;invalidate();return true;}
        return true;
    }
    private boolean cropDown(MotionEvent e){
        PointF q=imagePoint(e.getX(),e.getY());lastX=q.x;lastY=q.y;
        lastScreenX=e.getX();lastScreenY=e.getY();
        float[][] corners={{crop.left,crop.top},{crop.right,crop.top},{crop.right,crop.bottom},{crop.left,crop.bottom}};
        for(int i=0;i<4;i++)if(near(q,new PointF(corners[i][0],corners[i][1]),dp(25))){cropCorner=i;gesture=CROP_RESIZE;return true;}
        if(crop.contains(q.x,q.y)){gesture=CROP_MOVE;return true;}
        if(!photoRect.contains(e.getX(),e.getY())){gesture=CANVAS_PAN;return true;}
        savedCrop.set(crop);downX=clamp(q.x,0,photo.getWidth());downY=clamp(q.y,0,photo.getHeight());
        crop.set(downX,downY,downX+1,downY+1);gesture=CROP_CREATE;invalidate();return true;
    }
    private boolean cropMove(MotionEvent e){
        PointF q=imagePoint(e.getX(),e.getY());
        if(gesture==CANVAS_PAN){panCanvas(e);return true;}
        if(gesture==CROP_MOVE){
            crop.offset(q.x-lastX,q.y-lastY);
            if(crop.left<0)crop.offset(-crop.left,0);if(crop.top<0)crop.offset(0,-crop.top);
            if(crop.right>photo.getWidth())crop.offset(photo.getWidth()-crop.right,0);
            if(crop.bottom>photo.getHeight())crop.offset(0,photo.getHeight()-crop.bottom);
        }else if(gesture==CROP_RESIZE){
            if(cropCorner==0||cropCorner==3)crop.left=clamp(q.x,0,crop.right-24);
            else crop.right=clamp(q.x,crop.left+24,photo.getWidth());
            if(cropCorner==0||cropCorner==1)crop.top=clamp(q.y,0,crop.bottom-16);
            else crop.bottom=clamp(q.y,crop.top+16,photo.getHeight());
        }else if(gesture==CROP_CREATE){
            crop.set(clamp(Math.min(downX,q.x),0,photo.getWidth()),clamp(Math.min(downY,q.y),0,photo.getHeight()),
                    clamp(Math.max(downX,q.x),0,photo.getWidth()),clamp(Math.max(downY,q.y),0,photo.getHeight()));
        }
        lastX=q.x;lastY=q.y;invalidate();return true;
    }
    private boolean editDown(MotionEvent e){
        PointF q=imagePoint(e.getX(),e.getY());lastX=q.x;lastY=q.y;
        lastScreenX=e.getX();lastScreenY=e.getY();
        if(target==TARGET_VIEW){gesture=CANVAS_PAN;return true;}
        if(target==TARGET_PHOTO){gesture=NONE;return true;}
        if(target==TARGET_GRID){gesture=gridLocked?NONE:GRID_MOVE;return true;}
        if(hasSelection()){
            Layer l=layers.get(selected);
            if(!l.locked){
                if(near(q,rotateHandle(l),dp(26))){gesture=STICKER_ROTATE;lastRotationAngle=around(l,q);initialRotation=l.angle;return true;}
                if(near(q,resizeHandle(l),dp(26))){gesture=STICKER_RESIZE;initialWidth=l.width;resizeDistance=Math.max(1,(float)Math.hypot(q.x-l.x,q.y-l.y));return true;}
            }
            gesture=l.locked?NONE:STICKER_MOVE;return true;
        }
        gesture=NONE;return true;
    }
    private boolean editMove(MotionEvent e){
        PointF q=imagePoint(e.getX(),e.getY());
        if(gesture==CANVAS_PAN){panCanvas(e);return true;}
        if(gesture==GRID_MOVE&&!gridLocked){gridX=clamp(gridX+q.x-lastX,0,photo.getWidth());gridY=clamp(gridY+q.y-lastY,0,photo.getHeight());}
        else if(hasSelection()){
            Layer l=layers.get(selected);
            if(!l.locked){
                if(gesture==STICKER_MOVE){l.x=clamp(l.x+q.x-lastX,0,photo.getWidth());l.y=clamp(l.y+q.y-lastY,0,photo.getHeight());}
                else if(gesture==STICKER_ROTATE){float a=around(l,q);initialRotation+=normalizedAngle(a-lastRotationAngle);l.angle=AngleKnob.quantize(initialRotation);lastRotationAngle=a;}
                else if(gesture==STICKER_RESIZE){float d=(float)Math.hypot(q.x-l.x,q.y-l.y);l.width=clamp(initialWidth*d/resizeDistance,20,photo.getWidth()*1.5f);}
            }
        }
        lastX=q.x;lastY=q.y;transformed();invalidate();return true;
    }
    private void panCanvas(MotionEvent e){
        panX+=e.getX()-lastScreenX;panY+=e.getY()-lastScreenY;
        lastScreenX=e.getX();lastScreenY=e.getY();updateViewport();invalidate();
    }
    private void startPinch(MotionEvent e){
        initialDistance=Math.max(1,distance(e));
        float mx=(e.getX(0)+e.getX(1))/2,my=(e.getY(0)+e.getY(1))/2;
        PointF mid=imagePoint(mx,my);pinchImageX=mid.x;pinchImageY=mid.y;
        if(mode==EDIT&&hasSelection()&&!isSelectedLocked()){
            Layer l=layers.get(selected);gesture=STICKER_PINCH;initialWidth=l.width;
            initialAngle=angle(e);initialRotation=l.angle;initialLayerX=l.x;initialLayerY=l.y;
        }else if(mode==EDIT&&target==TARGET_PHOTO){
            gesture=PHOTO_PINCH;initialAngle=angle(e);pinchPhotoScale=photoScale;
            pinchPhotoAngle=photoAngle;
        }else if(mode==EDIT&&target==TARGET_GRID&&!gridLocked){
            gesture=GRID_PINCH;pinchGridSize=gridSize;initialLayerX=gridX;initialLayerY=gridY;
        }else{gesture=CANVAS_PINCH;pinchZoom=zoom;pinchMidX=mx;pinchMidY=my;}
        suppressTap=true;
    }
    private float around(Layer l,PointF q){return (float)Math.toDegrees(Math.atan2(q.y-l.y,q.x-l.x));}
    private float normalizedAngle(float value){while(value>180)value-=360;while(value< -180)value+=360;return value;}
    private float distance(MotionEvent e){return (float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));}
    private float angle(MotionEvent e){return (float)Math.toDegrees(Math.atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0)));}
}
