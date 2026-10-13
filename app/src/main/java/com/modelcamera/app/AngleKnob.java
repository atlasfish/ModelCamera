package com.modelcamera.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.Locale;

/** A slow relative dial: 24 dp of vertical travel advances one half degree. */
final class AngleKnob extends LinearLayout {
    interface Listener {void changed(float angle);}
    private final Dial dial;
    private final Button minus,plus;
    private float value;
    private Listener listener;
    private Runnable dialStart,dialEnd;
    AngleKnob(Context context){
        super(context);setOrientation(HORIZONTAL);setGravity(android.view.Gravity.CENTER);
        minus=stepButton("↶ 0.5°");plus=stepButton("↷ 0.5°");dial=new Dial(context);
        addView(minus,new LayoutParams(0,dp(44),1));
        LayoutParams center=new LayoutParams(dp(100),dp(94));center.setMargins(dp(10),0,dp(10),0);addView(dial,center);
        addView(plus,new LayoutParams(0,dp(44),1));
        minus.setOnClickListener(v->change(value-.5f));plus.setOnClickListener(v->change(value+.5f));
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private Button stepButton(String label){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(0xFF18312E);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE8EFEC));return b;}
    void onChange(Listener listener){this.listener=listener;}
    void onDialGesture(Runnable start,Runnable end){dialStart=start;dialEnd=end;}
    void setValue(float angle){value=quantize(angle);dial.setContentDescription(String.format(Locale.CHINA,"旋转 %+.1f 度，上下滑动微调",value));dial.invalidate();}
    float value(){return value;}
    static float quantize(float angle){float a=Math.round(angle*2f)/2f;while(a>180)a-=360;while(a< -180)a+=360;return a;}
    private void change(float angle){if(!isEnabled())return;setValue(angle);if(listener!=null)listener.changed(value);}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);if(dial!=null){dial.setEnabled(enabled);minus.setEnabled(enabled);plus.setEnabled(enabled);setAlpha(enabled?1f:.45f);}}
    private final class Dial extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float lastY,remainder;
        Dial(Context c){super(c);setFocusable(true);}
        @Override protected void onDraw(Canvas c){
            float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.43f;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(5));p.setColor(0xFFE0E9E5);c.drawCircle(cx,cy,r,p);
            p.setStrokeWidth(dp(1));
            for(int i=0;i<36;i++){double a=Math.toRadians(i*10-90);p.setColor(i%3==0?0xFF6C8E87:0xFFCEDDD7);
                c.drawLine(cx+(float)Math.cos(a)*(r-dp(5)),cy+(float)Math.sin(a)*(r-dp(5)),cx+(float)Math.cos(a)*(r+dp(1)),cy+(float)Math.sin(a)*(r+dp(1)),p);}
            double a=Math.toRadians(value-90);p.setStyle(Paint.Style.FILL);p.setColor(0xFF2D8278);
            c.drawCircle(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r,dp(5),p);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(17));p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            c.drawText(String.format(Locale.CHINA,"%+.1f°",value),cx,cy+dp(4),p);
            p.setTextSize(dp(10));p.setColor(0xFF687775);c.drawText("↑  微调  ↓",cx,cy+dp(22),p);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            if(!isEnabled())return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastY=e.getY();remainder=0;if(dialStart!=null)dialStart.run();getParent().requestDisallowInterceptTouchEvent(true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                remainder+=e.getY()-lastY;lastY=e.getY();int steps=(int)(remainder/dp(24));
                if(steps!=0){remainder-=steps*dp(24);change(value+steps*.5f);}return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(dialEnd!=null)dialEnd.run();getParent().requestDisallowInterceptTouchEvent(false);return true;}
            return true;
        }
    }
}
