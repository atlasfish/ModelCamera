package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.PointF;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Shape-free contour path for a sculpt clearly separated from a coloured mat. */
final class SculptSilhouette {
    static List<PointF> detect(Bitmap photo,PointF seed,float regionSize){
        float scale=Math.min(1f,960f/Math.max(photo.getWidth(),photo.getHeight()));
        Bitmap small=Bitmap.createScaledBitmap(photo,Math.round(photo.getWidth()*scale),Math.round(photo.getHeight()*scale),true);
        Mat rgba=new Mat(),rgb=new Mat(),mask=new Mat(),bg=new Mat(),fg=new Mat(),binary=new Mat(),ring=new Mat(),solid=new Mat(),hierarchy=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(7,7));
        List<MatOfPoint> contours=new ArrayList<>();
        try{
            Utils.bitmapToMat(small,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);
            int w=rgb.cols(),h=rgb.rows();byte[] colors=new byte[w*h*3];rgb.get(0,0,colors);
            int coloured=0;
            for(int i=0;i<w*h;i+=9)if(matColor(colors,i))coloured++;
            // Low-contrast flesh-coloured mats retain the established shadow-aware path.
            if(coloured<w*h/9*.12f)return null;
            int cx=Math.max(2,Math.min(w-3,Math.round(seed.x*scale))),cy=Math.max(2,Math.min(h-3,Math.round(seed.y*scale)));
            double size=Math.max(50,regionSize*scale);
            int x0=Math.max(1,(int)(cx-size)),x1=Math.min(w-1,(int)(cx+size));
            int y0=Math.max(1,(int)(cy-size*1.5)),y1=Math.min(h-1,(int)(cy+size*1.5));
            Rect roi=new Rect(x0,y0,x1-x0,y1-y0);
            if(roi.width<30||roi.height<30)return null;
            Point center=new Point(cx,cy);
            // A loose rectangle lets the pale mat grid join the pale sculpt.
            // First remove thin grid structures, then grow only the seed's component.
            byte[] initial=new byte[w*h];
            int seedIndex=(cy*w+cx)*3;
            float seedLight=((colors[seedIndex]&255)+(colors[seedIndex+1]&255)+(colors[seedIndex+2]&255))/3f;
            for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
                int p=y*w+x,i=p*3,r=colors[i]&255,g=colors[i+1]&255,b=colors[i+2]&255;
                if(!matColor(colors,p)&&Math.min(r,Math.min(g,b))>seedLight*.48f
                    &&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<90)initial[p]=(byte)255;
            }
            binary.create(h,w,CvType.CV_8UC1);binary.put(0,0,initial);
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_OPEN,kernel);
            binary.get(0,0,initial);
            if(initial[cy*w+cx]==0)return null;
            byte[] component=new byte[w*h];int[] queue=new int[w*h];int head=0,tail=0;
            queue[tail++]=cy*w+cx;component[cy*w+cx]=(byte)255;
            while(head<tail){int p=queue[head++],x=p%w,y=p/w;
                for(int d=0;d<4;d++){int xx=x+(d==0?-1:d==1?1:0),yy=y+(d==2?-1:d==3?1:0);
                    if(xx<0||xx>=w||yy<0||yy>=h)continue;int next=yy*w+xx;
                    if(initial[next]!=0&&component[next]==0){component[next]=(byte)255;queue[tail++]=next;}
                }
            }
            binary.put(0,0,component);Imgproc.dilate(binary,ring,kernel);Imgproc.erode(binary,solid,kernel);
            byte[] outer=new byte[w*h],inner=new byte[w*h];ring.get(0,0,outer);solid.get(0,0,inner);
            byte[] initialLabels=new byte[w*h];
            for(int i=0;i<initialLabels.length;i++)initialLabels[i]=(byte)(inner[i]!=0?Imgproc.GC_FGD:component[i]!=0?Imgproc.GC_PR_FGD:outer[i]!=0?Imgproc.GC_PR_BGD:Imgproc.GC_BGD);
            mask.create(h,w,CvType.CV_8UC1);mask.put(0,0,initialLabels);
            Imgproc.grabCut(rgb,mask,roi,bg,fg,5,Imgproc.GC_INIT_WITH_MASK);
            byte[] labels=new byte[w*h];mask.get(0,0,labels);
            for(int i=0;i<labels.length;i++)labels[i]=(byte)(labels[i]==Imgproc.GC_FGD||labels[i]==Imgproc.GC_PR_FGD?255:0);
            binary.create(h,w,CvType.CV_8UC1);binary.put(0,0,labels);
            // Remove narrow grid-line spurs admitted by the boundary refinement.
            int cleanupSize=Math.max(3,Math.min(25,((int)(size*.067f))|1));
            Mat cleanup=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(cleanupSize,cleanupSize));
            try{Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_OPEN,cleanup);}finally{cleanup.release();}
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_NONE);
            MatOfPoint chosen=null;double best=0;
            for(MatOfPoint contour:contours){MatOfPoint2f curve=new MatOfPoint2f(contour.toArray());try{
                double area=Imgproc.contourArea(contour);
                if(area>best&&Imgproc.pointPolygonTest(curve,center,false)>=0){chosen=contour;best=area;}
            }finally{curve.release();}}
            if(chosen==null||best<size*size*.06||best>roi.area()*.85)return null;
            Rect bounds=Imgproc.boundingRect(chosen);
            if(bounds.x<=x0+1||bounds.y<=y0+1||bounds.x+bounds.width>=x1-1||bounds.y+bounds.height>=y1-1)return null;
            solid.create(h,w,CvType.CV_8UC1);solid.setTo(new Scalar(0));
            Imgproc.drawContours(solid,Collections.singletonList(chosen),0,new Scalar(255),Imgproc.FILLED);
            Mat supportKernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(25,25));
            try{Imgproc.dilate(solid,ring,supportKernel);}finally{supportKernel.release();}
            Imgproc.dilate(solid,binary,kernel);Core.subtract(ring,binary,ring);
            byte[] outside=new byte[w*h];ring.get(0,0,outside);int green=0,n=0;
            for(int i=0;i<outside.length;i++)if(outside[i]!=0){n++;if(matColor(colors,i))green++;}
            // Demand evidence around the object, not merely elsewhere in the photo.
            if(n==0||green<n*.40f)return null;
            MatOfPoint2f curve=new MatOfPoint2f(chosen.toArray()),simple=new MatOfPoint2f();
            try{
                // Subpixel-scale simplification preserves concavities, asymmetric
                // ears and tabs. No radial sampling, symmetry or convex hull.
                Imgproc.approxPolyDP(curve,simple,.7,true);
                List<PointF> result=new ArrayList<>();
                for(Point point:simple.toArray())result.add(new PointF((float)point.x/scale,(float)point.y/scale));
                return result;
            }finally{curve.release();simple.release();}
        }finally{
            if(small!=photo)small.recycle();
            for(MatOfPoint c:contours)c.release();
            for(Mat m:new Mat[]{rgba,rgb,mask,bg,fg,binary,ring,solid,hierarchy,kernel})m.release();
        }
    }
    private static boolean matColor(byte[] colors,int pixel){
        int i=pixel*3,r=colors[i]&255,g=colors[i+1]&255,b=colors[i+2]&255;
        return g-r>7&&g>b-8&&r<170;
    }
}
