package com.modelcamera.app;
import android.graphics.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.List;

public final class IrregularOutlineTest extends InstrumentationTestCase {
    public void testIrregularSculpt()throws Exception{
        Bitmap photo;try(InputStream in=getInstrumentation().getContext().getAssets().open("sample_neutral_sheet.jpg")){photo=BitmapFactory.decodeStream(in);}
        for(float seedY:new float[]{.54f,.68f}){
            List<PointF> points=FaceOutline.detect(photo,new PointF(720,photo.getHeight()*seedY),720);
            Bitmap preview=photo.copy(Bitmap.Config.ARGB_8888,true);Path path=new Path();
            for(int i=0;i<points.size();i++){PointF p=points.get(i);if(i==0)path.moveTo(p.x,p.y);else path.lineTo(p.x,p.y);}path.close();
            Paint pen=new Paint(3);pen.setColor(0xff00ffad);pen.setStrokeWidth(4);pen.setStyle(Paint.Style.STROKE);new Canvas(preview).drawPath(path,pen);
            try(FileOutputStream out=new FileOutputStream(new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"irregular-"+seedY+".png"))){preview.compress(Bitmap.CompressFormat.PNG,100,out);}preview.recycle();
            assertTrue("Left ear",inside(points,419,1320));assertTrue("Right ear",inside(points,1010,1310));
            assertTrue("Top corner",inside(points,866,1005));assertTrue("Connector",inside(points,650,1710));
            assertFalse("Right ear notch",inside(points,1038,1254));
            assertFalse("Mat grid below left ear",inside(points,400,1440));
            assertFalse("Mat outside right cheek",inside(points,1050,1450));assertFalse("Backing sheet",inside(points,700,850));
        }
        photo.recycle();
    }
    private boolean inside(List<PointF> p,float x,float y){boolean in=false;for(int i=0,j=p.size()-1;i<p.size();j=i++){PointF a=p.get(i),b=p.get(j);if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)in=!in;}return in;}
}
