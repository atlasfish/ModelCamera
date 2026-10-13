package com.modelcamera.app;

/** Robust colour mode of a pale, smooth backing. No blue hue is required. */
final class PaperColorModel {
    final int r,g,b;
    private PaperColorModel(int r,int g,int b){this.r=r;this.g=g;this.b=b;}

    static PaperColorModel learn(int[] pixels,int w,int h){
        int[] bins=new int[4096];
        for(int y=1;y<h-1;y+=3)for(int x=1;x<w-1;x+=3){
            int c=pixels[y*w+x],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(Math.min(r,Math.min(g,b))<105||Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>65)continue;
            // Thin mat lines and ink edges must not vote for the backing colour.
            if(delta(c,pixels[y*w+x-1])>18||delta(c,pixels[(y-1)*w+x])>18)continue;
            bins[(r/16)*256+(g/16)*16+b/16]++;
        }
        int best=-1,score=0;
        // Neighbouring bins vote together: white balance and JPEG quantisation
        // must not make a mode disappear at an arbitrary bin boundary.
        for(int r=6;r<16;r++)for(int g=6;g<16;g++)for(int b=6;b<16;b++){
            int n=0;
            for(int dr=-1;dr<=1;dr++)for(int dg=-1;dg<=1;dg++)for(int db=-1;db<=1;db++){
                int rr=r+dr,gg=g+dg,bb=b+db;
                if(rr>=0&&rr<16&&gg>=0&&gg<16&&bb>=0&&bb<16)n+=bins[rr*256+gg*16+bb];
            }
            if(n>score){score=n;best=r*256+g*16+b;}
        }
        if(best<0||score<Math.max(30,w*h/900))return null;
        int cr=(best/256)*16+8,cg=(best/16%16)*16+8,cb=(best%16)*16+8;
        int[] rs=new int[256],gs=new int[256],bs=new int[256];int n=0;
        for(int i=0;i<pixels.length;i+=3){
            int c=pixels[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            if(Math.abs(r-cr)>24||Math.abs(g-cg)>24||Math.abs(b-cb)>24)continue;
            rs[r]++;gs[g]++;bs[b]++;n++;
        }
        return n==0?null:new PaperColorModel(median(rs,n),median(gs,n),median(bs,n));
    }
    boolean matches(int c){
        int dr=((c>>16)&255)-r,dg=((c>>8)&255)-g,db=(c&255)-b;
        return dr*dr+dg*dg+db*db<45*45
            &&Math.max(dr,Math.max(dg,db))-Math.min(dr,Math.min(dg,db))<22;
    }
    private static int delta(int a,int b){return Math.max(Math.abs(((a>>16)&255)-((b>>16)&255)),Math.max(Math.abs(((a>>8)&255)-((b>>8)&255)),Math.abs((a&255)-(b&255))));}
    private static int median(int[] bins,int n){int sum=0;for(int i=0;i<256;i++){sum+=bins[i];if(sum>n/2)return i;}return 255;}
}
