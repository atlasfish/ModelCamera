package com.modelcamera.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SubMenu;
import android.view.TextureView;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.ColorStateList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONObject;

public final class CameraActivity extends Activity implements SensorEventListener {
    static final String EXTRA_ASSIST="paste_assist";
    private static final int PICK_OVERLAY=202;
    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder previewRequest;
    private SensorManager sensors;
    private Sensor gravitySensor;
    private LevelView level;
    private AssistOverlayView assistOverlay;
    private boolean assistMode;
    private ImageButton torchButton;
    private ImageButton lensButton;
    private Button[] zoomButtons;
    private float zoomRatio=1f,maxDigitalZoom=1f,captureZoomRatio=1f;
    private boolean nativeZoomRatio,logicalMultiCamera;
    private String preferredCameraId;
    private boolean reopenAfterCameraClosed;
    private boolean softwareZoomNoticeShown;
    private Rect activeArray;
    private String cameraId;
    private int jpegRotation=90;
    private Size previewSize;
    private int cameraGeneration;
    private boolean taking,opening,resumed,flashAvailable,torchOn;
    private File capturedFile;
    private JSONObject capturedMetadata;
    private boolean captureFinished;
    private float sensorWidthMm;
    private float rawTiltX,rawTiltY,rawZ,calibrationX,calibrationY,calibrationZ;
    private boolean calibrated,samplingCalibration;
    private int calibrationSamples;
    private float calibrationSumX,calibrationSumY,calibrationSumZ;
    private float calibrationMinX,calibrationMaxX,calibrationMinY,calibrationMaxY;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        assistMode=getIntent().getBooleanExtra(EXTRA_ASSIST,false);
        android.content.SharedPreferences levelPrefs=getSharedPreferences("level_calibration",MODE_PRIVATE);
        calibrated=levelPrefs.getBoolean("enabled",false);
        calibrationX=levelPrefs.getFloat("x",0);calibrationY=levelPrefs.getFloat("y",0);calibrationZ=levelPrefs.getFloat("z",0);
        getWindow().setStatusBarColor(0xFF16211F);getWindow().setNavigationBarColor(0xFF16211F);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xFF16211F);
        preview=new TextureView(this);root.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        if(assistMode){assistOverlay=new AssistOverlayView(this);root.addView(assistOverlay,new FrameLayout.LayoutParams(-1,-1));}
        level=new LevelView(this);level.desktopCalibrated=calibrated;root.addView(level,new FrameLayout.LayoutParams(-1,-1));
        if(assistOverlay!=null)assistOverlay.guides=level;
        LinearLayout topBar=new LinearLayout(this);topBar.setGravity(Gravity.CENTER_VERTICAL);topBar.setPadding(dp(16),0,assistMode?dp(62):dp(16),0);
        FrameLayout.LayoutParams topParams=new FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP);topParams.topMargin=dp(14);root.addView(topBar,topParams);
        ImageButton back=cameraIcon(R.drawable.ic_arrow_back,"返回");topBar.addView(back,new LinearLayout.LayoutParams(dp(46),dp(46)));back.setOnClickListener(v->finish());
        TextView title=new TextView(this);title.setText(assistMode?"粘贴辅助":"现场拍摄");title.setTextColor(Color.WHITE);title.setTextSize(17);title.setTypeface(null,1);title.setGravity(Gravity.CENTER);topBar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        if(!assistMode){ImageButton guides=cameraIcon(R.drawable.ic_grid,"取景辅助线");topBar.addView(guides,new LinearLayout.LayoutParams(dp(46),dp(46)));guides.setOnClickListener(v->showCameraGuides(guides));}
        lensButton=cameraIcon(R.drawable.ic_camera_switch,"选择后置镜头");topBar.addView(lensButton,new LinearLayout.LayoutParams(dp(46),dp(46)));lensButton.setOnClickListener(v->showLensMenu(lensButton));
        torchButton=cameraIcon(R.drawable.ic_flash,"切换补光灯");topBar.addView(torchButton,new LinearLayout.LayoutParams(dp(46),dp(46)));torchButton.setOnClickListener(v->toggleTorch());updateTorchButton();
        Button shutter=new Button(this);shutter.setText("●");shutter.setTextSize(34);shutter.setTextColor(0xFF17312E);
        GradientDrawable shutterShape=new GradientDrawable();shutterShape.setShape(GradientDrawable.OVAL);shutterShape.setColor(0xFFE8FCF7);shutterShape.setStroke(dp(2),0xFFB8DED5);
        shutter.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44318378),shutterShape,null));shutter.setElevation(0);shutter.setStateListAnimator(null);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(82),dp(82),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);sp.bottomMargin=dp(22);root.addView(shutter,sp);shutter.setOnClickListener(v->takePhoto());
        TextView hint=new TextView(this);hint.setText("面雕与水贴同框 · 尽量平放");hint.setGravity(Gravity.CENTER);hint.setTextColor(0xE8FFFFFF);hint.setTextSize(12);hint.setShadowLayer(dp(2),0,dp(1),0xCC000000);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,dp(34),Gravity.BOTTOM);hp.bottomMargin=dp(170);root.addView(hint,hp);
        LinearLayout zoomBar=new LinearLayout(this);zoomBar.setGravity(Gravity.CENTER);zoomBar.setPadding(dp(3),dp(3),dp(3),dp(3));zoomBar.setBackground(cameraFill(0xCC203633,dp(23)));
        FrameLayout.LayoutParams zp=new FrameLayout.LayoutParams(dp(174),dp(42),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);zp.bottomMargin=dp(assistMode?139:115);root.addView(zoomBar,zp);
        zoomButtons=new Button[3];float[] zoomValues={1f,2f,4f};for(int i=0;i<zoomButtons.length;i++){final float selected=zoomValues[i];Button choice=new Button(this);choice.setAllCaps(false);choice.setText(((int)selected)+"×");choice.setTextSize(13);choice.setTypeface(null,1);choice.setTextColor(Color.WHITE);choice.setMinWidth(0);choice.setMinimumWidth(0);choice.setMinHeight(0);choice.setMinimumHeight(0);choice.setElevation(0);choice.setStateListAnimator(null);zoomBar.addView(choice,new LinearLayout.LayoutParams(0,-1,1));choice.setOnClickListener(v->setZoom(selected));zoomButtons[i]=choice;}updateZoomButtons();
        if(assistMode){
            shutter.setVisibility(View.GONE);hint.setVisibility(View.GONE);
            LinearLayout assistControls=new LinearLayout(this);assistControls.setOrientation(LinearLayout.VERTICAL);assistControls.setPadding(dp(12),dp(8),dp(12),dp(8));assistControls.setBackground(cameraFill(0xE8203633,dp(16)));
            FrameLayout.LayoutParams acp=new FrameLayout.LayoutParams(-1,dp(100),Gravity.BOTTOM);acp.setMargins(dp(16),0,dp(16),dp(24));root.addView(assistControls,acp);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);assistControls.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));
            Button importButton=new Button(this);importButton.setText("＋ 导入虚像");importButton.setAllCaps(false);importButton.setTextSize(13);importButton.setTextColor(0xFF153630);importButton.setTypeface(null,1);importButton.setBackground(cameraFill(0xFFDDF6EF,dp(9)));importButton.setElevation(0);importButton.setStateListAnimator(null);row.addView(importButton,new LinearLayout.LayoutParams(dp(126),dp(40)));importButton.setOnClickListener(v->pickOverlay());
            TextView modeLabel=new TextView(this);modeLabel.setText("虚像");modeLabel.setSingleLine(true);modeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);modeLabel.setGravity(Gravity.CENTER);modeLabel.setTextColor(0xD8FFFFFF);modeLabel.setTextSize(12);row.addView(modeLabel,new LinearLayout.LayoutParams(0,-1,1));
            ImageButton rotateButton=cameraIcon(R.drawable.ic_rotate_left_90,"旋转虚像");row.addView(rotateButton,new LinearLayout.LayoutParams(dp(42),dp(42)));rotateButton.setOnClickListener(v->showRotationTools(rotateButton));
            ImageButton toolsButton=cameraIcon(R.drawable.ic_more_horiz,"更多对位工具");row.addView(toolsButton,new LinearLayout.LayoutParams(dp(42),dp(42)));toolsButton.setOnClickListener(v->showAssistTools(toolsButton,modeLabel));
            LinearLayout opacityRow=new LinearLayout(this);opacityRow.setGravity(Gravity.CENTER_VERTICAL);assistControls.addView(opacityRow,new LinearLayout.LayoutParams(-1,dp(42)));
            TextView opacityLabel=new TextView(this);opacityLabel.setTextColor(0xDDFFFFFF);opacityLabel.setTextSize(12);opacityLabel.setText("透明度 50%");opacityRow.addView(opacityLabel,new LinearLayout.LayoutParams(dp(87),-2));
            SeekBar opacity=new SeekBar(this);opacity.setMax(100);opacity.setProgress(50);opacity.setContentDescription("虚像透明度");opacityRow.addView(opacity,new LinearLayout.LayoutParams(0,dp(42),1));
            opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user){assistOverlay.opacity=progress/100f;assistOverlay.invalidate();opacityLabel.setText("透明度 "+progress+"%");}}
                public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
            });
            ImageButton hideUi=cameraIcon(R.drawable.ic_visibility_off,"隐藏界面");FrameLayout.LayoutParams hideParams=new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP|Gravity.RIGHT);hideParams.setMargins(0,dp(15),dp(16),0);root.addView(hideUi,hideParams);
            final boolean[] hidden={false};hideUi.setOnClickListener(v->{hidden[0]=!hidden[0];
                int visibility=hidden[0]?View.GONE:View.VISIBLE;
                topBar.setVisibility(visibility);assistControls.setVisibility(visibility);zoomBar.setVisibility(visibility);
                level.minimalUi=hidden[0];level.invalidate();hideUi.setImageResource(hidden[0]?R.drawable.ic_visibility:R.drawable.ic_visibility_off);hideUi.setContentDescription(hidden[0]?"显示界面":"隐藏界面");
            });
        }
        root.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            if(right-left!=oldRight-oldLeft||bottom-top!=oldBottom-oldTop){level.postInvalidate();if(assistOverlay!=null)assistOverlay.postInvalidate();}
        });
        setContentView(root);
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);gravitySensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);if(gravitySensor==null)gravitySensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){openCamera();}public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){updatePreviewTransform(w,h);level.postInvalidate();}public boolean onSurfaceTextureDestroyed(SurfaceTexture s){return true;}public void onSurfaceTextureUpdated(SurfaceTexture s){}});
        if(preview.isAvailable())openCamera();
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable cameraFill(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(radius);return shape;}
    private ImageButton cameraIcon(int resource,String description){ImageButton button=new ImageButton(this);button.setImageResource(resource);button.setImageTintList(ColorStateList.valueOf(Color.WHITE));button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);button.setPadding(dp(11),dp(11),dp(11),dp(11));button.setBackground(cameraFill(0xAB203633,dp(12)));button.setContentDescription(description);button.setTooltipText(description);button.setElevation(0);button.setStateListAnimator(null);return button;}
    private void showCameraGuides(View anchor){PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,1,0,"九宫格").setCheckable(true).setChecked(level.showGrid);
        menu.getMenu().add(0,2,1,"水平垂直十字线").setCheckable(true).setChecked(level.showCross);
        menu.getMenu().add(0,3,2,"按桌面校准水平");
        if(calibrated)menu.getMenu().add(0,4,3,"恢复重力水平");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 1:level.showGrid=!level.showGrid;break;
            case 2:level.showCross=!level.showCross;break;
            case 3:showLevelCalibration();break;
            case 4:resetLevelCalibration();break;
        }level.invalidate();return true;});menu.show();
    }
    private void showAssistTools(View anchor,TextView modeLabel){PopupMenu menu=new PopupMenu(this,anchor);
        SubMenu target=menu.getMenu().addSubMenu("选择操作对象");target.add(0,1,0,"虚像");target.add(0,2,1,"参考网格");
        for(int i=0;i<level.horizontalGuides.size();i++)target.add(0,1000+i,2+i,"水平线 "+(i+1)).setCheckable(true).setChecked(assistOverlay.guideMode==AssistOverlayView.MODE_H&&assistOverlay.selectedGuideIndex==i);
        for(int i=0;i<level.verticalGuides.size();i++)target.add(0,2000+i,100+i,"垂直线 "+(i+1)).setCheckable(true).setChecked(assistOverlay.guideMode==AssistOverlayView.MODE_V&&assistOverlay.selectedGuideIndex==i);
        SubMenu guides=menu.getMenu().addSubMenu("参考线与网格");guides.add(0,3,0,"新增水平参考线");guides.add(0,4,1,"新增垂直参考线");
        guides.add(0,5,2,"显示 / 隐藏九宫格");guides.add(0,6,3,"显示 / 隐藏十字线");
        guides.add(0,14,4,level.showGuideDistances?"隐藏间距数字":"显示间距数字");
        guides.add(0,13,5,"删除所选参考线");guides.add(0,10,6,"清除自定义参考线");
        menu.getMenu().add(0,9,3,"虚像复位");
        menu.getMenu().add(0,11,4,"按桌面校准水平");
        if(calibrated)menu.getMenu().add(0,12,5,"恢复重力水平");
        menu.setOnMenuItemClickListener(item->{int id=item.getItemId();
            if(id>=1000&&id<2000){int index=id-1000;assistOverlay.setGuideMode(AssistOverlayView.MODE_H,index);modeLabel.setText("水平线 "+(index+1));return true;}
            if(id>=2000&&id<3000){int index=id-2000;assistOverlay.setGuideMode(AssistOverlayView.MODE_V,index);modeLabel.setText("垂直线 "+(index+1));return true;}
            switch(id){
            case 1:assistOverlay.setGuideMode(AssistOverlayView.MODE_IMAGE);modeLabel.setText("虚像");break;
            case 2:level.showGrid=true;assistOverlay.setGuideMode(AssistOverlayView.MODE_GRID);modeLabel.setText("网格");level.invalidate();break;
            case 3:level.horizontalGuides.add(.5f);assistOverlay.setGuideMode(AssistOverlayView.MODE_H,level.horizontalGuides.size()-1);modeLabel.setText("水平线 "+level.horizontalGuides.size());level.invalidate();break;
            case 4:level.verticalGuides.add(.5f);assistOverlay.setGuideMode(AssistOverlayView.MODE_V,level.verticalGuides.size()-1);modeLabel.setText("垂直线 "+level.verticalGuides.size());level.invalidate();break;
            case 5:level.showGrid=!level.showGrid;level.invalidate();break;
            case 6:level.showCross=!level.showCross;level.invalidate();break;
            case 9:assistOverlay.reset();break;
            case 10:level.horizontalGuides.clear();level.verticalGuides.clear();level.invalidate();assistOverlay.setGuideMode(AssistOverlayView.MODE_IMAGE);modeLabel.setText("虚像");break;
            case 11:showLevelCalibration();break;
            case 12:resetLevelCalibration();break;
            case 13:if(assistOverlay.deleteSelectedGuide()){modeLabel.setText("虚像");}else Toast.makeText(this,"先在“选择操作对象”中选择参考线",Toast.LENGTH_SHORT).show();break;
            case 14:level.showGuideDistances=!level.showGuideDistances;level.invalidate();break;
        }return true;});menu.show();
    }
    private void showRotationTools(View anchor){
        if(assistOverlay==null)return;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(8),dp(6),dp(8),dp(6));content.setBackground(cameraFill(0xFFF8FAF9,dp(14)));
        AngleKnob knob=new AngleKnob(this);knob.setValue(assistOverlay.angle);knob.onChange(angle->assistOverlay.setAngle(angle));content.addView(knob,new LinearLayout.LayoutParams(-1,dp(94)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);content.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));
        ImageButton left=cameraIcon(R.drawable.ic_rotate_left_90,"向左旋转 90 度");left.setImageTintList(ColorStateList.valueOf(0xFF18312E));left.setBackground(cameraFill(0xFFE8EFEC,dp(8)));
        ImageButton right=cameraIcon(R.drawable.ic_rotate_right_90,"向右旋转 90 度");right.setImageTintList(ColorStateList.valueOf(0xFF18312E));right.setBackground(cameraFill(0xFFE8EFEC,dp(8)));
        Button zero=new Button(this);zero.setText("角度归零");zero.setAllCaps(false);zero.setTextSize(12);zero.setTextColor(0xFF18312E);zero.setBackground(cameraFill(0xFFE8EFEC,dp(8)));zero.setElevation(0);
        row.addView(left,new LinearLayout.LayoutParams(dp(48),dp(40)));LinearLayout.LayoutParams center=new LinearLayout.LayoutParams(0,dp(40),1);center.setMargins(dp(8),0,dp(8),0);row.addView(zero,center);row.addView(right,new LinearLayout.LayoutParams(dp(48),dp(40)));
        left.setOnClickListener(v->{assistOverlay.rotateBy(-90f);knob.setValue(assistOverlay.angle);});
        right.setOnClickListener(v->{assistOverlay.rotateBy(90f);knob.setValue(assistOverlay.angle);});
        zero.setOnClickListener(v->{assistOverlay.setAngle(0);knob.setValue(0);});
        PopupWindow popup=new PopupWindow(content,getResources().getDisplayMetrics().widthPixels-dp(32),dp(148),true);popup.setBackgroundDrawable(cameraFill(0xFFF8FAF9,dp(14)));popup.setElevation(dp(10));popup.setOutsideTouchable(true);
        popup.showAtLocation(anchor,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,dp(205));
    }
    private void showLevelCalibration(){
        new AlertDialog.Builder(this).setTitle("按桌面校准水平")
            .setMessage("点击开始后，将手机屏幕朝下平贴目标桌面，保持顶部朝向不变。3 秒后自动采样约 1 秒；拍摄时尽量保持手机顶部同向。")
            .setPositiveButton("开始校准",(dialog,which)->startLevelCalibration())
            .setNegativeButton("取消",null).show();
    }
    private void startLevelCalibration(){
        if(gravitySensor==null){level.setMessage("设备没有水平传感器");return;}
        samplingCalibration=false;
        level.setMessage("3 秒内将手机屏幕朝下放在桌面");
        level.postDelayed(()->{
            if(!resumed)return;
            calibrationSamples=0;calibrationSumX=calibrationSumY=calibrationSumZ=0;
            calibrationMinX=calibrationMinY=Float.POSITIVE_INFINITY;
            calibrationMaxX=calibrationMaxY=Float.NEGATIVE_INFINITY;
            samplingCalibration=true;level.setMessage("正在采样，请保持手机静止");
            level.postDelayed(this::finishLevelCalibration,1200);
        },3000);
    }
    private void finishLevelCalibration(){
        samplingCalibration=false;
        if(calibrationSamples<5||Math.abs(calibrationSumZ/calibrationSamples)<7f||
            calibrationMaxX-calibrationMinX>1f||calibrationMaxY-calibrationMinY>1f){
            level.setMessage("校准未完成：请平放并保持静止后重试");level.postDelayed(()->level.setMessage(null),3500);return;
        }
        calibrationX=calibrationSumX/calibrationSamples;calibrationY=calibrationSumY/calibrationSamples;calibrationZ=calibrationSumZ/calibrationSamples;calibrated=true;
        level.desktopCalibrated=true;
        getSharedPreferences("level_calibration",MODE_PRIVATE).edit().putBoolean("enabled",true)
            .putFloat("x",calibrationX).putFloat("y",calibrationY).putFloat("z",calibrationZ).apply();
        level.resetTiltSmoothing();level.setMessage("桌面校准完成 · 当前桌面为零点");level.postDelayed(()->level.setMessage(null),3500);
    }
    private void resetLevelCalibration(){
        calibrated=false;calibrationX=calibrationY=calibrationZ=0;
        level.desktopCalibrated=false;
        getSharedPreferences("level_calibration",MODE_PRIVATE).edit().clear().apply();
        level.resetTiltSmoothing();level.setMessage("已恢复重力水平");level.postDelayed(()->level.setMessage(null),2800);
    }
    private void updateZoomButtons(){if(zoomButtons==null)return;float[] values={1f,2f,4f};for(int i=0;i<zoomButtons.length;i++){
        Button button=zoomButtons[i];
        button.setBackground(cameraFill(Math.abs(zoomRatio-values[i])<.05f?0xFF2D8278:Color.TRANSPARENT,dp(19)));
        button.setContentDescription(((int)values[i])+"倍变焦"+(values[i]>maxDigitalZoom+.01f?"，软件放大，画质可能下降":""));
    }}
    private void setZoom(float value){zoomRatio=value;updateZoomButtons();updatePreviewTransform(preview.getWidth(),preview.getHeight());applyTorchToPreview();
        if(camera!=null&&value>maxDigitalZoom+.01f&&!softwareZoomNoticeShown){softwareZoomNoticeShown=true;Toast.makeText(this,"当前镜头使用软件放大，照片清晰度可能下降",Toast.LENGTH_SHORT).show();}
    }
    private ArrayList<String> backCameraIds(CameraManager manager)throws CameraAccessException{
        ArrayList<String> ids=new ArrayList<>();
        for(String id:manager.getCameraIdList()){
            CameraCharacteristics chars=manager.getCameraCharacteristics(id);
            Integer facing=chars.get(CameraCharacteristics.LENS_FACING);
            StreamConfigurationMap streams=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK&&streams!=null&&
                streams.getOutputSizes(android.graphics.ImageFormat.JPEG)!=null&&
                streams.getOutputSizes(SurfaceTexture.class)!=null)ids.add(id);
        }
        return ids;
    }
    private String lensLabel(CameraManager manager,String id){
        try{
            CameraCharacteristics chars=manager.getCameraCharacteristics(id);
            float[] focal=chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF sensor=chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if(focal!=null&&focal.length>0&&sensor!=null&&sensor.getWidth()>0)
                return String.format(Locale.CHINA,"镜头 %s · 约 %.0f mm",id,focal[0]*36f/sensor.getWidth());
        }catch(Exception ignored){}
        return "镜头 "+id;
    }
    private void showLensMenu(View anchor){
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            ArrayList<String> ids=backCameraIds(manager);
            PopupMenu menu=new PopupMenu(this,anchor);
            if(ids.isEmpty()){menu.getMenu().add("没有可用后置镜头").setEnabled(false);}
            else{
                for(int i=0;i<ids.size();i++){
                    String id=ids.get(i);
                    menu.getMenu().add(0,i+1,i,lensLabel(manager,id)).setCheckable(true).setChecked(id.equals(cameraId));
                }
                if(ids.size()==1)menu.getMenu().add("设备只开放一个可单独选择的后置镜头").setEnabled(false);
                if(nativeZoomRatio&&logicalMultiCamera&&maxDigitalZoom>1.01f)menu.getMenu().add("变焦时系统可能自动切换实际镜头").setEnabled(false);
            }
            menu.setOnMenuItemClickListener(item->{int index=item.getItemId()-1;
                if(index<0||index>=ids.size())return true;
                String next=ids.get(index);if(next.equals(cameraId))return true;
                if(taking){Toast.makeText(this,"拍摄完成后再切换镜头",Toast.LENGTH_SHORT).show();return true;}
                if(opening){Toast.makeText(this,"镜头启动中，请稍后再切换",Toast.LENGTH_SHORT).show();return true;}
                preferredCameraId=next;zoomRatio=1f;softwareZoomNoticeShown=false;
                reopenAfterCameraClosed=camera!=null;closeCamera();
                if(!reopenAfterCameraClosed)openCamera();
                Toast.makeText(this,"已选择 "+lensLabel(manager,next),Toast.LENGTH_SHORT).show();return true;
            });menu.show();
        }catch(Exception e){Toast.makeText(this,"无法读取镜头列表",Toast.LENGTH_SHORT).show();}
    }
    static Rect zoomCrop(Rect sensor,float zoom){float safe=Math.max(1f,zoom);int width=Math.max(2,Math.min(sensor.width(),Math.round(sensor.width()/safe)));int height=Math.max(2,Math.min(sensor.height(),Math.round(sensor.height()/safe)));
        int left=sensor.left+(sensor.width()-width)/2,top=sensor.top+(sensor.height()-height)/2;return new Rect(left,top,left+width,top+height);
    }
    private void pickOverlay(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,PICK_OVERLAY);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_OVERLAY&&result==RESULT_OK&&data!=null&&data.getData()!=null){
        try(InputStream in=getContentResolver().openInputStream(data.getData())){Bitmap bitmap=BitmapFactory.decodeStream(in);if(bitmap==null)throw new IllegalArgumentException("无法读取图片");
            if(Math.max(bitmap.getWidth(),bitmap.getHeight())>2048){float ratio=2048f/Math.max(bitmap.getWidth(),bitmap.getHeight());Bitmap resized=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*ratio),Math.round(bitmap.getHeight()*ratio),true);bitmap.recycle();bitmap=resized;}
            assistOverlay.setBitmap(bitmap);
        }catch(Exception e){level.setMessage("导入失败："+e.getMessage());}
    }}
    @Override protected void onResume(){super.onResume();resumed=true;if(gravitySensor!=null)sensors.registerListener(this,gravitySensor,SensorManager.SENSOR_DELAY_UI);if(preview!=null&&preview.isAvailable()&&camera==null&&!opening)openCamera();}
    @Override protected void onPause(){resumed=false;reopenAfterCameraClosed=false;super.onPause();sensors.unregisterListener(this);closeCamera();}
    private void openCamera(){
        if(!resumed||opening||camera!=null)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},101);return;}
        opening=true;
        final int generation=++cameraGeneration;
        try{
            CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
            String[] ids=manager.getCameraIdList();if(ids.length==0){opening=false;level.setMessage("设备没有可用相机");return;}
            cameraId=ids[0];boolean foundBack=false;
            for(String id:ids){
                CameraCharacteristics option=manager.getCameraCharacteristics(id);
                Integer facing=option.get(CameraCharacteristics.LENS_FACING);
                if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK){
                    if(!foundBack){cameraId=id;foundBack=true;}
                    if(Boolean.TRUE.equals(option.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))){cameraId=id;break;}
                }
            }
            if(preferredCameraId!=null){
                for(String id:ids)if(id.equals(preferredCameraId)){
                    Integer facing=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if(facing!=null&&facing==CameraCharacteristics.LENS_FACING_BACK)cameraId=id;
                    break;
                }
            }
            CameraCharacteristics chars=manager.getCameraCharacteristics(cameraId);Integer rotation=chars.get(CameraCharacteristics.SENSOR_ORIENTATION);if(rotation!=null)jpegRotation=rotation;
            android.util.SizeF sensorSize=chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);sensorWidthMm=sensorSize==null?0:sensorSize.getWidth();
            activeArray=chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maximum=chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);maxDigitalZoom=maximum==null||!Float.isFinite(maximum)?1f:Math.max(1f,maximum);
            nativeZoomRatio=false;
            logicalMultiCamera=false;
            int[] capabilities=chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if(Build.VERSION.SDK_INT>=28&&capabilities!=null)for(int capability:capabilities)
                if(capability==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)logicalMultiCamera=true;
            if(Build.VERSION.SDK_INT>=30){Range<Float> range=chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if(range!=null&&range.getLower()<=1f&&range.getUpper()>=1f){nativeZoomRatio=true;maxDigitalZoom=Math.max(1f,range.getUpper());}
            }
            if(activeArray==null)maxDigitalZoom=1f;
            updateZoomButtons();
            if(lensButton!=null){String label="选择后置镜头，当前 "+lensLabel(manager,cameraId);lensButton.setContentDescription(label);lensButton.setTooltipText(label);}
            flashAvailable=Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));torchOn=false;updateTorchButton();
            StreamConfigurationMap streams=chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(streams==null)throw new IllegalStateException("相机没有可用的输出尺寸");
            Size[] jpg=streams.getOutputSizes(android.graphics.ImageFormat.JPEG);
            Size[] availablePreview=streams.getOutputSizes(SurfaceTexture.class);
            if(jpg==null||jpg.length==0||availablePreview==null||availablePreview.length==0)throw new IllegalStateException("相机没有可用的预览尺寸");
            Size best=chooseJpegSize(jpg);
            previewSize=choosePreviewSize(availablePreview,best);
            updatePreviewTransform(preview.getWidth(),preview.getHeight());
            reader=ImageReader.newInstance(best.getWidth(),best.getHeight(),android.graphics.ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(r->{try(Image image=r.acquireLatestImage()){if(image==null)return;ByteBuffer b=image.getPlanes()[0].getBuffer();byte[] data=new byte[b.remaining()];b.get(data);capturedFile=new File(getFilesDir(),"capture_"+System.currentTimeMillis()+".jpg");try(FileOutputStream out=new FileOutputStream(capturedFile)){out.write(data);}finishCaptureIfReady();}catch(Exception ex){runOnUiThread(()->level.setMessage("保存失败，请重试"));taking=false;}},null);
            manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){if(!resumed||generation!=cameraGeneration){c.close();return;}opening=false;camera=c;updateTorchButton();startPreview();}
                @Override public void onDisconnected(CameraDevice c){c.close();if(generation!=cameraGeneration)return;opening=false;camera=null;torchOn=false;updateTorchButton();}
                @Override public void onError(CameraDevice c,int error){c.close();if(generation!=cameraGeneration)return;opening=false;camera=null;torchOn=false;updateTorchButton();level.setMessage("相机启动失败");}
                @Override public void onClosed(CameraDevice c){if(reopenAfterCameraClosed&&resumed){reopenAfterCameraClosed=false;preview.post(CameraActivity.this::openCamera);}}
            },null);
        }catch(Exception e){opening=false;level.setMessage("相机不可用："+e.getMessage());}
    }
    private void updateTorchButton(){
        if(torchButton==null)return;
        boolean ready=flashAvailable&&camera!=null;
        String label=!flashAvailable?"当前镜头无补光灯":torchOn?"关闭补光灯":"打开补光灯";
        torchButton.setContentDescription(label);torchButton.setTooltipText(label);torchButton.setImageTintList(ColorStateList.valueOf(torchOn?0xFF75E3CE:Color.WHITE));
        torchButton.setEnabled(ready);
        torchButton.setAlpha(ready?1f:.4f);
        torchButton.setBackground(cameraFill(torchOn?0xD9235B54:0xAB203633,dp(12)));
    }
    private void toggleTorch(){
        if(!flashAvailable||camera==null)return;
        torchOn=!torchOn;applyTorchToPreview();updateTorchButton();
    }
    private void applyTorchToPreview(){
        if(session==null||previewRequest==null)return;
        try{
            previewRequest.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
            if(flashAvailable)previewRequest.set(CaptureRequest.FLASH_MODE,torchOn?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);
            if(nativeZoomRatio&&Build.VERSION.SDK_INT>=30){previewRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO,Math.min(zoomRatio,maxDigitalZoom));
                if(activeArray!=null)previewRequest.set(CaptureRequest.SCALER_CROP_REGION,activeArray);
            }else if(activeArray!=null)previewRequest.set(CaptureRequest.SCALER_CROP_REGION,zoomCrop(activeArray,Math.min(zoomRatio,maxDigitalZoom)));
            session.setRepeatingRequest(previewRequest.build(),null,null);
        }catch(Exception e){
            torchOn=false;zoomRatio=1f;updateZoomButtons();updatePreviewTransform(preview.getWidth(),preview.getHeight());
            try{if(flashAvailable)previewRequest.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);if(nativeZoomRatio&&Build.VERSION.SDK_INT>=30)previewRequest.set(CaptureRequest.CONTROL_ZOOM_RATIO,1f);if(activeArray!=null)previewRequest.set(CaptureRequest.SCALER_CROP_REGION,activeArray);session.setRepeatingRequest(previewRequest.build(),null,null);}catch(Exception ignored){}
            level.setMessage("相机参数更新失败，已回到 1×");
        }
        updateTorchButton();
    }
    private void startPreview(){if(camera==null||!preview.isAvailable())return;try{
        SurfaceTexture texture=preview.getSurfaceTexture();texture.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());Surface surface=new Surface(texture);
        previewRequest=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);previewRequest.addTarget(surface);previewRequest.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        camera.createCaptureSession(Arrays.asList(surface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
            @Override public void onConfigured(CameraCaptureSession s){if(camera==null||s.getDevice()!=camera){s.close();return;}session=s;applyTorchToPreview();}
            @Override public void onConfigureFailed(CameraCaptureSession s){level.setMessage("预览启动失败");}
        },null);
    }catch(Exception e){level.setMessage("预览不可用");}}
    private static Size chooseJpegSize(Size[] sizes){
        Size best=null;long bestArea=-1;
        for(Size s:sizes){
            if(s.getWidth()>4000||s.getHeight()>4000)continue;
            // A 4:3 still frame retains the full sensor area on common main cameras.
            if(Math.abs(s.getWidth()/(float)s.getHeight()-4f/3f)>.03f)continue;
            long area=(long)s.getWidth()*s.getHeight();if(area>bestArea){best=s;bestArea=area;}
        }
        if(best!=null)return best;
        for(Size s:sizes){
            if(s.getWidth()>4000||s.getHeight()>4000)continue;
            long area=(long)s.getWidth()*s.getHeight();if(area>bestArea){best=s;bestArea=area;}
        }
        if(best!=null)return best;
        for(Size s:sizes){long area=(long)s.getWidth()*s.getHeight();if(area>bestArea){best=s;bestArea=area;}}
        return best;
    }
    private static Size choosePreviewSize(Size[] sizes,Size still){
        Size best=sizes[0];double bestScore=Double.MAX_VALUE;
        double ratio=still.getWidth()/(double)still.getHeight(),targetArea=1280d*960d;
        for(Size s:sizes){
            double area=(double)s.getWidth()*s.getHeight();
            double score=Math.abs(s.getWidth()/(double)s.getHeight()-ratio)*10+Math.abs(Math.log(area/targetArea));
            if(area>1920d*1440d)score+=1;
            if(score<bestScore){best=s;bestScore=score;}
        }
        return best;
    }
    private void updatePreviewTransform(int width,int height){
        if(previewSize==null||width<=0||height<=0)return;
        boolean quarterTurn=jpegRotation==90||jpegRotation==270;
        int imageWidth=quarterTurn?previewSize.getHeight():previewSize.getWidth();
        int imageHeight=quarterTurn?previewSize.getWidth():previewSize.getHeight();
        // TextureView otherwise stretches a 4:3 camera stream into the tall phone display.
        Matrix transform=centerCropTransform(width,height,imageWidth,imageHeight);
        float extra=zoomRatio/Math.min(zoomRatio,maxDigitalZoom);
        if(extra>1.001f)transform.postScale(extra,extra,width/2f,height/2f);
        preview.setTransform(transform);
    }
    static Matrix centerCropTransform(int width,int height,int imageWidth,int imageHeight){
        Matrix transform=new Matrix();
        float viewRatio=width/(float)height,imageRatio=imageWidth/(float)imageHeight;
        if(imageRatio>viewRatio)transform.setScale(imageRatio/viewRatio,1f,width/2f,height/2f);
        else transform.setScale(1f,viewRatio/imageRatio,width/2f,height/2f);
        return transform;
    }
    private void takePhoto(){if(taking||camera==null||session==null)return;taking=true;captureZoomRatio=zoomRatio;captureFinished=false;capturedFile=null;capturedMetadata=null;try{CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);if(flashAvailable)b.set(CaptureRequest.FLASH_MODE,torchOn?CaptureRequest.FLASH_MODE_TORCH:CaptureRequest.FLASH_MODE_OFF);if(nativeZoomRatio&&Build.VERSION.SDK_INT>=30){b.set(CaptureRequest.CONTROL_ZOOM_RATIO,Math.min(captureZoomRatio,maxDigitalZoom));if(activeArray!=null)b.set(CaptureRequest.SCALER_CROP_REGION,activeArray);}else if(activeArray!=null)b.set(CaptureRequest.SCALER_CROP_REGION,zoomCrop(activeArray,Math.min(captureZoomRatio,maxDigitalZoom)));b.set(CaptureRequest.JPEG_ORIENTATION,jpegRotation);session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){
        @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest request,TotalCaptureResult result){capturedMetadata=captureMetadata(result);finishCaptureIfReady();}
        @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest request,android.hardware.camera2.CaptureFailure failure){taking=false;level.setMessage("拍摄失败，请重试");}
    },null);}catch(Exception e){taking=false;level.setMessage("拍摄失败，请重试");}}
    private JSONObject captureMetadata(TotalCaptureResult result){JSONObject info=new JSONObject();try{
        info.put("cameraId",cameraId);info.put("capturedAtEpochMs",System.currentTimeMillis());
        info.put("sensorWidthMm",sensorWidthMm);
        info.put("digitalZoom",captureZoomRatio);
        if(Build.VERSION.SDK_INT>=29){String physical=result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);if(physical!=null)info.put("activePhysicalCameraId",physical);}
        Float focal=result.get(CaptureResult.LENS_FOCAL_LENGTH);if(focal!=null){info.put("focalLengthMm",focal);if(sensorWidthMm>0)info.put("equivalentFocalLengthMm",focal*36f/sensorWidthMm);}
        Integer awb=result.get(CaptureResult.CONTROL_AWB_MODE);if(awb!=null)info.put("whiteBalanceMode",awb==CaptureResult.CONTROL_AWB_MODE_OFF?"manual":awb==CaptureResult.CONTROL_AWB_MODE_AUTO?"auto":"preset_"+awb);
        android.hardware.camera2.params.RggbChannelVector gains=result.get(CaptureResult.COLOR_CORRECTION_GAINS);if(gains!=null)info.put("whiteBalanceGains",new org.json.JSONArray().put(gains.getRed()).put(gains.getGreenEven()).put(gains.getGreenOdd()).put(gains.getBlue()));
        Integer iso=result.get(CaptureResult.SENSOR_SENSITIVITY);if(iso!=null)info.put("iso",iso);
        Long exposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);if(exposure!=null)info.put("exposureTimeNs",exposure);
        info.put("torchOn",torchOn);
    }catch(Exception ignored){}return info;}
    private void finishCaptureIfReady(){if(captureFinished||capturedFile==null||capturedMetadata==null)return;captureFinished=true;
        if(captureZoomRatio>maxDigitalZoom+.01f)try{applySoftwareZoom(capturedFile,captureZoomRatio/maxDigitalZoom);}catch(Exception error){taking=false;runOnUiThread(()->level.setMessage("放大照片处理失败，请重拍"));return;}
        try{
        File meta=new File(capturedFile.getAbsolutePath()+".json");try(FileOutputStream out=new FileOutputStream(meta)){out.write(capturedMetadata.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        android.media.ExifInterface exif=new android.media.ExifInterface(capturedFile.getAbsolutePath());
        if(capturedMetadata.has("focalLengthMm")){int numerator=Math.round((float)capturedMetadata.getDouble("focalLengthMm")*1000);exif.setAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH,numerator+"/1000");}
        exif.setAttribute(android.media.ExifInterface.TAG_WHITE_BALANCE,"manual".equals(capturedMetadata.optString("whiteBalanceMode"))?"1":"0");exif.saveAttributes();
    }catch(Exception e){level.setMessage("照片已保存，元数据写入失败");}
        Intent result=new Intent();result.putExtra("path",capturedFile.getAbsolutePath());setResult(RESULT_OK,result);finish();}
    static void applySoftwareZoom(File jpeg,float extra)throws Exception{
        if(extra<=1.001f)return;
        android.media.ExifInterface originalExif=new android.media.ExifInterface(jpeg.getAbsolutePath());
        String orientation=originalExif.getAttribute(android.media.ExifInterface.TAG_ORIENTATION);
        BitmapRegionDecoder decoder=BitmapRegionDecoder.newInstance(jpeg.getAbsolutePath(),false);
        Bitmap cropped=null;
        try{int width=decoder.getWidth(),height=decoder.getHeight();int cropWidth=Math.max(2,Math.round(width/extra)),cropHeight=Math.max(2,Math.round(height/extra));
            Rect crop=new Rect((width-cropWidth)/2,(height-cropHeight)/2,(width+cropWidth)/2,(height+cropHeight)/2);
            cropped=decoder.decodeRegion(crop,new BitmapFactory.Options());
            if(cropped==null)throw new IllegalStateException("无法完成拍照放大");
            File temporary=new File(jpeg.getAbsolutePath()+".zoom");
            try(FileOutputStream output=new FileOutputStream(temporary)){if(!cropped.compress(Bitmap.CompressFormat.JPEG,95,output))throw new IllegalStateException("无法保存放大照片");}
            if(orientation!=null){android.media.ExifInterface exif=new android.media.ExifInterface(temporary.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,orientation);exif.saveAttributes();}
            Files.move(temporary.toPath(),jpeg.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }finally{if(cropped!=null)cropped.recycle();decoder.recycle();}
    }
    private void closeCamera(){cameraGeneration++;opening=false;torchOn=false;if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}previewRequest=null;flashAvailable=false;activeArray=null;nativeZoomRatio=false;logicalMultiCamera=false;maxDigitalZoom=1f;updateTorchButton();updateZoomButtons();}
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] grants){super.onRequestPermissionsResult(req,p,grants);if(req==101){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)openCamera();else level.setMessage("请允许相机权限后重试");}}
    @Override public void onSensorChanged(SensorEvent e){float x=e.values[0],y=e.values[1],z=e.values[2];
        rawTiltX=(float)Math.toDegrees(Math.atan2(x,Math.abs(z)));rawTiltY=(float)Math.toDegrees(Math.atan2(y,Math.abs(z)));rawZ=z;
        if(samplingCalibration){calibrationSamples++;calibrationSumX+=rawTiltX;calibrationSumY+=rawTiltY;calibrationSumZ+=z;
            calibrationMinX=Math.min(calibrationMinX,rawTiltX);calibrationMaxX=Math.max(calibrationMaxX,rawTiltX);
            calibrationMinY=Math.min(calibrationMinY,rawTiltY);calibrationMaxY=Math.max(calibrationMaxY,rawTiltY);
        }
        // With the phone top kept in the same compass direction, flipping screen-down to
        // camera-down reverses the device X axis while leaving its Y axis aligned.
        float refX=calibrated?(Math.signum(z)==Math.signum(calibrationZ)?calibrationX:-calibrationX):0f;
        float refY=calibrated?calibrationY:0f;
        level.setTilt(rawTiltX-refX,rawTiltY-refY,z);
    }
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    static final class AssistOverlayView extends View{
        static final int MODE_IMAGE=0,MODE_GRID=1,MODE_H=2,MODE_V=3;
        LevelView guides;
        int guideMode,selectedGuideIndex=-1;
        private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;float opacity=.5f;
        float offsetX,offsetY,scale=1f,angle;
        private float lastX,lastY,lastDistance;
        private boolean twoFingers;
        AssistOverlayView(Context context){super(context);setContentDescription("半透明面雕虚像，可单指移动、双指缩放；旋转请使用工具栏旋钮");}
        void setBitmap(Bitmap value){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();bitmap=value;reset();}
        void reset(){offsetX=offsetY=angle=0;scale=1f;invalidate();}
        void rotateBy(float degrees){setAngle(angle+degrees);}
        void setAngle(float value){angle=AngleKnob.quantize(value);invalidate();}
        void setGuideMode(int mode){setGuideMode(mode,-1);}
        void setGuideMode(int mode,int index){guideMode=mode;selectedGuideIndex=index;if(guides!=null){guides.selectedGuideMode=mode;guides.selectedGuideIndex=index;guides.invalidate();}}
        boolean deleteSelectedGuide(){
            if(guides==null||selectedGuideIndex<0)return false;
            ArrayList<Float> list=guideMode==MODE_H?guides.horizontalGuides:guideMode==MODE_V?guides.verticalGuides:null;
            if(list==null||selectedGuideIndex>=list.size())return false;
            list.remove(selectedGuideIndex);setGuideMode(MODE_IMAGE);return true;
        }
        private float fit(){if(bitmap==null)return 1f;return Math.min(getWidth()*.64f/bitmap.getWidth(),getHeight()*.48f/bitmap.getHeight());}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap==null)return;
            c.save();c.translate(getWidth()/2f+offsetX,getHeight()/2f+offsetY);c.rotate(angle);c.scale(fit()*scale,fit()*scale);
            imagePaint.setAlpha(Math.round(opacity*255));c.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,imagePaint);c.restore();
        }
        private static float distance(MotionEvent e){return (float)Math.hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));}
        @Override public boolean onTouchEvent(MotionEvent e){if(bitmap==null&&guideMode==MODE_IMAGE)return false;
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();twoFingers=false;return true;
                case MotionEvent.ACTION_POINTER_DOWN:if(e.getPointerCount()>=2&&guideMode==MODE_IMAGE){twoFingers=true;lastDistance=distance(e);}return true;
                case MotionEvent.ACTION_MOVE:if(guideMode!=MODE_IMAGE){
                    if(e.getPointerCount()>1)return true;
                    float dx=e.getX()-lastX,dy=e.getY()-lastY;lastX=e.getX();lastY=e.getY();
                    if(guideMode==MODE_GRID){guides.gridOffsetX+=dx/getWidth();guides.gridOffsetY+=dy/getHeight();}
                    else if(guideMode==MODE_H&&selectedGuideIndex>=0&&selectedGuideIndex<guides.horizontalGuides.size())guides.horizontalGuides.set(selectedGuideIndex,Math.max(0f,Math.min(1f,guides.horizontalGuides.get(selectedGuideIndex)+dy/getHeight())));
                    else if(guideMode==MODE_V&&selectedGuideIndex>=0&&selectedGuideIndex<guides.verticalGuides.size())guides.verticalGuides.set(selectedGuideIndex,Math.max(0f,Math.min(1f,guides.verticalGuides.get(selectedGuideIndex)+dx/getWidth())));
                    guides.invalidate();return true;
                }else if(e.getPointerCount()>=2){
                    float dist=distance(e);
                    if(lastDistance>1)scale=Math.max(.2f,Math.min(5f,scale*dist/lastDistance));
                    lastDistance=dist;
                }else if(!twoFingers){offsetX+=e.getX()-lastX;offsetY+=e.getY()-lastY;lastX=e.getX();lastY=e.getY();}
                    invalidate();return true;
                case MotionEvent.ACTION_POINTER_UP:if(e.getPointerCount()==2){int remaining=e.getActionIndex()==0?1:0;lastX=e.getX(remaining);lastY=e.getY(remaining);twoFingers=false;}return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:return true;
            }return true;
        }
    }
    static final class LevelView extends View{
        private final Paint p=new Paint(3);
        private float tiltX,tiltY,z;
        private String message;
        private boolean sampled,ready;
        boolean showGrid,showCross,showGuideDistances=true;
        boolean minimalUi,desktopCalibrated;
        float gridOffsetX,gridOffsetY;
        final ArrayList<Float> horizontalGuides=new ArrayList<>(),verticalGuides=new ArrayList<>();
        int selectedGuideMode=-1,selectedGuideIndex=-1;
        private long alignedSince;
        LevelView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        void resetTiltSmoothing(){sampled=false;ready=false;alignedSince=0;invalidate();}
        void setTilt(float x,float y,float gravityZ){
            if(!sampled){tiltX=x;tiltY=y;z=gravityZ;sampled=true;}
            else{tiltX+=.22f*(x-tiltX);tiltY+=.22f*(y-tiltY);z+=.22f*(gravityZ-z);}
            boolean nearCenter=Math.abs(tiltX)<.35f&&Math.abs(tiltY)<.35f&&z>6;
            if(nearCenter){if(alignedSince==0)alignedSince=SystemClock.uptimeMillis();if(SystemClock.uptimeMillis()-alignedSince>=300)ready=true;}
            else{alignedSince=0;if(Math.abs(tiltX)>.65f||Math.abs(tiltY)>.65f||z<5.5f)ready=false;}
            invalidate();
        }
        void setMessage(String m){message=m;invalidate();}
        private void cross(Canvas c,float x,float y,float arm,float stroke,int color){
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(stroke);p.setColor(color);
            p.setShadowLayer(stroke*1.3f,0,stroke*.5f,0xCC10211F);
            c.drawLine(x-arm,y,x+arm,y,p);c.drawLine(x,y-arm,x,y+arm,p);
            p.clearShadowLayer();
        }
        static final class GuideGap{
            final float midpoint;
            final int pixels;
            GuideGap(float midpoint,int pixels){this.midpoint=midpoint;this.pixels=pixels;}
        }
        static ArrayList<GuideGap> guideGaps(ArrayList<Float> positions,int axisPixels){
            ArrayList<GuideGap> result=new ArrayList<>();
            if(positions.size()<2||axisPixels<=0)return result;
            ArrayList<Float> ordered=new ArrayList<>(positions);Collections.sort(ordered);
            for(int i=1;i<ordered.size();i++){
                float first=ordered.get(i-1),second=ordered.get(i);
                result.add(new GuideGap((first+second)/2f,Math.round((second-first)*axisPixels)));
            }
            return result;
        }
        private void distanceLabel(Canvas c,String label,float x,float y,float d){
            p.setStyle(Paint.Style.FILL);p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            p.setTextSize(11*d);p.setTextAlign(Paint.Align.CENTER);
            float halfWidth=p.measureText(label)/2f+7*d;
            x=Math.max(halfWidth+5*d,Math.min(getWidth()-halfWidth-5*d,x));
            p.setColor(0xA81B302D);c.drawRoundRect(x-halfWidth,y-10*d,x+halfWidth,y+10*d,5*d,5*d,p);
            p.setColor(0xD9FFFFFF);c.drawText(label,x,y+4*d,p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f;
            // Fine thirds lines match the camera reference without obscuring the subject.
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.75f*d);p.setColor(0x72FFFFFF);
            for(int i=-3;showGrid&&i<6;i++){
                float x=getWidth()*(i/3f+gridOffsetX),y=getHeight()*(i/3f+gridOffsetY);
                if(x>=0&&x<=getWidth())c.drawLine(x,0,x,getHeight(),p);
                if(y>=0&&y<=getHeight())c.drawLine(0,y,getWidth(),y,p);
            }
            if(showCross){p.setColor(0xA8FFFFFF);p.setStrokeWidth(1f*d);c.drawLine(cx,0,cx,getHeight(),p);c.drawLine(0,cy,getWidth(),cy,p);}
            for(int i=0;i<horizontalGuides.size();i++){boolean selected=selectedGuideMode==AssistOverlayView.MODE_H&&selectedGuideIndex==i;
                p.setColor(selected?0xFF70E6D2:0xE6F3C275);p.setStrokeWidth((selected?2.5f:1.4f)*d);
                float y=horizontalGuides.get(i)*getHeight();c.drawLine(0,y,getWidth(),y,p);
                if(selected){p.setStyle(Paint.Style.FILL);c.drawCircle(getWidth()-16*d,y,5*d,p);p.setStyle(Paint.Style.STROKE);}
            }
            for(int i=0;i<verticalGuides.size();i++){boolean selected=selectedGuideMode==AssistOverlayView.MODE_V&&selectedGuideIndex==i;
                p.setColor(selected?0xFF70E6D2:0xE6F3C275);p.setStrokeWidth((selected?2.5f:1.4f)*d);
                float x=verticalGuides.get(i)*getWidth();c.drawLine(x,0,x,getHeight(),p);
                if(selected){p.setStyle(Paint.Style.FILL);c.drawCircle(x,getHeight()-16*d,5*d,p);p.setStyle(Paint.Style.STROKE);}
            }
            if(minimalUi)return;
            if(showGuideDistances){
                for(GuideGap gap:guideGaps(horizontalGuides,getHeight()))
                    distanceLabel(c,gap.pixels+" px",getWidth()-51*d,gap.midpoint*getHeight(),d);
                for(GuideGap gap:guideGaps(verticalGuides,getWidth()))
                    distanceLabel(c,gap.pixels+" px",gap.midpoint*getWidth(),92*d,d);
            }
            float half=57*d,corner=19*d;
            p.setStyle(Paint.Style.STROKE);p.setColor(0xDEFFFFFF);p.setStrokeWidth(1.6f*d);p.setStrokeCap(Paint.Cap.SQUARE);
            p.setShadowLayer(2*d,0,d,0xAA10211F);
            for(int sx:new int[]{-1,1})for(int sy:new int[]{-1,1}){
                float x=cx+sx*half,y=cy+sy*half;
                c.drawLine(x,y,x-sx*corner,y,p);c.drawLine(x,y,x,y-sy*corner,p);
            }
            p.clearShadowLayer();
            // White stays fixed; amber moves with gravity and meets it when the phone is level.
            cross(c,cx,cy,29*d,1.6f*d,0xF2FFFFFF);
            float moveX=tiltX*11*d,moveY=tiltY*11*d;
            if(markerWithinFrame(moveX,moveY,half,24*d)){
                int indicator=ready?0xFF62E2CD:0xFFFFC66D;
                cross(c,cx+moveX,cy+moveY,24*d,2.1f*d,indicator);
            }
            String status=message!=null?message:!sampled?"正在读取水平传感器":z<0?"请将后置镜头朝下":ready?
                desktopCalibrated?"十字重合 · 桌面基准":"十字重合 · 已水平":
                String.format(java.util.Locale.CHINA,desktopCalibrated?"桌面 X %+.1f°   Y %+.1f°":"X %+.1f°    Y %+.1f°",tiltX,tiltY);
            // A single quiet line above the reticle leaves the subject visible.
            float labelY=cy-(half+23)*d;
            p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            p.setShadowLayer(2*d,0,d,0xDD10211F);
            p.setTextAlign(Paint.Align.CENTER);p.setTextSize(12*d);c.drawText(status,cx,labelY,p);p.clearShadowLayer();
            p.setTextAlign(Paint.Align.LEFT);
        }
        static boolean markerWithinFrame(float x,float y,float half,float arm){return Math.abs(x)+arm<=half&&Math.abs(y)+arm<=half;}
    }
}
