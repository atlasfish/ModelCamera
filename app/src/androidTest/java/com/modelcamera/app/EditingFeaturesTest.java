package com.modelcamera.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Verifies restored pixel ownership, single-axis editing, the slow dial, and real sample segmentation. */
public final class EditingFeaturesTest extends InstrumentationTestCase {
    private Context context;private File storage;
    @Override protected void setUp()throws Exception{
        super.setUp();Context target=getInstrumentation().getTargetContext();
        storage=new File(target.getCacheDir(),"editing-test-"+UUID.randomUUID());assertTrue(storage.mkdirs());
        context=new ContextWrapper(target){@Override public File getFilesDir(){return storage;}};
    }
    private PhotoBoard board(Bitmap photo){
        PhotoBoard b=new PhotoBoard(context,photo,PhotoBoard.EDIT);
        b.measure(View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY));
        b.layout(0,0,500,500);return b;
    }
    public void testSavedSessionRestoresEntireSceneEvenAfterLibraryDeletion()throws Exception{
        StickerRepo repo=new StickerRepo(context);SessionRepo sessions=new SessionRepo(context);
        Bitmap photo=Bitmap.createBitmap(1000,800,Bitmap.Config.ARGB_8888);photo.eraseColor(0xFFFFEEDD);
        Bitmap sticker=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888);sticker.setPixel(30,20,0xFF123456);
        StickerRepo.Sticker s=repo.save(sticker,"眼睛",10,6,"same-photo",100);
        PhotoBoard b=board(photo);b.addSticker(sticker,s,10,"same-photo");b.setSelectedCoordinate(true,300.5f);b.setSelectedCoordinate(false,450.5f);
        b.setSelectedAngle(12.5f);b.scaleSelected(1.4f);b.toggleSelectedLocked();
        b.selectGrid();b.setSelectedCoordinate(true,450);b.setSelectedCoordinate(false,350);b.setGrid(6);b.toggleGridLocked();
        b.selectPhoto();b.setSelectedAngle(-4.5f);b.scaleSelected(1.1f);b.zoomViewport(1.7f);
        b.setOutline(Arrays.asList(new PointF(100,100),new PointF(200,100),new PointF(200,200)));b.setOutlineOffset(4);b.toggleOutline();b.selectSticker(0);
        JSONObject expected=b.snapshot();SessionRepo.Capture capture=sessions.capture(b,"same-photo",new Calibration.Result(10,8,true));
        b.selectPhoto();b.setSelectedAngle(90);photo.eraseColor(0xFF000000);sticker.eraseColor(0xFFFF0000);
        String id;try{id=sessions.save(null,"工作现场",capture);}finally{capture.release();}
        repo.deleteSticker(s.id);photo.recycle();sticker.recycle();
        SessionRepo.Loaded loaded=new SessionRepo(context).load(id);PhotoBoard restored=board(loaded.photo);
        restored.restore(loaded.document.getJSONObject("board"),loaded.stickers);
        // draw once applies the restored viewport using the current view dimensions.
        Bitmap screen=Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888);restored.draw(new Canvas(screen));screen.recycle();
        assertEquals(expected.toString(),restored.snapshot().toString());
        assertEquals(0xFFFFEEDD,loaded.photo.getPixel(0,0));assertEquals(0,loaded.stickers.get(0).getPixel(0,0));
        assertEquals(0xFF123456,loaded.stickers.get(0).getPixel(30,20));assertEquals(10f,(float)loaded.document.getJSONObject("calibration").getDouble("ppm"),.001f);
        assertTrue(restored.isSelectedLocked());assertTrue(restored.isGridLocked());assertTrue(restored.hasOutline());assertFalse(restored.outlineVisible());
        assertEquals(id,sessions.save(id,"更新现场",restored,"same-photo",null));assertEquals(1,sessions.list().size());assertEquals("更新现场",sessions.list().get(0).name);
        sessions.delete(id);assertTrue(sessions.list().isEmpty());loaded.photo.recycle();for(Bitmap image:loaded.stickers)image.recycle();
    }
    public void testCoordinateEditsPreserveOtherAxisAndRespectLocks()throws Exception{
        Bitmap photo=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888),sticker=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888);
        PhotoBoard b=board(photo);b.addSticker(sticker,new StickerRepo.Sticker("id","eye",0,0,"same",100),0,"same");
        float y=b.selectedY();b.setSelectedCoordinate(true,251.5f);assertEquals(y,b.selectedY(),.001f);
        b.setSelectedCoordinate(false,398.5f);assertEquals(251.5f,b.selectedX(),.001f);
        b.toggleSelectedLocked();b.setSelectedCoordinate(true,600);b.setSelectedCoordinate(false,600);
        assertEquals(251.5f,b.selectedX(),.001f);assertEquals(398.5f,b.selectedY(),.001f);
        b.selectGrid();float gridY=b.selectedY();b.setSelectedCoordinate(true,650);assertEquals(gridY,b.selectedY(),.001f);
        b.setSelectedCoordinate(false,300);assertEquals(650f,b.selectedX(),.001f);
        b.toggleGridLocked();b.setSelectedCoordinate(true,400);b.setSelectedCoordinate(false,400);
        assertEquals(650f,b.selectedX(),.001f);assertEquals(300f,b.selectedY(),.001f);photo.recycle();sticker.recycle();
    }
    private void touch(View view,int action,float y){long time=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(time,time,action,40,y,0);view.onTouchEvent(e);e.recycle();}
    public void testAngleDialIgnoresSmallDragAndStepsHalfDegree()throws Exception{
        AngleKnob knob=new AngleKnob(context);View dial=knob.getChildAt(1);float density=context.getResources().getDisplayMetrics().density;
        knob.setValue(10);touch(dial,MotionEvent.ACTION_DOWN,50*density);touch(dial,MotionEvent.ACTION_MOVE,70*density);assertEquals(10f,knob.value(),.001f);
        touch(dial,MotionEvent.ACTION_MOVE,75*density);assertEquals(10.5f,knob.value(),.001f);
        touch(dial,MotionEvent.ACTION_MOVE,99*density);assertEquals(11f,knob.value(),.001f);
        touch(dial,MotionEvent.ACTION_UP,99*density);knob.getChildAt(0).performClick();assertEquals(10.5f,knob.value(),.001f);
        knob.getChildAt(2).performClick();assertEquals(11f,knob.value(),.001f);
        knob.setEnabled(false);knob.getChildAt(2).performClick();assertEquals(11f,knob.value(),.001f);
    }
    public void testCameraPreviewCenterCropPreservesPixelAspect(){
        // The 1280x960 stream is upright as 960x1280; the portrait view is much taller.
        int viewW=1080,viewH=2274,imageW=960,imageH=1280;
        Matrix transform=CameraActivity.centerCropTransform(viewW,viewH,imageW,imageH);
        float[] axes={viewW/(float)imageW,0,0,viewH/(float)imageH};
        transform.mapVectors(axes);
        assertEquals("Horizontal and vertical camera pixels must have equal display size",axes[0],axes[3],.002f);
        float[] center={viewW/2f,viewH/2f};transform.mapPoints(center);
        assertEquals(viewW/2f,center[0],.001f);assertEquals(viewH/2f,center[1],.001f);
    }
    public void testCameraZoomCropAndSoftwareFallback()throws Exception{
        Rect sensor=new Rect(100,50,4100,3050);
        assertEquals(sensor,CameraActivity.zoomCrop(sensor,1f));
        assertEquals(new Rect(1100,800,3100,2300),CameraActivity.zoomCrop(sensor,2f));
        assertEquals(new Rect(1600,1175,2600,1925),CameraActivity.zoomCrop(sensor,4f));
        Bitmap sample=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);
        sample.eraseColor(0xFF00AA00);
        for(int y=0;y<300;y++)for(int x=0;x<100;x++)sample.setPixel(x,y,0xFFFF0000);
        File jpeg=new File(storage,"software_zoom.jpg");try(FileOutputStream out=new FileOutputStream(jpeg)){assertTrue(sample.compress(Bitmap.CompressFormat.JPEG,100,out));}
        CameraActivity.applySoftwareZoom(jpeg,4f);Bitmap cropped=BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        assertEquals(100,cropped.getWidth());assertEquals(75,cropped.getHeight());
        assertTrue("Center crop must retain the center of the image",android.graphics.Color.green(cropped.getPixel(50,37))>100);
        assertTrue("Left edge must not leak into 4x crop",android.graphics.Color.red(cropped.getPixel(1,37))<100);
        sample.recycle();cropped.recycle();
    }
    public void testQuarterTurnRotatesPixelsAndCropWithoutScaling(){
        Bitmap photo=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);
        photo.setPixel(0,0,0xFFFF0000);photo.setPixel(2,1,0xFF0000FF);
        android.graphics.Rect box=new android.graphics.Rect(0,0,2,1);
        Bitmap clockwise=ImageTools.rotateQuarterTurn(photo,true);
        assertEquals(2,clockwise.getWidth());assertEquals(3,clockwise.getHeight());
        assertEquals(0xFFFF0000,clockwise.getPixel(1,0));assertEquals(0xFF0000FF,clockwise.getPixel(0,2));
        assertEquals(new android.graphics.Rect(1,0,2,2),ImageTools.rotateQuarterTurn(box,3,2,true));
        Bitmap restored=ImageTools.rotateQuarterTurn(clockwise,false);
        assertEquals(photo.getWidth(),restored.getWidth());assertEquals(photo.getHeight(),restored.getHeight());
        assertEquals(0xFFFF0000,restored.getPixel(0,0));assertEquals(0xFF0000FF,restored.getPixel(2,1));
        photo.recycle();clockwise.recycle();restored.recycle();
    }
    public void testAdjustedOutlineDrivesTransparentExportAndKeepsOutsideSticker()throws Exception{
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);photo.eraseColor(0xFFFFCCBB);
        Bitmap sticker=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);sticker.eraseColor(0xFFFF0000);
        PhotoBoard b=board(photo);b.setOutline(Arrays.asList(new PointF(20,20),new PointF(70,20),new PointF(70,70),new PointF(20,70)));
        b.addSticker(sticker,new StickerRepo.Sticker("test","eye",0,0,"same",10),0,"same");
        b.setSelectedCoordinate(true,90);b.setSelectedCoordinate(false,80);
        Bitmap initial=b.renderSculptCutout();
        int skin=0,transparent=0,red=0;
        for(int y=0;y<initial.getHeight();y++)for(int x=0;x<initial.getWidth();x++){
            int pixel=initial.getPixel(x,y);if(pixel==0xFFFFCCBB)skin++;if((pixel>>>24)==0)transparent++;if(pixel==0xFFFF0000)red++;
        }
        assertTrue("Background outside the sculpt must be transparent",transparent>100);
        assertTrue("Face body must remain",skin>1800);
        assertTrue("Sticker outside face outline must remain in the union",red>70);
        int originalSkin=skin;b.setOutlineOffset(4);
        assertEquals(4f,(float)b.snapshot().getDouble("outlineOffset"),.001f);
        Bitmap expanded=b.renderSculptCutout();skin=0;for(int y=0;y<expanded.getHeight();y++)for(int x=0;x<expanded.getWidth();x++)if(expanded.getPixel(x,y)==0xFFFFCCBB)skin++;
        assertTrue("Expanding the outline must include more face photo",skin>originalSkin);
        initial.recycle();expanded.recycle();photo.recycle();sticker.recycle();
    }
    public void testTransparentExportFollowsPhotoRotation()throws Exception{
        Bitmap photo=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
        for(int y=0;y<100;y++)for(int x=0;x<100;x++)photo.setPixel(x,y,x<50?0xFFFFCCBB:0xFF335577);
        PhotoBoard b=board(photo);b.setOutline(Arrays.asList(new PointF(12,18),new PointF(38,18),new PointF(38,75),new PointF(12,75)));
        b.selectPhoto();b.setSelectedAngle(90);
        Bitmap out=b.renderSculptCutout();int skin=0,wrong=0;
        for(int y=0;y<out.getHeight();y++)for(int x=0;x<out.getWidth();x++){
            int pixel=out.getPixel(x,y);if(pixel==0xFFFFCCBB)skin++;if(pixel==0xFF335577)wrong++;
        }
        assertTrue("Rotated sculpt area must remain visible",skin>800);
        assertEquals("Mask and rotated photo must share the same transform",0,wrong);
        out.recycle();photo.recycle();
    }
    public void testReeditOverwritePreservesIdentityFolderAndTransparentPixels()throws Exception{
        StickerRepo repo=new StickerRepo(context);StickerRepo.Folder folder=repo.createFolder("常用");
        Bitmap original=Bitmap.createBitmap(100,60,Bitmap.Config.ARGB_8888);StickerRepo.Sticker sticker=repo.save(original,"眼睛",10,6,"photo",100,folder.id);
        Bitmap edited=Bitmap.createBitmap(140,80,Bitmap.Config.ARGB_8888);edited.setPixel(35,20,0xFF112233);
        repo.update(sticker,edited,14,8,140);StickerRepo.Sticker saved=repo.list().get(0);
        assertEquals(1,repo.list().size());assertEquals(sticker.id,saved.id);assertEquals(folder.id,saved.folderId);assertEquals("photo",saved.sourcePhoto);
        assertEquals("眼睛",saved.name);assertEquals(14f,saved.widthMm,.001f);assertEquals(140f,saved.sourceWidthPx,.001f);
        Bitmap png=BitmapFactory.decodeFile(repo.file(saved.id).getAbsolutePath());assertEquals(0,png.getPixel(0,0));assertEquals(0xFF112233,png.getPixel(35,20));
        original.recycle();edited.recycle();png.recycle();
    }
    public void testSculptBoundaryOnBothUserSamples()throws Exception{
        outlineSample("sample_face.jpg",.5f,.54f,.5f,new RectF(.29f,.36f,.70f,.74f),new PointF(.67f,.53f),new PointF(.71f,.60f));
        outlineSample("sample_sheet.jpg",.26f,.60f,.35f,new RectF(.10f,.47f,.41f,.75f),new PointF(.372f,.56f),new PointF(.406f,.58f));
    }
    public void testGreenMatPhotoTrial()throws Exception{
        Bitmap photo;try(java.io.InputStream in=getInstrumentation().getContext().getAssets().open("sample_green_mat.jpg")){photo=BitmapFactory.decodeStream(in);}
        Calibration.Result calibration=Calibration.detect(photo);
        assertNotNull("The pale 1 cm grid should calibrate automatically",calibration);
        assertEquals("The short 5 mm crosses must not halve the measured pitch",78.7f,calibration.pixelsPerMm,2f);
        assertTrue(calibration.automatic);assertTrue(calibration.dotCount>=12);
        System.out.println("GREEN_MAT calibration="+calibration.pixelsPerMm+" px/mm, grid points="+calibration.dotCount);
        Bitmap screen=Bitmap.createScaledBitmap(photo,photo.getWidth()/2,photo.getHeight()/2,true);
        Calibration.Result screenCalibration=Calibration.detect(screen);
        assertNotNull("The grid should survive the app's 2048-pixel image load",screenCalibration);
        assertEquals(calibration.pixelsPerMm/2,screenCalibration.pixelsPerMm,1f);
        System.out.println("GREEN_MAT app-scale calibration="+screenCalibration.pixelsPerMm+" px/mm");screen.recycle();
        List<PointF> outline=FaceOutline.detect(photo,new PointF(photo.getWidth()*.52f,photo.getHeight()*.48f),photo.getWidth()*.52f);
        RectF found=new RectF(Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE);Path path=new Path();
        for(int i=0;i<outline.size();i++){PointF p=outline.get(i);if(i==0)path.moveTo(p.x,p.y);else path.lineTo(p.x,p.y);
            found.left=Math.min(found.left,p.x);found.right=Math.max(found.right,p.x);found.top=Math.min(found.top,p.y);found.bottom=Math.max(found.bottom,p.y);}
        path.close();Bitmap annotated=photo.copy(Bitmap.Config.ARGB_8888,true);Paint pen=new Paint(3);pen.setColor(0xFF00FFB2);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(photo.getWidth()*.003f);
        new Canvas(annotated).drawPath(path,pen);
        try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"outline-green-mat.png"))){annotated.compress(Bitmap.CompressFormat.PNG,100,out);}
        System.out.println("GREEN_MAT bounds="+found);annotated.recycle();photo.recycle();
    }
    public void testExistingMatSamplesStillCalibrate()throws Exception{
        for(String asset:new String[]{"sample_face.jpg","sample_sheet.jpg"}){
            Bitmap photo;try(java.io.InputStream in=context.getAssets().open(asset)){photo=BitmapFactory.decodeStream(in);}
            Calibration.Result result=Calibration.detect(photo);
            assertNotNull(asset+" dark-dot calibration must still work",result);
            assertTrue(asset+" has an implausible scale: "+result.pixelsPerMm,result.pixelsPerMm>10&&result.pixelsPerMm<100);
            System.out.println("OLD_MAT "+asset+" "+result.pixelsPerMm+" px/mm");photo.recycle();
        }
    }
    private void outlineSample(String asset,float x,float y,float size,RectF expected,PointF shadedSculpt,PointF castShadow)throws Exception{
        Bitmap photo;try(java.io.InputStream in=context.getAssets().open(asset)){photo=BitmapFactory.decodeStream(in);}
        List<PointF> outline=FaceOutline.detect(photo,new PointF(photo.getWidth()*x,photo.getHeight()*y),photo.getWidth()*size);
        assertTrue(asset+" shaded sculpt must remain inside",inside(outline,shadedSculpt.x*photo.getWidth(),shadedSculpt.y*photo.getHeight()));
        assertFalse(asset+" cast shadow must remain outside",inside(outline,castShadow.x*photo.getWidth(),castShadow.y*photo.getHeight()));
        if(asset.equals("sample_face.jpg"))assertFalse("right cast shadow must remain outside",inside(outline,.69f*photo.getWidth(),.59f*photo.getHeight()));
        RectF found=new RectF(Float.MAX_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE);Path path=new Path();
        for(int i=0;i<outline.size();i++){PointF p=outline.get(i);if(i==0)path.moveTo(p.x,p.y);else path.lineTo(p.x,p.y);found.left=Math.min(found.left,p.x);found.right=Math.max(found.right,p.x);found.top=Math.min(found.top,p.y);found.bottom=Math.max(found.bottom,p.y);}path.close();
        Bitmap annotated=photo.copy(Bitmap.Config.ARGB_8888,true);Paint pen=new Paint(3);pen.setColor(0xFF00FFB2);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(photo.getWidth()*.003f);new Canvas(annotated).drawPath(path,pen);
        try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"outline-"+asset+".png"))){annotated.compress(Bitmap.CompressFormat.PNG,100,out);}annotated.recycle();
        found.left/=photo.getWidth();found.right/=photo.getWidth();found.top/=photo.getHeight();found.bottom/=photo.getHeight();
        if(asset.equals("sample_sheet.jpg"))assertTrue("Mat dot above the forehead must be excluded: "+found,found.top>.477f);
        RectF intersection=new RectF(found);assertTrue(intersection.intersect(expected));
        float overlap=intersection.width()*intersection.height()/(expected.width()*expected.height()+found.width()*found.height()-intersection.width()*intersection.height());
        assertTrue(asset+" boundary bounding box differs from the sculpt: "+found+" overlap="+overlap,overlap>.60f);
        assertTrue(outline.size()>8);photo.recycle();
    }
    private boolean inside(List<PointF> polygon,float x,float y){
        boolean inside=false;
        for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++){
            PointF a=polygon.get(i),b=polygon.get(j);
            if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;
        }return inside;
    }
}
