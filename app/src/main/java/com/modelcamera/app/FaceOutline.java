package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.PointF;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.List;

/** Seeded GrabCut for an unpainted sculpt; no human-face landmark model is assumed. */
final class FaceOutline {
    static List<PointF> detect(Bitmap photo,PointF seed,float regionSize){
        if(!OpenCVLoader.initLocal())throw new IllegalStateException("轮廓算法无法启动");
        List<PointF> silhouette=SculptSilhouette.detect(photo,seed,regionSize);
        if(silhouette!=null)return silhouette;
        // Segment at this size: a larger GrabCut mask tends to absorb the adjacent shadow.
        float factor=Math.min(1f,640f/Math.max(photo.getWidth(),photo.getHeight()));
        Bitmap small=Bitmap.createScaledBitmap(photo,Math.max(1,Math.round(photo.getWidth()*factor)),Math.max(1,Math.round(photo.getHeight()*factor)),true);
        Mat rgba=new Mat(),rgb=new Mat(),mask=new Mat(),bg=new Mat(),fg=new Mat(),binary=new Mat(),hierarchy=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,new Size(3,3));List<MatOfPoint> contours=new ArrayList<>();
        try{
            Utils.bitmapToMat(small,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);
            int w=rgb.cols(),h=rgb.rows();double cx=Math.max(2,Math.min(w-3,seed.x*factor)),cy=Math.max(2,Math.min(h-3,seed.y*factor));
            double rw=Math.max(50,regionSize*factor*1.45),rh=Math.max(65,regionSize*factor*1.9);
            int x0=Math.max(1,(int)(cx-rw/2)),y0=Math.max(1,(int)(cy-rh/2));
            int x1=Math.min(w-2,(int)(cx+rw/2)),y1=Math.min(h-2,(int)(cy+rh/2));
            org.opencv.core.Rect rect=new org.opencv.core.Rect(x0,y0,x1-x0,y1-y0);
            if(rect.width<30||rect.height<30)throw new IllegalArgumentException("请点在面雕中部");
            mask.create(h,w,CvType.CV_8UC1);mask.setTo(new Scalar(Imgproc.GC_BGD));
            Imgproc.rectangle(mask,rect,new Scalar(Imgproc.GC_PR_BGD),Imgproc.FILLED);
            Point center=new Point(cx,cy);
            Imgproc.ellipse(mask,center,new Size(rect.width*.27,rect.height*.29),0,0,360,new Scalar(Imgproc.GC_PR_FGD),Imgproc.FILLED);
            Imgproc.ellipse(mask,center,new Size(rect.width*.085,rect.height*.085),0,0,360,new Scalar(Imgproc.GC_FGD),Imgproc.FILLED);
            Imgproc.grabCut(rgb,mask,rect,bg,fg,5,Imgproc.GC_INIT_WITH_MASK);
            Core.inRange(mask,new Scalar(Imgproc.GC_PR_FGD),new Scalar(Imgproc.GC_PR_FGD),binary);
            Mat sure=new Mat();try{Core.inRange(mask,new Scalar(Imgproc.GC_FGD),new Scalar(Imgproc.GC_FGD),sure);Core.bitwise_or(binary,sure,binary);}finally{sure.release();}
            Imgproc.morphologyEx(binary,binary,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            MatOfPoint chosen=null;double best=0;
            for(MatOfPoint c:contours){MatOfPoint2f curve=new MatOfPoint2f(c.toArray());try{
                double area=Imgproc.contourArea(c);
                if(Imgproc.pointPolygonTest(curve,center,false)>=0&&area>best){best=area;chosen=c;}
            }finally{curve.release();}}
            if(chosen==null||best<rect.area()*.07||best>rect.area()*.92)throw new IllegalArgumentException("轮廓不够明确，请重选面雕中心或调整拍摄光线");
            org.opencv.core.Rect bounds=Imgproc.boundingRect(chosen);
            if(bounds.x<=rect.x+1||bounds.y<=rect.y+1||bounds.x+bounds.width>=rect.x+rect.width-1||bounds.y+bounds.height>=rect.y+rect.height-1)
                throw new IllegalArgumentException("边界延伸到识别区域外，请重选面雕中部");
            // Trace the boundary at higher resolution while retaining the coarse mask's
            // reliable separation between the sculpt and its cast shadow.
            List<PointF> coarse=refineEdge(rgb,chosen,center,factor,false);
            float detailFactor=Math.min(1f,960f/Math.max(photo.getWidth(),photo.getHeight()));
            Bitmap detail=Bitmap.createScaledBitmap(photo,Math.max(1,Math.round(photo.getWidth()*detailFactor)),Math.max(1,Math.round(photo.getHeight()*detailFactor)),true);
            Mat detailRgba=new Mat(),detailRgb=new Mat();MatOfPoint detailContour=new MatOfPoint();
            try{
                Utils.bitmapToMat(detail,detailRgba);Imgproc.cvtColor(detailRgba,detailRgb,Imgproc.COLOR_RGBA2RGB);
                Point[] original=chosen.toArray(),scaled=new Point[original.length];double ratio=detailFactor/factor;
                for(int i=0;i<original.length;i++)scaled[i]=new Point(original[i].x*ratio,original[i].y*ratio);
                detailContour.fromArray(scaled);
                List<PointF> fine=refineEdge(detailRgb,detailContour,new Point(center.x*ratio,center.y*ratio),detailFactor,true);
                // The high-resolution trace helps the pale forehead, but on the
                // cheek it may prefer the much stronger outer edge of a shadow.
                List<PointF> merged=new ArrayList<>();
                for(int i=0;i<fine.size();i++){
                    double angle=-Math.PI/2+2*Math.PI*i/fine.size();
                    double upper=-Math.sin(angle);
                    double blend=Math.max(0,Math.min(1,(upper-.55)/.25));
                    double coarseIndex=(double)i*coarse.size()/fine.size();int left=(int)Math.floor(coarseIndex)%coarse.size(),right=(left+1)%coarse.size();
                    float t=(float)(coarseIndex-Math.floor(coarseIndex));
                    PointF a=coarse.get(left),b=coarse.get(right),f=fine.get(i);
                    merged.add(new PointF((float)((a.x+(b.x-a.x)*t)*(1-blend)+f.x*blend),
                        (float)((a.y+(b.y-a.y)*t)*(1-blend)+f.y*blend)));
                }
                return merged;
            }finally{if(detail!=photo)detail.recycle();detailRgba.release();detailRgb.release();detailContour.release();}
        }finally{
            if(small!=photo)small.recycle();rgba.release();rgb.release();mask.release();bg.release();fg.release();binary.release();hierarchy.release();kernel.release();
            for(MatOfPoint c:contours)c.release();
        }
    }

    /** Follow local edges around the whole sculpt, including shaded material.
     * Brightness is only used for local contrast; no dark pixel is removed from the sculpt mask.
     * A weak bilateral prior discourages one-sided cast-shadow extensions without mirroring the result.
     */
    private static List<PointF> refineEdge(Mat rgb,MatOfPoint contour,Point center,float factor,boolean fine){
        int w=rgb.cols(),h=rgb.rows(),count=fine?240:180;Mat solid=Mat.zeros(h,w,CvType.CV_8UC1),smooth=new Mat();
        try{
            Imgproc.drawContours(solid,java.util.Collections.singletonList(contour),0,new Scalar(255),Imgproc.FILLED);
            Imgproc.GaussianBlur(rgb,smooth,new Size(3,3),.65);
            byte[] mask=new byte[w*h],color=new byte[w*h*3];solid.get(0,0,mask);smooth.get(0,0,color);
            double[] radius=new double[count],dx=new double[count],dy=new double[count];
            for(int i=0;i<count;i++){
                double a=-Math.PI/2+2*Math.PI*i/count;dx[i]=Math.cos(a);dy[i]=Math.sin(a);
                for(int r=1;r<Math.max(w,h);r++){
                    int x=(int)Math.round(center.x+dx[i]*r),y=(int)Math.round(center.y+dy[i]*r);
                    if(x<1||y<1||x>=w-1||y>=h-1||mask[y*w+x]==0){radius[i]=r;break;}
                }
            }
            // Median suppresses small connected mat specks, while keeping the face's actual outline.
            double[] rough=radius.clone();
            for(int i=0;i<count;i++){double[] window=new double[5];for(int k=-2;k<=2;k++)window[k+2]=rough[(i+k+count)%count];java.util.Arrays.sort(window);radius[i]=window[2];}
            double innerLight=luma(sample(color,w,h,center.x,center.y));
            double[] outside=new double[count];
            for(int i=0;i<count;i++)outside[i]=luma(sample(color,w,h,center.x+dx[i]*(radius[i]+6),center.y+dy[i]*(radius[i]+6)));
            java.util.Arrays.sort(outside);double polarity=innerLight>=outside[count/2]?1:-1;
            int[] start=new int[count],end=new int[count];double[][] reward=new double[count][];
            for(int i=0;i<count;i++){
                // Permit a small outward correction without reaching mat dots or the
                // cast shadow, whose edges can be stronger than the sculpt boundary.
                start[i]=Math.max(8,(int)Math.floor(radius[i]*(fine?.84:.76)-(fine?3:0)));end[i]=(int)Math.ceil(radius[i]+(fine?5:3));
                reward[i]=new double[end[i]-start[i]+1];int mirror=(count-i)%count;
                double reference=Math.min(radius[i],radius[mirror]);
                for(int r=start[i];r<=end[i];r++){
                    double[] inside=profile(color,w,h,center,dx[i],dy[i],r-2),out=profile(color,w,h,center,dx[i],dy[i],r+2);
                    double distance=0;for(int c=0;c<3;c++)distance+=(inside[c]-out[c])*(inside[c]-out[c]);
                    double contrast=polarity*(luma(inside)-luma(out));
                    reward[i][r-start[i]]=(fine?.06:.28)*Math.sqrt(distance)+(fine?1.05:.9)*Math.max(0,contrast)
                        -(fine?.15:.06)*Math.abs(r-radius[i])-.4*Math.max(0,Math.abs(r-reference)-reference*.12);
                }
            }
            int first=0;for(int j=1;j<reward[0].length;j++)if(reward[0][j]>reward[0][first])first=j;
            double[][] cost=new double[count][];int[][] previous=new int[count][];
            cost[0]=new double[reward[0].length];java.util.Arrays.fill(cost[0],Double.NEGATIVE_INFINITY);cost[0][first]=reward[0][first];
            for(int i=1;i<count;i++){
                cost[i]=new double[reward[i].length];previous[i]=new int[reward[i].length];
                for(int j=0;j<cost[i].length;j++){
                    double best=Double.NEGATIVE_INFINITY;int bestK=0;
                    for(int k=0;k<cost[i-1].length;k++){
                        double delta=(start[i]+j)-(start[i-1]+k),value=cost[i-1][k]-(fine?.85:.6)*delta*delta;
                        if(value>best){best=value;bestK=k;}
                    }cost[i][j]=best+reward[i][j];previous[i][j]=bestK;
                }
            }
            int last=0;double best=Double.NEGATIVE_INFINITY;
            for(int j=0;j<cost[count-1].length;j++){
                double delta=(start[count-1]+j)-(start[0]+first),value=cost[count-1][j]-(fine?.85:.6)*delta*delta;
                if(value>best){best=value;last=j;}
            }
            int[] chosen=new int[count];chosen[count-1]=last;
            for(int i=count-1;i>0;i--)chosen[i-1]=previous[i][chosen[i]];
            double[] edge=new double[count],clean=new double[count];
            for(int i=0;i<count;i++)edge[i]=start[i]+chosen[i];
            // Remove narrow outward hooks caused by nearby mat dots, never by dark skin pixels.
            for(int i=0;i<count;i++){
                // The forehead is broad and smooth; a nearby grid dot can create a
                // several-ray outward peak. Use a wider median only in this sector.
                int half=fine&&dy[i]<-.65?10:4;double[] window=new double[half*2+1];
                for(int k=-half;k<=half;k++)window[k+half]=edge[(i+k+count)%count];
                java.util.Arrays.sort(window);double median=window[half];
                double allowance=fine&&dy[i]<-.65?1:1.5;
                clean[i]=edge[i]-median>allowance?median+(fine?allowance:0):edge[i];
            }
            // A short circular Gaussian suppresses pixel-scale pits without flattening
            // the chin or replacing the real shaded cheek with a mirrored edge.
            double[] softened=new double[count];
            for(int i=0;i<count;i++)softened[i]=fine?(.05*clean[(i+count-2)%count]+.25*clean[(i+count-1)%count]
                +.4*clean[i]+.25*clean[(i+1)%count]+.05*clean[(i+2)%count])
                :(.25*clean[(i+count-1)%count]+.5*clean[i]+.25*clean[(i+1)%count]);
            List<PointF> points=new ArrayList<>();
            for(int i=0;i<count;i++){
                double r=softened[i];
                points.add(new PointF((float)(center.x+dx[i]*r)/factor,(float)(center.y+dy[i]*r)/factor));
            }
            return points;
        }finally{solid.release();smooth.release();}
    }
    private static double[] profile(byte[] rgb,int w,int h,Point center,double dx,double dy,double radius){
        double[] result=new double[3];
        for(int k=-1;k<=1;k++){double[] p=sample(rgb,w,h,center.x+dx*radius-dy*k,center.y+dy*radius+dx*k);for(int c=0;c<3;c++)result[c]+=p[c]/3;}
        return result;
    }
    private static double[] sample(byte[] rgb,int w,int h,double x,double y){
        int ix=Math.max(0,Math.min(w-1,(int)Math.round(x))),iy=Math.max(0,Math.min(h-1,(int)Math.round(y))),i=(iy*w+ix)*3;
        return new double[]{rgb[i]&255,rgb[i+1]&255,rgb[i+2]&255};
    }
    private static double luma(double[] rgb){return rgb[0]*.2126+rgb[1]*.7152+rgb[2]*.0722;}
}
