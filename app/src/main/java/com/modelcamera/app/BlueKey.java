package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.util.Arrays;

/** Local color key for decal paper, including cyan paper made lavender by flash and white balance. */
final class BlueKey {
    static final class Result {
        final Bitmap bitmap;
        final Rect sourceBounds;
        final float blueFraction;
        Result(Bitmap bitmap, Rect sourceBounds, float blueFraction) { this.bitmap=bitmap; this.sourceBounds=sourceBounds; this.blueFraction=blueFraction; }
    }
    private BlueKey() {}

    private static int blueScore(int color) {
        int r=(color>>16)&255, g=(color>>8)&255, b=color&255;
        return Math.min(g-r,b-r);
    }
    private static boolean isBlue(int color) {
        int r=(color>>16)&255, g=(color>>8)&255, b=color&255;
        return r>65 && g>90 && b>100 && blueScore(color)>8 && b>=g-9;
    }
    private static boolean isLavenderPaper(int color){
        int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
        // Flash can make pale cyan backing look pink-lavender. The small blue-over-green
        // difference distinguishes it from warm resin faces and cream cutting-mat lines.
        return r>150&&g>140&&b>150&&b>=g+2&&r>=g&&r-g<30;
    }
    private static boolean isPaperColor(int color){return isBlue(color)||isLavenderPaper(color);}

    private static PaperColorModel adaptivePaper(int[] px,int w,int h){
        int known=0,n=0;
        for(int i=0;i<px.length;i+=3){n++;if(isPaperColor(px[i]))known++;}
        return known>=n*.10f?null:PaperColorModel.learn(px,w,h);
    }
    private static boolean paper(int c,PaperColorModel model){return model==null?isPaperColor(c):model.matches(c);}

    /** Largest connected backing region excludes disconnected cutting-mat lines. */
    private static Rect paperRegion(int[] px,int w,int h,PaperColorModel model){
        int gw=(w+2)/3,gh=(h+2)/3;
        boolean[] seen=new boolean[gw*gh],solid=new boolean[gw*gh];int[] queue=new int[seen.length];
        int radius=Math.max(6,w/100);
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
            int xx=x*3,yy=y*3;
            solid[y*gw+x]=model.matches(px[yy*w+xx])
                &&model.matches(px[yy*w+Math.max(0,xx-radius)])
                &&model.matches(px[yy*w+Math.min(w-1,xx+radius)])
                &&model.matches(px[Math.max(0,yy-radius)*w+xx])
                &&model.matches(px[Math.min(h-1,yy+radius)*w+xx]);
        }
        int best=0;Rect bounds=null;
        for(int start=0;start<seen.length;start++){
            if(seen[start]||!solid[start])continue;
            int head=0,tail=0,left=w,right=0,top=h,bottom=0;queue[tail++]=start;seen[start]=true;
            while(head<tail){
                int p=queue[head++],x=p%gw,y=p/gw;
                left=Math.min(left,x*3);right=Math.max(right,x*3);top=Math.min(top,y*3);bottom=Math.max(bottom,y*3);
                for(int d=0;d<4;d++){
                    int xx=x+(d==0?-1:d==1?1:0),yy=y+(d==2?-1:d==3?1:0);
                    if(xx<0||xx>=gw||yy<0||yy>=gh)continue;
                    int next=yy*gw+xx;
                    if(!seen[next]&&solid[next]){seen[next]=true;queue[tail++]=next;}
                }
            }
            if(tail>best&&right-left>w/8&&bottom-top>h/25&&tail*9>(right-left)*(bottom-top)*.3f){
                best=tail;bounds=new Rect(left,top,right,bottom);
            }
        }
        return bounds;
    }

    static Rect suggest(Bitmap image) {
        int w=image.getWidth(), h=image.getHeight();
        int[] px=new int[w*h]; image.getPixels(px,0,w,0,0,w,h);
        PaperColorModel model=adaptivePaper(px,w,h);
        int left=w, right=0, top=h, bottom=0, paperSamples=0,lavenderSamples=0;
        int[] columns=new int[w],rows=new int[h];
        for(int y=0;y<h;y+=3) for(int x=0;x<w;x+=3) if(paper(px[y*w+x],model)) {
            columns[x]++;rows[y]++;paperSamples++;
            if(isLavenderPaper(px[y*w+x]))lavenderSamples++;
        }
        int maxColumn=0,maxRow=0;for(int count:columns)maxColumn=Math.max(maxColumn,count);for(int count:rows)maxRow=Math.max(maxRow,count);
        int colThreshold=Math.max(6,maxColumn/12),rowThreshold=Math.max(6,maxRow/12);
        for(int x=0;x<w;x+=3)if(columns[x]>=colThreshold){left=Math.min(left,x);right=Math.max(right,x);}
        for(int y=0;y<h;y+=3)if(rows[y]>=rowThreshold){top=Math.min(top,y);bottom=Math.max(bottom,y);}
        if(model!=null){Rect region=paperRegion(px,w,h,model);if(region!=null){left=region.left;right=region.right;top=region.top;bottom=region.bottom;}}
        if(paperSamples<80 || right-left<w/8 || bottom-top<h/25) return new Rect(w/5,h/5,w*4/5,h*2/5);
        int bw=right-left;
        boolean lavender=model==null&&lavenderSamples>paperSamples*.45f;
        int inkLimit=lavender?190:112;
        int[] counts=new int[bottom-top+1];
        for(int y=top;y<=bottom;y+=2) for(int x=left;x<=right;x+=2) {
            int c=px[y*w+x], r=(c>>16)&255, g=(c>>8)&255, b=c&255;
            if(r<inkLimit && g<inkLimit && b<inkLimit+8 && paperNear(px,w,h,x,y,lavender?3:2,model)) counts[y-top]++;
        }
        int start=-1;
        int rowMin=Math.max(3,bw/110);
        int scanStart=Math.min(counts.length-1,Math.max(24,bw/14));
        for(int i=scanStart;i<counts.length;i++) {
            if(counts[i]>=rowMin) {
                start=i;
                break;
            }
        }
        if(start<0) return new Rect(left+bw/10,top+(bottom-top)/10,right-bw/10,top+(bottom-top)/3);
        // One expression occupies roughly a fifth of the sheet width vertically.
        // This intentionally stops before the next row on multi-expression sheets.
        int contentStart=top+start;
        int y0=Math.max(top,contentStart-(lavender?Math.max(35,bw/10):Math.max(8,bw/50)));
        int y1=Math.min(bottom,contentStart+Math.round(bw*(lavender?.26f:.21f))+(lavender?15:20));
        int x0=w,x1=0;
        for(int y=y0;y<=y1;y+=2) for(int x=left;x<=right;x+=2) {
            int c=px[y*w+x];
            if(((c>>16)&255)<inkLimit && ((c>>8)&255)<inkLimit && (c&255)<inkLimit+8 && paperNear(px,w,h,x,y,lavender?3:2,model)) { x0=Math.min(x0,x); x1=Math.max(x1,x); }
        }
        if(x1<=x0) {x0=left+bw/10; x1=right-bw/10;}
        int margin=Math.max(12,bw/(lavender?15:22));
        return new Rect(Math.max(left+3,x0-margin),Math.max(top+3,y0),Math.min(right-3,x1+margin),Math.min(bottom-3,y1));
    }

    private static boolean paperNear(int[] px,int w,int h,int x,int y,int required,PaperColorModel model) {
        int d=Math.max(5,w/150);
        int count=0;
        if(paper(px[Math.max(0,y-d)*w+x],model))count++;
        if(paper(px[Math.min(h-1,y+d)*w+x],model))count++;
        if(paper(px[y*w+Math.max(0,x-d)],model))count++;
        if(paper(px[y*w+Math.min(w-1,x+d)],model))count++;
        return count>=required;
    }

    static Result extract(Bitmap source, Rect requested, int sensitivity) {
        Rect box=ImageTools.clamp(requested,source);
        int w=box.width(),h=box.height();
        if(w<24 || h<16) throw new IllegalArgumentException("框选区域太小");
        int[] pixels=new int[w*h]; source.getPixels(pixels,0,w,box.left,box.top,w,h);
        // Learn the actual pale paper colour from this crop. Blue irises occupy far
        // fewer pixels than the paper, so channel medians are resistant to them.
        int[] rs=new int[(pixels.length+2)/3],gs=new int[rs.length],bs=new int[rs.length];
        PaperColorModel model=adaptivePaper(pixels,w,h);
        int paperCount=0;
        for(int i=0;i<pixels.length;i+=3) if(paper(pixels[i],model)) {
            int c=pixels[i];rs[paperCount]=(c>>16)&255;gs[paperCount]=(c>>8)&255;bs[paperCount]=c&255;paperCount++;
        }
        if(paperCount < pixels.length/35) throw new IllegalArgumentException("框内没有识别到足够的水贴底纸；请把选框移到水贴上");
        Arrays.sort(rs,0,paperCount);Arrays.sort(gs,0,paperCount);Arrays.sort(bs,0,paperCount);
        int br=rs[paperCount/2],bg=gs[paperCount/2],bb=bs[paperCount/2];
        boolean flashTintedPaper=br>=bg&&bb>=bg+2;
        // A row-local estimate handles uneven lighting without treating a dark,
        // slightly cyan eyebrow as if it were the backing sheet.
        long[] rowR=new long[h],rowG=new long[h],rowB=new long[h];int[] rowN=new int[h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=pixels[y*w+x];
            if(paper(c,model)&&distance(c,br,bg,bb)<65){rowR[y]+=(c>>16)&255;rowG[y]+=(c>>8)&255;rowB[y]+=c&255;rowN[y]++;}
        }
        int[] rowPaperR=new int[h],rowPaperG=new int[h],rowPaperB=new int[h];
        int radius=Math.max(5,Math.min(24,h/9));
        for(int y=0;y<h;y++){
            long r=0,g=0,b=0;int n=0;
            for(int yy=Math.max(0,y-radius);yy<=Math.min(h-1,y+radius);yy++){r+=rowR[yy];g+=rowG[yy];b+=rowB[yy];n+=rowN[yy];}
            rowPaperR[y]=n>=w?Math.round(r/(float)n):br;
            rowPaperG[y]=n>=w?Math.round(g/(float)n):bg;
            rowPaperB[y]=n>=w?Math.round(b/(float)n):bb;
        }
        // Estimate the backing in both image directions. A single row average
        // mistakes a left-to-right light falloff for sticker ink. Tile medians
        // reject small print and reflections; sparse tiles use the row model.
        int[][] localPaper=localPaperModel(pixels,w,h,br,bg,bb,rowPaperR,rowPaperG,rowPaperB,model);
        int[] paperR=localPaper[0],paperG=localPaper[1],paperB=localPaper[2];
        // Keep glare detection anchored to the broader row estimate. A local
        // tile can be darker than a warm printed eye white and falsely make
        // that white look like a flash reflection.
        boolean[] reflection=reflectionMask(pixels,w,h,rowPaperR,rowPaperG,rowPaperB,model);
        boolean[] printedLight=model==null?null:protectedPrint(pixels,w,h,rowPaperR,rowPaperG,rowPaperB);
        int cutoff=17+Math.round(sensitivity*.26f),feather=22;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int i=y*w+x,c=pixels[i]; int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            // Match the paper's full RGB colour, not simply the presence of blue.
            // Saturated blue pupils and dark blue ink are therefore kept.
            int d=distance(c,paperR[i],paperG[i],paperB[i]);
            int alpha=clamp((d-cutoff)*255/feather);
            if(flashTintedPaper&&alpha>15&&alpha<255){
                // On flash-tinted paper, weak gray brows can sit close to the
                // backing in RGB distance. Strengthen only edges already found
                // by the colour key, or dark backing around the brows forms a
                // visible halo. Require all three channels to darken together.
                int dr=paperR[i]-r,dg=paperG[i]-g,db=paperB[i]-b;
                int spread=Math.max(dr,Math.max(dg,db))-Math.min(dr,Math.min(dg,db));
                if(dr>0&&dg>0&&db>0&&spread<=14){
                    float darkContrast=.2126f*dr+.7152f*dg+.0722f*db;
                    int grayAlpha=clamp(Math.round((darkContrast-(4+sensitivity*.08f))*255/24f));
                    alpha=Math.max(alpha,grayAlpha);
                }
            }
            if(r<65&&g<80&&b<95)alpha=255;
            // Printed eye whites can be almost as pale as the backing. Their
            // neutral colour and higher red channel distinguish them from cyan.
            if(r>paperR[i]+19&&Math.abs(r-g)<23&&Math.abs(g-b)<23)alpha=255;
            // On flash-tinted lavender paper the eye whites become warm cream,
            // while the backing keeps a blue-over-green cast.
            if(r>205&&r>paperR[i]+8&&r-g>paperR[i]-paperG[i]+6&&g-b>paperG[i]-paperB[i]+5)alpha=255;
            if(reflection[i])alpha=0;
            if(printedLight!=null&&printedLight[i])alpha=255;
            if(alpha<255 && alpha>15) {
                float a=alpha/255f;
                r=clamp(Math.round((r-(1-a)*paperR[i])/a));
                g=clamp(Math.round((g-(1-a)*paperG[i])/a));
                b=clamp(Math.round((b-(1-a)*paperB[i])/a));
            }
            pixels[i]=(alpha<<24)|(r<<16)|(g<<8)|b;
        }
        // Camera blur carries the cyan sheet into grey lashes and brows. Their
        // blue-green cast is weak; a blue pupil has much stronger blue chroma.
        int[] despilled=pixels.clone();
        int eyeTop=h,eyeBottom=-1;
        for(int y=0;y<h;y++){
            int ink=0;
            for(int x=0;x<w;x++){
                int c=pixels[y*w+x];
                if((c>>>24)>160&&((c>>16)&255)<95&&((c>>8)&255)<95&&(c&255)<110)ink++;
            }
            if(ink>Math.max(14,w/12)){eyeTop=Math.min(eyeTop,y);eyeBottom=Math.max(eyeBottom,y);}
        }
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
            int i=y*w+x,c=pixels[i],a=c>>>24;
            if(a<25)continue;
            int r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(r<170&&g>r+6&&b>=g&&b-g<24&&b-r<68){
                if(y>=eyeTop-2&&y<=eyeBottom+2&&a>245){
                    boolean edge=false;
                    for(int dy=-1;dy<=1&&!edge;dy++)for(int dx=-1;dx<=1;dx++)if((pixels[(y+dy)*w+x+dx]>>>24)<30)edge=true;
                    if(!edge)continue;
                }
                int gray=clamp((2*r+g+b)/4);despilled[i]=(a<<24)|(gray<<16)|(gray<<8)|gray;
            }
        }
        pixels=despilled;
        // Ignore tiny isolated reflections and dust on the backing sheet.
        boolean[] visited=new boolean[pixels.length];int[] queue=new int[pixels.length];
        int minComponent=Math.max(18,Math.min(50,pixels.length/1300));
        for(int start=0;start<pixels.length;start++){
            if(visited[start]||(pixels[start]>>>24)<=35)continue;
            int head=0,tail=0;queue[tail++]=start;visited[start]=true;
            while(head<tail){
                int index=queue[head++],x=index%w,y=index/w;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int xx=x+dx,yy=y+dy;
                    if(xx<0||xx>=w||yy<0||yy>=h)continue;
                    int next=yy*w+xx;
                    if(!visited[next]&&(pixels[next]>>>24)>35){visited[next]=true;queue[tail++]=next;}
                }
            }
            if(tail<minComponent)for(int j=0;j<tail;j++)pixels[queue[j]]&=0x00FFFFFF;
        }
        int minX=w,minY=h,maxX=0,maxY=0,foreground=0;
        int darkLeft=w,darkTop=h,darkRight=0,darkBottom=0,darkCount=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=pixels[y*w+x],a=c>>>24,r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(a>35){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);foreground++;}
            int inkContrast=Math.min(paperR[y*w+x]-r,Math.min(paperG[y*w+x]-g,paperB[y*w+x]-b));
            if(a>160&&inkContrast>45&&Math.max(r,Math.max(g,b))<205){darkLeft=Math.min(darkLeft,x);darkTop=Math.min(darkTop,y);darkRight=Math.max(darkRight,x);darkBottom=Math.max(darkBottom,y);darkCount++;}
        }
        if(foreground<25 || foreground>pixels.length*0.78) throw new IllegalArgumentException("抠图结果不可靠；请缩小选框并避开底纸边缘");
        // Print glints and dust on the backing paper are usually isolated from the ink.
        // Keep light eye details near dark artwork, discard distant bright specks.
        if(darkCount>30){
            int halo=Math.max(8,Math.round(Math.max(w,h)*.06f));
            minX=w;minY=h;maxX=0;maxY=0;foreground=0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=y*w+x,c=pixels[i],alpha=c>>>24;
                if(x<darkLeft-halo||x>darkRight+halo||y<darkTop-halo||y>darkBottom+halo){pixels[i]=c&0x00FFFFFF;continue;}
                if(alpha>35){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);foreground++;}
            }
        }
        if(foreground<25)throw new IllegalArgumentException("没有找到清晰的眼部图案；请调整选框");
        int pad=Math.max(3,Math.min(w,h)/35);
        minX=Math.max(0,minX-pad);minY=Math.max(0,minY-pad);maxX=Math.min(w-1,maxX+pad);maxY=Math.min(h-1,maxY+pad);
        Bitmap full=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); full.setPixels(pixels,0,w,0,0,w,h);
        Bitmap cropped=Bitmap.createBitmap(full,minX,minY,maxX-minX+1,maxY-minY+1); full.recycle();
        return new Result(cropped,new Rect(box.left+minX,box.top+minY,box.left+maxX+1,box.top+maxY+1),paperCount*3f/pixels.length);
    }
    /** Keep the editable preview in crop coordinates as the key strength changes. */
    static Result extractFixed(Bitmap source,Rect requested,int sensitivity){
        Rect box=ImageTools.clamp(requested,source);
        Result trimmed=extract(source,box,sensitivity);
        Bitmap fixed=Bitmap.createBitmap(box.width(),box.height(),Bitmap.Config.ARGB_8888);
        new Canvas(fixed).drawBitmap(trimmed.bitmap,trimmed.sourceBounds.left-box.left,trimmed.sourceBounds.top-box.top,null);
        trimmed.bitmap.recycle();
        return new Result(fixed,box,trimmed.blueFraction);
    }

    private static int[][] localPaperModel(int[] pixels,int w,int h,int br,int bg,int bb,
                                           int[] rowR,int[] rowG,int[] rowB,PaperColorModel model){
        final int tile=64;
        int cols=(w+tile-1)/tile,rows=(h+tile-1)/tile,cells=cols*rows;
        int[] histR=new int[cells*256],histG=new int[cells*256],histB=new int[cells*256],count=new int[cells];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=pixels[y*w+x];
            if(!paper(c,model)||distance(c,br,bg,bb)>=65)continue;
            int cell=(y/tile)*cols+x/tile,offset=cell*256;
            histR[offset+((c>>16)&255)]++;
            histG[offset+((c>>8)&255)]++;
            histB[offset+(c&255)]++;
            count[cell]++;
        }
        int[] gridR=new int[cells],gridG=new int[cells],gridB=new int[cells];
        for(int ty=0;ty<rows;ty++)for(int tx=0;tx<cols;tx++){
            int cell=ty*cols+tx,area=Math.min(tile,w-tx*tile)*Math.min(tile,h-ty*tile);
            if(count[cell]>=Math.max(12,area/12)){
                gridR[cell]=histMedian(histR,cell*256,count[cell]);
                gridG[cell]=histMedian(histG,cell*256,count[cell]);
                gridB[cell]=histMedian(histB,cell*256,count[cell]);
            }else{
                int yy=Math.min(h-1,ty*tile+tile/2);
                gridR[cell]=rowR[yy];gridG[cell]=rowG[yy];gridB[cell]=rowB[yy];
            }
        }
        int[][] result={new int[w*h],new int[w*h],new int[w*h]};
        int[][] grid={gridR,gridG,gridB};
        for(int y=0;y<h;y++){
            float gy=(y+.5f)/tile-.5f;
            int y0=Math.max(0,Math.min(rows-1,(int)Math.floor(gy))),y1=Math.min(rows-1,y0+1);
            float fy=Math.max(0f,Math.min(1f,gy-y0));
            for(int x=0;x<w;x++){
                float gx=(x+.5f)/tile-.5f;
                int x0=Math.max(0,Math.min(cols-1,(int)Math.floor(gx))),x1=Math.min(cols-1,x0+1);
                float fx=Math.max(0f,Math.min(1f,gx-x0));
                int a=y0*cols+x0,b=y0*cols+x1,c=y1*cols+x0,d=y1*cols+x1;
                for(int channel=0;channel<3;channel++){
                    int[] values=grid[channel];
                    float top=values[a]+(values[b]-values[a])*fx;
                    float bottom=values[c]+(values[d]-values[c])*fx;
                    result[channel][y*w+x]=Math.round(top+(bottom-top)*fy);
                }
            }
        }
        return result;
    }

    private static int histMedian(int[] hist,int offset,int count){
        int n=0;
        for(int value=0;value<256;value++){
            n+=hist[offset+value];
            if(n>count/2)return value;
        }
        return 255;
    }

    private static boolean[] protectedPrint(int[] pixels,int w,int h,int[] pr,int[] pg,int[] pb){
        boolean[] dark=new boolean[pixels.length],keep=new boolean[pixels.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int c=pixels[y*w+x];
            dark[y*w+x]=.2126f*(pr[y]-((c>>16)&255))+.7152f*(pg[y]-((c>>8)&255))+.0722f*(pb[y]-(c&255))>35;
        }
        // Thin detached brows do not enclose eye whites. Letting them vote
        // would preserve pale paper fragments in the gap above an eyelid.
        boolean[] seen=new boolean[dark.length],eyeArea=new boolean[dark.length];int[] queue=new int[dark.length];
        for(int start=0;start<dark.length;start++){
            if(!dark[start]||seen[start])continue;
            int head=0,tail=0,top=h,bottom=0,left=w,right=0;queue[tail++]=start;seen[start]=true;
            while(head<tail){
                int p=queue[head++],x=p%w,y=p/w;top=Math.min(top,y);bottom=Math.max(bottom,y);left=Math.min(left,x);right=Math.max(right,x);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int xx=x+dx,yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h)continue;
                    int next=yy*w+xx;if(dark[next]&&!seen[next]){seen[next]=true;queue[tail++]=next;}
                }
            }
            if(bottom-top<Math.max(6,h*.25f)||tail<w*h*.006f){for(int j=0;j<tail;j++)dark[queue[j]]=false;}
            else for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++)eyeArea[y*w+x]=true;
        }
        int reach=Math.max(8,Math.min(w/7,h/2));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            if(!eyeArea[y*w+x])continue;
            int c=pixels[y*w+x],dr=((c>>16)&255)-pr[y],dg=((c>>8)&255)-pg[y],db=(c&255)-pb[y];
            if(.2126f*dr+.7152f*dg+.0722f*db<5&&Math.max(dr,Math.max(dg,db))-Math.min(dr,Math.min(dg,db))<8)continue;
            int hits=0;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                if(dx==0&&dy==0)continue;
                for(int d=1;d<=reach;d++){
                    int xx=x+dx*d,yy=y+dy*d;
                    if(xx<0||xx>=w||yy<0||yy>=h)break;
                    if(dark[yy*w+xx]){hits++;break;}
                }
            }
            keep[y*w+x]=hits>=5;
        }
        return keep;
    }

    /** A glossy cyan sheet moves toward the light colour. Keep neutral print enclosed by dark ink. */
    private static boolean[] reflectionMask(int[] pixels,int w,int h,int[] paperR,int[] paperG,int[] paperB,PaperColorModel model){
        int n=pixels.length;
        boolean[] paper=new boolean[n],candidate=new boolean[n],dark=new boolean[n],remove=new boolean[n],seen=new boolean[n];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x,c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            int pr=paperR[y],pg=paperG[y],pb=paperB[y];
            paper[i]=paper(c,model)&&distance(c,pr,pg,pb)<36;
            int drDark=pr-r,dgDark=pg-g,dbDark=pb-b;
            int darkSpread=Math.max(drDark,Math.max(dgDark,dbDark))-Math.min(drDark,Math.min(dgDark,dbDark));
            dark[i]=(r<125&&g<125&&b<140)
                ||(drDark>42&&dgDark>42&&dbDark>42&&darkSpread<25)
                ||(model!=null&&.2126f*drDark+.7152f*dgDark+.0722f*dbDark>45);
            int vr=255-pr,vg=255-pg,vb=255-pb;
            float t=((r-pr)*vr+(g-pg)*vg+(b-pb)*vb)/(float)Math.max(1,vr*vr+vg*vg+vb*vb);
            if(t<.14f||paper[i]||r<pr+10||g<pg-20||b<pb-25)continue;
            float clamped=Math.min(1f,t);
            float dr=r-(pr+clamped*vr),dg=g-(pg+clamped*vg),db=b-(pb+clamped*vb);
            candidate[i]=dr*dr+dg*dg+db*db<35*35;
        }
        if(model!=null){
            // On neutral paper printed whites and glare have the same colour.
            // A colour-only decision would punch holes through the iris/eye white.
            // Protect highlights surrounded by artwork in several directions.
            int reach=Math.max(8,Math.min(w/7,h/2));
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=y*w+x;if(!candidate[i])continue;
                int hits=0;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    if(dx==0&&dy==0)continue;
                    for(int d=1;d<=reach;d++){
                        int xx=x+dx*d,yy=y+dy*d;
                        if(xx<0||xx>=w||yy<0||yy>=h)break;
                        if(dark[yy*w+xx]){hits++;break;}
                    }
                }
                if(hits>=4)candidate[i]=false;
            }
        }
        int[] queue=new int[n];
        for(int start=0;start<n;start++){
            if(!candidate[start]||seen[start])continue;
            int head=0,tail=0,paperEdge=0,inkEdge=0;
            queue[tail++]=start;seen[start]=true;
            while(head<tail){
                int i=queue[head++],x=i%w,y=i/w;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                    int xx=x+dx,yy=y+dy;
                    if(xx<0||xx>=w||yy<0||yy>=h||(dx==0&&dy==0))continue;
                    int next=yy*w+xx;
                    if(candidate[next]){if(!seen[next]){seen[next]=true;queue[tail++]=next;}}
                    else{if(paper[next])paperEdge++;if(dark[next])inkEdge++;}
                }
            }
            if(tail>=3&&paperEdge>=Math.max(6,inkEdge*2))
                for(int j=0;j<tail;j++)remove[queue[j]]=true;
        }
        return remove;
    }
    private static int distance(int c,int r,int g,int b){
        int dr=((c>>16)&255)-r,dg=((c>>8)&255)-g,db=(c&255)-b;
        return (int)Math.sqrt(dr*dr+dg*dg+db*db);
    }
    /** Alpha-aware unsharp mask; transparent pixels cannot bleed colour into an edge. */
    static Bitmap sharpen(Bitmap original,int strength){
        if(strength<=0)return original;
        int w=original.getWidth(),h=original.getHeight();
        int[] src=new int[w*h],out=new int[w*h];original.getPixels(src,0,w,0,0,w,h);
        // The old 1.25 maximum was barely visible once the small decal was
        // fitted into the dialog. Give the full slider useful range while
        // leaving tiny paper/photographic noise alone.
        float amount=(float)(2.6*Math.pow(Math.min(100,strength)/100.0,1.12));
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x,c=src[i],a=c>>>24;
            if(a==0){out[i]=0;continue;}
            float total=0,rr=0,gg=0,bb=0;
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){
                int xx=Math.max(0,Math.min(w-1,x+dx)),yy=Math.max(0,Math.min(h-1,y+dy));
                int neighbor=src[yy*w+xx],na=neighbor>>>24;
                float weight=(dx==0?2:1)*(dy==0?2:1)*na/255f;
                total+=weight;rr+=((neighbor>>16)&255)*weight;gg+=((neighbor>>8)&255)*weight;bb+=(neighbor&255)*weight;
            }
            int cr=(c>>16)&255,cg=(c>>8)&255,cb=c&255;
            // Apply a shared luminance adjustment so that red irises and pale
            // blue edge pixels do not acquire new colour fringes.
            float detail=.2126f*(cr-rr/total)+.7152f*(cg-gg/total)+.0722f*(cb-bb/total);
            float delta=Math.abs(detail)<2f?0:amount*detail*Math.min(1f,a/225f);
            int r=clamp(Math.round(cr+delta));
            int g=clamp(Math.round(cg+delta));
            int b=clamp(Math.round(cb+delta));
            out[i]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);result.setPixels(out,0,w,0,0,w,h);return result;
    }
    private static int clamp(int x) {return Math.max(0,Math.min(255,x));}
}
