package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.PointF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Finds 10 mm cutting mat spacing from dark dots or pale full-length grid lines. */
final class Calibration {
    static final class Result {
        final float pixelsPerMm;
        final int dotCount;
        final boolean automatic;
        Result(float p,int n,boolean a){pixelsPerMm=p;dotCount=n;automatic=a;}
    }
    private Calibration() {}

    static Result detect(Bitmap original) {
        float factor=Math.min(1f,1000f/Math.max(original.getWidth(),original.getHeight()));
        Bitmap image=factor==1 ? original : Bitmap.createScaledBitmap(original,Math.round(original.getWidth()*factor),Math.round(original.getHeight()*factor),true);
        int w=image.getWidth(),h=image.getHeight(),size=w*h;
        int[] px=new int[size];image.getPixels(px,0,w,0,0,w,h);
        Result grid=detectBrightGrid(px,w,h,factor);
        if(grid!=null){if(image!=original)image.recycle();return grid;}
        byte[] mask=new byte[size];
        for(int i=0;i<size;i++) {
            int c=px[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            // Dots are near black; lashes on blue paper are excluded by surrounding matte color later.
            if(r<92 && g<92 && b<94) mask[i]=1;
        }
        byte[] seen=new byte[size];int[] queue=new int[size];List<PointF> dots=new ArrayList<>();
        for(int y=2;y<h-2;y++) for(int x=2;x<w-2;x++) {
            int start=y*w+x;if(mask[start]==0||seen[start]!=0)continue;
            int head=0,tail=0;queue[tail++]=start;seen[start]=1;
            int x0=x,x1=x,y0=y,y1=y;
            while(head<tail && tail<2000) {
                int p=queue[head++],pxx=p%w,py=p/w;
                x0=Math.min(x0,pxx);x1=Math.max(x1,pxx);y0=Math.min(y0,py);y1=Math.max(y1,py);
                int[] neighbors={p-1,p+1,p-w,p+w};
                for(int n:neighbors)if(n>=0&&n<size&&Math.abs(n%w-pxx)<=1&&mask[n]!=0&&seen[n]==0){seen[n]=1;queue[tail++]=n;}
            }
            int bw=x1-x0+1,bh=y1-y0+1;
            if(tail<12||tail>800||bw<4||bh<4||bw>40||bh>40||Math.abs(bw-bh)>Math.max(bw,bh)*.43f)continue;
            float roundness=tail/(float)(bw*bh);
            if(roundness<.35f||roundness>.90f)continue;
            int insetX=Math.max(1,bw/8),insetY=Math.max(1,bh/8),darkCorners=0;
            if(mask[(y0+insetY)*w+x0+insetX]!=0)darkCorners++;
            if(mask[(y0+insetY)*w+x1-insetX]!=0)darkCorners++;
            if(mask[(y1-insetY)*w+x0+insetX]!=0)darkCorners++;
            if(mask[(y1-insetY)*w+x1-insetX]!=0)darkCorners++;
            if(darkCorners>1)continue;
            int cx=(x0+x1)/2,cy=(y0+y1)/2;
            if(cx<3||cy<3||cx>=w-3||cy>=h-3)continue;
            int around=px[Math.max(0,cy-bh)*w+cx],ar=(around>>16)&255,ag=(around>>8)&255,ab=around&255;
            if(ar<95 || ar<ag-7 || ab>ar+7)continue;
            dots.add(new PointF(cx,cy));
        }
        if(image!=original)image.recycle();
        if(dots.size()<4)return null;
        List<Float> distances=new ArrayList<>();
        for(int i=0;i<dots.size();i++)for(int j=i+1;j<dots.size();j++){
            float dx=Math.abs(dots.get(i).x-dots.get(j).x),dy=Math.abs(dots.get(i).y-dots.get(j).y);
            float d=(float)Math.hypot(dx,dy);
            if(d<35||d>330)continue;
            if(Math.min(dx,dy)<d*.10f) distances.add(d);
        }
        if(distances.size()<3)return null;
        float best=0;int bestScore=0;
        for(float candidate:distances){
            int score=0;
            for(float d:distances){float multiple=d/candidate;int k=Math.round(multiple);if(k>=1&&k<=3&&Math.abs(multiple-k)<.065f)score+= k==1?3:1;}
            if(score>bestScore || score==bestScore&&candidate<best){bestScore=score;best=candidate;}
        }
        if(bestScore<9||best<35)return null;
        List<Float> close=new ArrayList<>();for(float d:distances)if(Math.abs(d-best)<best*.07f)close.add(d);
        Collections.sort(close);float spacing=close.get(close.size()/2);
        return new Result(spacing/factor/10f,dots.size(),true);
    }

    /** Short crosses are removed by line opening; outer bands avoid the sculpt and sticker sheet. */
    private static Result detectBrightGrid(int[] pixels,int w,int h,float factor) {
        if(!OpenCVLoader.initLocal())return null;
        byte[] bright=new byte[pixels.length];
        for(int i=0;i<pixels.length;i++){
            int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(r>165&&g>145&&b>140)bright[i]=(byte)255;
        }
        Mat source=new Mat(h,w,CvType.CV_8UC1),vertical=new Mat(),horizontal=new Mat();
        Mat verticalKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,75));
        Mat horizontalKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(75,3));
        try{
            source.put(0,0,bright);
            Imgproc.morphologyEx(source,vertical,Imgproc.MORPH_OPEN,verticalKernel);
            Imgproc.morphologyEx(source,horizontal,Imgproc.MORPH_OPEN,horizontalKernel);
            byte[] lines=new byte[bright.length];int[] vScore=new int[w],hScore=new int[h];
            vertical.get(0,0,lines);
            for(int y=0;y<h;y++)if(y<h/4||y>=3*h/4){
                int row=y*w;for(int x=0;x<w;x++)if(lines[row+x]!=0)vScore[x]++;
            }
            horizontal.get(0,0,lines);
            for(int y=0;y<h;y++){
                int row=y*w;for(int x=0;x<w;x++)if(x<w/4||x>=3*w/4)if(lines[row+x]!=0)hScore[y]++;
            }
            List<Float> vLines=strongLineCenters(vScore,h*.13f);
            List<Float> hLines=strongLineCenters(hScore,w*.13f);
            float vPitch=regularPitch(vLines),hPitch=regularPitch(hLines);
            if(vPitch==0||hPitch==0||Math.abs(vPitch-hPitch)>Math.max(vPitch,hPitch)*.08f)return detectOccludedWarmGrid(pixels,w,h,factor);
            return new Result((vPitch+hPitch)*.5f/factor/10f,vLines.size()*hLines.size(),true);
        }finally{
            source.release();vertical.release();horizontal.release();verticalKernel.release();horizontalKernel.release();
        }
    }

    /** Use the uncovered half of a green mat when the pale decal sheet hides other full lines. */
    private static Result detectOccludedWarmGrid(int[] pixels,int w,int h,float factor){
        byte[] warm=new byte[pixels.length];
        for(int i=0;i<pixels.length;i++){
            int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(r>180&&g>160&&b>140&&g>b+2&&r-g<34)warm[i]=(byte)255;
        }
        Mat source=new Mat(h,w,CvType.CV_8UC1),vertical=new Mat(),horizontal=new Mat();
        Mat verticalKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,75));
        Mat horizontalKernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(75,3));
        try{
            source.put(0,0,warm);
            Imgproc.morphologyEx(source,vertical,Imgproc.MORPH_OPEN,verticalKernel);
            Imgproc.morphologyEx(source,horizontal,Imgproc.MORPH_OPEN,horizontalKernel);
            byte[] lines=new byte[w*h];int[] top=new int[w],bottom=new int[w],left=new int[h],right=new int[h];
            vertical.get(0,0,lines);
            for(int y=0;y<h;y++)if(y<h/4||y>=3*h/4){
                int row=y*w;for(int x=0;x<w;x++)if(lines[row+x]!=0){if(y<h/4)top[x]++;else bottom[x]++;}
            }
            horizontal.get(0,0,lines);
            for(int y=0;y<h;y++){
                int row=y*w;for(int x=0;x<w;x++)if(lines[row+x]!=0){if(x<w/4)left[y]++;else if(x>=3*w/4)right[y]++;}
            }
            int[] vScore=new int[w],hScore=new int[h];
            for(int x=0;x<w;x++)vScore[x]=Math.max(top[x],bottom[x]);
            for(int y=0;y<h;y++)hScore[y]=Math.max(left[y],right[y]);
            List<Float> vLines=mergeNearbyLines(strongLineCenters(vScore,h*.06f),18);
            List<Float> hLines=mergeNearbyLines(strongLineCenters(hScore,w*.06f),18);
            float vPitch=pitchAllowingCoveredLines(vLines),hPitch=pitchAllowingCoveredLines(hLines);
            if(vPitch==0||hPitch==0||Math.abs(vPitch-hPitch)>Math.max(vPitch,hPitch)*.08f)return null;
            return new Result((vPitch+hPitch)*.5f/factor/10f,vLines.size()*hLines.size(),true);
        }finally{source.release();vertical.release();horizontal.release();verticalKernel.release();horizontalKernel.release();}
    }

    private static List<Float> mergeNearbyLines(List<Float> centers,float gap){
        List<Float> merged=new ArrayList<>();
        for(float center:centers){
            int last=merged.size()-1;
            if(last>=0&&center-merged.get(last)<gap)merged.set(last,(merged.get(last)+center)*.5f);
            else merged.add(center);
        }
        return merged;
    }

    private static float pitchAllowingCoveredLines(List<Float> centers){
        if(centers.size()<3)return 0;
        List<Float> gaps=new ArrayList<>();for(int i=1;i<centers.size();i++)gaps.add(centers.get(i)-centers.get(i-1));
        Collections.sort(gaps);
        float pitch=gaps.get(Math.min(1,gaps.size()-1));
        if(pitch<25||pitch>300)return 0;
        int adjacent=0;
        for(float distance:gaps){
            int multiple=Math.round(distance/pitch);
            if(multiple<1||multiple>4||Math.abs(distance-multiple*pitch)>pitch*.08f*multiple)return 0;
            if(multiple==1)adjacent++;
        }
        return adjacent>=2?pitch:0;
    }

    private static List<Float> strongLineCenters(int[] score,float minimumSupport){
        int max=0;for(int n:score)max=Math.max(max,n);
        int threshold=Math.max(Math.round(minimumSupport),Math.round(max*.65f));
        List<Float> centers=new ArrayList<>();
        for(int i=0;i<score.length;){
            if(score[i]<threshold){i++;continue;}
            int start=i;long sum=0,weighted=0;
            while(i<score.length&&score[i]>=threshold){sum+=score[i];weighted+=(long)i*score[i];i++;}
            if(i-start>=2&&sum>0)centers.add(weighted/(float)sum);
        }
        return centers;
    }

    private static float regularPitch(List<Float> centers){
        if(centers.size()<3)return 0;
        List<Float> gaps=new ArrayList<>();
        for(int i=1;i<centers.size();i++)gaps.add(centers.get(i)-centers.get(i-1));
        Collections.sort(gaps);float pitch=gaps.get(gaps.size()/2);
        if(pitch<25||pitch>300)return 0;
        for(float gap:gaps)if(Math.abs(gap-pitch)>pitch*.08f)return 0;
        return pitch;
    }
}
