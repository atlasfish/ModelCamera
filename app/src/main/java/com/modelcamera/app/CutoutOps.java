package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Final sticker edits. All dimensions stay in the source photo's pixel scale. */
final class CutoutOps {
    static final class SplitSuggestion {
        final int x;
        final boolean reliable;
        SplitSuggestion(int x,boolean reliable){this.x=x;this.reliable=reliable;}
    }
    private CutoutOps(){}

    static SplitSuggestion suggestSplit(Bitmap bitmap){
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
        float[] density=new float[w],smooth=new float[w];
        for(int x=0;x<w;x++)for(int y=0;y<h;y++){
            int c=pixels[y*w+x],a=c>>>24;
            if(a<50)continue;
            int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            float darkness=Math.max(0,190-(.21f*r+.72f*g+.07f*b));
            density[x]+=darkness*a/255f;
        }
        int radius=Math.max(1,w/160),left=Math.max(1,w*32/100),right=Math.min(w-1,w*68/100);
        float peak=0;
        for(int x=0;x<w;x++){
            float sum=0;for(int k=Math.max(0,x-radius);k<=Math.min(w-1,x+radius);k++)sum+=density[k];
            smooth[x]=sum/(Math.min(w-1,x+radius)-Math.max(0,x-radius)+1);
            if(x>w/8&&x<w*7/8)peak=Math.max(peak,smooth[x]);
        }
        float threshold=Math.max(1f,peak*.12f);
        int bestX=w/2,bestLength=0,runStart=-1;
        for(int x=left;x<=right+1;x++){
            boolean empty=x<=right&&smooth[x]<=threshold;
            if(empty&&runStart<0)runStart=x;
            if(!empty&&runStart>=0){
                int length=x-runStart,centre=(runStart+x-1)/2;
                if(length>bestLength||(length==bestLength&&Math.abs(centre-w/2)<Math.abs(bestX-w/2))){bestLength=length;bestX=centre;}
                runStart=-1;
            }
        }
        if(bestLength>=Math.max(3,w/90))return new SplitSuggestion(bestX,true);
        float best=Float.MAX_VALUE;
        for(int x=left;x<=right;x++){
            float score=smooth[x]+peak*.08f*Math.abs(x-w/2f)/(w*.18f);
            if(score<best){best=score;bestX=x;}
        }
        return new SplitSuggestion(bestX,peak>0&&smooth[bestX]<peak*.38f);
    }

    static SplitSuggestion suggestSplit(Bitmap bitmap,float angle){
        Bitmap rotated=rotate(bitmap,angle);
        try{return suggestSplit(rotated);}
        finally{if(rotated!=bitmap)rotated.recycle();}
    }

    static int rotatedWidth(int w,int h,float angle){
        double r=Math.toRadians(angle);
        return Math.max(1,(int)Math.ceil(w*Math.abs(Math.cos(r))+h*Math.abs(Math.sin(r))-1e-6));
    }
    static int rotatedHeight(int w,int h,float angle){
        double r=Math.toRadians(angle);
        return Math.max(1,(int)Math.ceil(w*Math.abs(Math.sin(r))+h*Math.abs(Math.cos(r))-1e-6));
    }

    static Bitmap[] render(Bitmap working,int sharpen,float angle,int splitX){
        return render(working,sharpen,0,0,0,angle,splitX);
    }

    /** One colour transform is shared by the live preview and exported PNG. */
    static ColorFilter colorFilter(int contrast,int saturation,int brightness){
        if(contrast==0&&saturation==0&&brightness==0)return null;
        float c=contrast>=0?1f+contrast*.015f:1f+contrast*.008f;
        float s=1f+saturation/100f,neutral=1f-s;
        float red=.2126f*neutral,green=.7152f*neutral,blue=.0722f*neutral;
        float offset=127.5f*(1f-c)+brightness;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            c*(red+s),c*green,c*blue,0,offset,
            c*red,c*(green+s),c*blue,0,offset,
            c*red,c*green,c*(blue+s),0,offset,
            0,0,0,1,0
        }));
    }

    static Bitmap adjustColors(Bitmap source,int contrast,int saturation,int brightness){
        ColorFilter filter=colorFilter(contrast,saturation,brightness);
        if(filter==null)return source;
        Bitmap adjusted=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(filter);
        new Canvas(adjusted).drawBitmap(source,0,0,paint);
        return adjusted;
    }

    static Bitmap[] render(Bitmap working,int sharpen,int contrast,int saturation,int brightness,float angle,int splitX){
        Bitmap sharp=sharpen<=0?working:BlueKey.sharpen(working,sharpen);
        Bitmap filtered=null;
        Bitmap rotated=null;
        try{
            filtered=adjustColors(sharp,contrast,saturation,brightness);
            rotated=rotate(filtered,angle);
            int w=rotated.getWidth();
            if(splitX<0)return new Bitmap[]{trim(rotated,0,w)};
            if(splitX<=2||splitX>=w-2)throw new IllegalArgumentException("分界线太靠边，请重新调整");
            Bitmap left=trim(rotated,0,splitX);
            try{return new Bitmap[]{left,trim(rotated,splitX,w)};}
            catch(RuntimeException e){left.recycle();throw e;}
        }finally{
            if(rotated!=null&&rotated!=filtered)rotated.recycle();
            if(filtered!=null&&filtered!=sharp)filtered.recycle();
            if(sharp!=working)sharp.recycle();
        }
    }

    private static Bitmap rotate(Bitmap source,float angle){
        if(Math.abs(angle)<.01f)return source;
        int w=source.getWidth(),h=source.getHeight();
        int outW=rotatedWidth(w,h,angle),outH=rotatedHeight(w,h,angle);
        Bitmap rotated=Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(rotated);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        canvas.translate(outW/2f,outH/2f);canvas.rotate(angle);
        canvas.drawBitmap(source,-w/2f,-h/2f,paint);
        return rotated;
    }

    private static Bitmap trim(Bitmap source,int x0,int x1){
        int h=source.getHeight(),minX=x1,minY=h,maxX=x0-1,maxY=-1;
        int[] pixels=new int[source.getWidth()*h];source.getPixels(pixels,0,source.getWidth(),0,0,source.getWidth(),h);
        for(int y=0;y<h;y++)for(int x=x0;x<x1;x++)if((pixels[y*source.getWidth()+x]>>>24)>8){
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
        }
        if(maxY<0)throw new IllegalArgumentException("分界线一侧没有图案，请调整后保存");
        minX=Math.max(x0,minX-1);maxX=Math.min(x1-1,maxX+1);
        minY=Math.max(0,minY-1);maxY=Math.min(h-1,maxY+1);
        int w=maxX-minX+1,outH=maxY-minY+1;
        Bitmap out=Bitmap.createBitmap(w,outH,Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(source,new Rect(minX,minY,maxX+1,maxY+1),new RectF(0,0,w,outH),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        return out;
    }
}
