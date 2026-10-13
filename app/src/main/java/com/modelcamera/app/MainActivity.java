package com.modelcamera.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int BG=0xFFF7F6F2, INK=0xFF18312E, MUTED=0xFF687775, TEAL=0xFF2D8278, LINE=0xFFE0E9E5;
    private static final int PICK_FACE=10,PICK_LIBRARY_IMAGE=12,PICK_LIBRARY_PNGS=13,PICK_LIBRARY_ZIP=14,CAM_FACE=20,PICK_EXPORT_DIR=30,EXPORT_STORAGE_PERMISSION=31;
    private StickerRepo repo;
    private SessionRepo sessions;
    private ExportStore exports;
    private boolean exporting,libraryBusy,pendingExportSculptOnly;
    private String currentSessionId,currentSessionName;
    private StickerRepo.Sticker editingSticker;
    private float editingPpm,editingSourceRatio;
    private Bitmap faceBitmap,sourceBitmap;
    private File faceFile;
    private File rotatedImportFile;
    private boolean albumPhoto;
    private Calibration.Result faceCalibration,sourceCalibration;
    private PhotoBoard board;
    private PhotoBoard editorBoard;
    private CutoutEditorView cutoutEditor;
    private final Handler cutoutHandler=new Handler(Looper.getMainLooper());
    private final ExecutorService cutoutWorker=Executors.newSingleThreadExecutor();
    private Runnable pendingCutoutUpdate;
    private boolean updatingCutout;
    private boolean detectingFace;
    private int appliedSensitivity=50;
    private TextView sensitivityLabel;
    private SeekBar sensitivitySlider;
    private Button cutoutSaveButton;
    private Rect retainedCrop;
    private LinearLayout root,libraryRow;
    private TextView scaleLabel;
    private int screen=0,sensitivity=50,sharpenStrength=0;
    private String sourceName="水贴";
    private int managerReturnScreen=0;
    private String managerFilter=null,libraryFilter=null;
    private static final String MANAGER_ALL="@all";
    private String managerQuery="";
    private boolean managerSelecting=false;
    private final Set<String> managerSelected=new LinkedHashSet<>();
    private final ExecutorService managerWorker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);repo=new StickerRepo(this);sessions=new SessionRepo(this);exports=new ExportStore(this);showHome();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(Math.min(radius,12)));return d;}
    private android.graphics.drawable.Drawable outlinedSurface(int fill,int border,int radius){
        GradientDrawable shape=background(fill,radius);shape.setStroke(dp(1),border);
        return new RippleDrawable(ColorStateList.valueOf(0x262D8278),shape,null);
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));return t;}
    private Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));b.setTextColor(primary?Color.WHITE:INK);b.setBackground(outlinedSurface(primary?INK:Color.WHITE,primary?INK:LINE,9));b.setMinHeight(0);b.setMinimumHeight(0);b.setElevation(0);b.setStateListAnimator(null);return b;}
    private ImageView iconButton(int icon,String description,int color){
        ImageView view=new ImageView(this);view.setImageResource(icon);view.setImageTintList(ColorStateList.valueOf(color));
        view.setPadding(dp(11),dp(11),dp(11),dp(11));view.setBackground(outlinedSurface(0xFFF0F4F2,LINE,9));
        view.setContentDescription(description);view.setTooltipText(description);view.setClickable(true);view.setFocusable(true);return view;
    }
    private static final class FooterAction {final LinearLayout view;final ImageView icon;final TextView label;FooterAction(LinearLayout v,ImageView i,TextView l){view=v;icon=i;label=l;}}
    private FooterAction footerAction(int iconResource,String name,String description){
        LinearLayout item=column();item.setGravity(Gravity.CENTER);item.setBackground(outlinedSurface(0xFFF0F4F2,LINE,9));
        ImageView icon=new ImageView(this);icon.setImageResource(iconResource);icon.setImageTintList(ColorStateList.valueOf(INK));
        item.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
        TextView label=text(name,11,INK,true);label.setGravity(Gravity.CENTER);item.addView(label,lp(-1,-2,3));
        item.setContentDescription(description);item.setTooltipText(description);item.setClickable(true);item.setFocusable(true);
        return new FooterAction(item,icon,label);
    }
    private void styleFooter(FooterAction action,boolean selected,boolean primary){
        int fill=primary?INK:selected?TEAL:0xFFF0F4F2,foreground=primary||selected?Color.WHITE:INK;
        action.view.setBackground(outlinedSurface(fill,primary?INK:selected?TEAL:LINE,9));action.icon.setImageTintList(ColorStateList.valueOf(foreground));action.label.setTextColor(foreground);
    }
    private LinearLayout.LayoutParams lp(int w,int h,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.topMargin=dp(top);return p;}
    private void margin(View v,LinearLayout parent,int height,int top){parent.addView(v,lp(-1,height<0?height:dp(height),top));}
    private SeekBar toneSlider(LinearLayout panel,String name,IntConsumer onChange){
        TextView label=text(name+"  0",13,INK,true);margin(label,panel,-2,9);
        SeekBar slider=new SeekBar(this);slider.setMax(200);slider.setProgress(100);margin(slider,panel,38,0);
        slider.setContentDescription(name+" 0");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean user){
                int value=progress-100;String display=name+"  "+(value>0?"+":"")+value;
                label.setText(display);bar.setContentDescription(display);onChange.accept(value);
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });
        return slider;
    }
    private void startScreen(int which){screen=which;root=column();root.setBackgroundColor(BG);setContentView(root);}
    private void header(String title,String subtitle,Runnable back){
        LinearLayout bar=row();bar.setPadding(dp(18),dp(18),dp(18),dp(14));
        if(back!=null){ImageView arrow=iconButton(R.drawable.ic_arrow_back,"返回",INK);arrow.setBackgroundColor(Color.TRANSPARENT);bar.addView(arrow,new LinearLayout.LayoutParams(dp(44),dp(46)));arrow.setOnClickListener(v->back.run());}
        LinearLayout labels=column();TextView h=text(title,23,INK,true);h.setSingleLine(true);h.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(h);if(subtitle!=null){TextView sub=text(subtitle,12,MUTED,false);sub.setSingleLine(true);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);sub.setPadding(0,dp(3),0,0);labels.addView(sub);}bar.addView(labels,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
    }
    private void showHome(){startScreen(0);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=column();content.setPadding(dp(20),dp(20),dp(20),dp(30));scroll.addView(content);
        LinearLayout homeHeading=row();content.addView(homeHeading);
        LinearLayout branding=column();branding.addView(text("MODEL CAMERA  /  水贴相机",11,TEAL,true));
        margin(text("v"+appVersion(),11,MUTED,false),branding,-2,2);homeHeading.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        ImageView more=iconButton(R.drawable.ic_more_horiz,"更多",INK);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(44),dp(44));mp.rightMargin=dp(8);homeHeading.addView(more,mp);
        more.setOnClickListener(v->{android.widget.PopupMenu menu=new android.widget.PopupMenu(this,more);menu.getMenu().add("体验示例照片");menu.setOnMenuItemClickListener(item->{chooseSample();return true;});menu.show();});
        ImageView settings=iconButton(R.drawable.ic_settings,"设置",INK);homeHeading.addView(settings,new LinearLayout.LayoutParams(dp(44),dp(44)));settings.setOnClickListener(v->showSettings());
        TextView title=text("让水贴，对准眼睛。",27,INK,true);margin(title,content,-2,21);
        margin(text("同框拍摄 → 制作贴纸 → 试贴定位",13,MUTED,false),content,-2,5);

        LinearLayout hero=column();hero.setPadding(dp(21),dp(20),dp(21),dp(21));hero.setBackground(outlinedSurface(INK,INK,12));content.addView(hero,lp(-1,-2,25));
        hero.addView(text("从同一张照片开始",12,0xFF91D8C8,true));
        margin(text("面雕与水贴，一次拍好",21,Color.WHITE,true),hero,-2,9);
        margin(text("同一画面保留比例，后续试贴更直观。",13,0xD8FFFFFF,false),hero,-2,4);
        LinearLayout capture=row();capture.setPadding(dp(15),0,dp(14),0);capture.setBackground(outlinedSurface(0xFFE5F7F1,0xFFE5F7F1,9));margin(capture,hero,50,18);
        ImageView cameraIcon=new ImageView(this);cameraIcon.setImageResource(R.drawable.ic_camera);cameraIcon.setImageTintList(ColorStateList.valueOf(INK));capture.addView(cameraIcon,new LinearLayout.LayoutParams(dp(23),dp(23)));
        TextView captureText=text("现场拍摄",16,INK,true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(10);capture.addView(captureText,cp);
        capture.addView(text("→",19,INK,true));capture.setContentDescription("现场拍摄面雕和水贴");capture.setClickable(true);capture.setOnClickListener(v->openCamera());
        LinearLayout quick=row();margin(quick,content,94,11);
        LinearLayout album=homeShortcut(R.drawable.ic_photo_library,"相册导入","已有照片");quick.addView(album,new LinearLayout.LayoutParams(0,-1,1));album.setOnClickListener(v->pick());
        LinearLayout assist=homeShortcut(R.drawable.ic_layers,"粘贴辅助","实时对位");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-1,1);ap.leftMargin=dp(10);quick.addView(assist,ap);
        assist.setOnClickListener(v->{Intent i=new Intent(this,CameraActivity.class);i.putExtra(CameraActivity.EXTRA_ASSIST,true);startActivity(i);});
        if(faceBitmap!=null){Button resume=button("继续当前照片  →",false);margin(resume,content,46,12);resume.setOnClickListener(v->{if(editorBoard!=null)showEditor();else showCrop();});}

        LinearLayout sessionHeading=row();margin(sessionHeading,content,40,22);sessionHeading.addView(text("最近会话",18,INK,true));
        List<SessionRepo.Entry> entries=sessions.list();
        if(entries.isEmpty())margin(text("试贴页可保存进度，下次继续。",12,MUTED,false),content,-2,2);
        else for(SessionRepo.Entry entry:entries){
            Button resume=button(entry.name+"  ·  "+entry.layers+" 张贴纸",false);margin(resume,content,44,6);
            resume.setOnClickListener(v->loadSession(entry));resume.setOnLongClickListener(v->{deleteSession(entry);return true;});
            margin(text(new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date(entry.time))+"  ·  长按删除",11,MUTED,false),content,-2,2);
        }
        LinearLayout libraryHeading=row();margin(libraryHeading,content,44,19);
        TextView libraryTitle=text("贴纸库  ·  "+repo.list().size(),18,INK,true);libraryHeading.addView(libraryTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button manage=button("查看全部",false);libraryHeading.addView(manage,new LinearLayout.LayoutParams(dp(96),dp(39)));manage.setOnClickListener(v->openManager());
        List<StickerRepo.Sticker> stickers=repo.list();if(stickers.isEmpty())margin(text("还没有贴纸。可以制作一张，或在管理页从相册导入。",13,MUTED,false),content,-2,7);
        else{HorizontalScrollView sc=new HorizontalScrollView(this);sc.setHorizontalScrollBarEnabled(false);margin(sc,content,112,8);LinearLayout list=row();sc.addView(list);for(StickerRepo.Sticker s:stickers){View item=stickerTile(s);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(104),dp(105));ip.rightMargin=dp(10);list.addView(item,ip);item.setOnClickListener(v->{if(faceBitmap==null){Toast.makeText(this,"请先拍摄或导入同框照片",Toast.LENGTH_SHORT).show();return;}showEditor();addSticker(s);});}}
        TextView note=text("本地处理 · 照片与贴纸仅保存在本机",11,MUTED,false);note.setGravity(Gravity.CENTER);margin(note,content,-2,23);
    }
    private LinearLayout homeShortcut(int icon,String name,String caption){LinearLayout item=row();item.setPadding(dp(13),0,dp(10),0);item.setBackground(outlinedSurface(Color.WHITE,LINE,11));
        ImageView image=new ImageView(this);image.setImageResource(icon);image.setImageTintList(ColorStateList.valueOf(TEAL));item.addView(image,new LinearLayout.LayoutParams(dp(24),dp(24)));
        LinearLayout labels=column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(10);item.addView(labels,lp);labels.addView(text(name,14,INK,true));labels.addView(text(caption,11,MUTED,false));
        item.setContentDescription(name+"，"+caption);item.setClickable(true);item.setFocusable(true);return item;}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "未知";}}
    private void showSettings(){
        startScreen(5);header("设置","水贴相机 · v"+appVersion(),this::showHome);
        LinearLayout content=column();content.setPadding(dp(22),dp(15),dp(22),dp(25));root.addView(content);
        LinearLayout exportCard=card();content.addView(exportCard,new LinearLayout.LayoutParams(-1,-2));
        exportCard.addView(text("导出图片",20,INK,true));margin(text("默认保存目录",13,MUTED,false),exportCard,-2,15);
        TextView directory=text(exports.directoryLabel(),16,TEAL,true);directory.setPadding(dp(12),dp(16),dp(12),dp(16));
        directory.setBackground(background(0xFFF0F5F2,12));margin(directory,exportCard,-2,7);
        margin(text("点击「导出预览图」即保存 PNG，自动生成文件名。",13,MUTED,false),exportCard,-2,14);
        margin(text("更改目录只需选择一次，关闭 App 后也会记住。",13,MUTED,false),exportCard,-2,6);
        Button change=button("更改默认目录",true);margin(change,exportCard,50,18);change.setOnClickListener(v->pickExportDirectory());
        Button reset=button("恢复 Download 目录",false);margin(reset,exportCard,48,8);reset.setEnabled(!exports.usesDownloads());
        reset.setOnClickListener(v->{try{exports.useDownloads();showSettings();Toast.makeText(this,"已恢复默认 Download 目录",Toast.LENGTH_SHORT).show();}catch(Exception e){alert("设置未保存",e.getMessage());}});
    }
    private void pickExportDirectory(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri current=exports.directoryUri();if(current!=null)intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,current);
        try{startActivityForResult(intent,PICK_EXPORT_DIR);}catch(android.content.ActivityNotFoundException e){alert("无法选择目录","此设备缺少文件夹选择器，请使用默认 Download 目录。");}
    }
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(19),dp(19),dp(19),dp(18));l.setBackground(outlinedSurface(Color.WHITE,LINE,12));return l;}
    private View stickerTile(StickerRepo.Sticker s){LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setBackground(background(Color.WHITE,12));ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.FIT_CENTER);img.setPadding(dp(8),dp(6),dp(8),dp(3));tile.addView(img,new LinearLayout.LayoutParams(-1,0,1));try{img.setImageBitmap(ImageTools.load(repo.file(s.id),480));}catch(Exception ignored){}TextView name=text(s.name,11,INK,false);name.setGravity(Gravity.CENTER);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(5),0,dp(5),0);tile.addView(name,lp(-1,dp(24),0));return tile;}
    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,PICK_FACE);}
    private void openCamera(){Intent i=new Intent(this,CameraActivity.class);startActivityForResult(i,CAM_FACE);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        try{
            if(request==PICK_FACE){File file=new File(getFilesDir(),"import_"+System.currentTimeMillis()+".jpg");try(InputStream in=getContentResolver().openInputStream(data.getData());OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}openPhoto(file,true);}
            if(request==CAM_FACE)openPhoto(new File(data.getStringExtra("path")),false);
            if(request==PICK_LIBRARY_IMAGE)importStickerImage(data.getData());
            if(request==PICK_LIBRARY_PNGS){List<Uri> chosen=new ArrayList<>();ClipData clips=data.getClipData();
                if(clips!=null)for(int i=0;i<clips.getItemCount();i++)chosen.add(clips.getItemAt(i).getUri());
                else if(data.getData()!=null)chosen.add(data.getData());
                if(!chosen.isEmpty())importManyPng(chosen);
            }
            if(request==PICK_LIBRARY_ZIP)importStickerZip(data.getData());
            if(request==PICK_EXPORT_DIR){exports.selectDirectory(data.getData(),data.getFlags());showSettings();Toast.makeText(this,"默认导出目录已更新",Toast.LENGTH_SHORT).show();}
        }catch(Exception e){alert("操作失败",e.getMessage());}
    }
    private void openPhoto(File file,boolean fromAlbum){try{
        Bitmap image=ImageTools.load(file,2048);
        Calibration.Result result=Calibration.detect(image);
        faceFile=file;rotatedImportFile=null;albumPhoto=fromAlbum;faceBitmap=image;sourceBitmap=image;faceCalibration=result;sourceCalibration=result;editorBoard=null;retainedCrop=null;
        currentSessionId=currentSessionName=null;
        sourceName="表情 "+(repo.list().size()+1);showCrop();
    }catch(Exception e){alert("读取照片失败",e.getMessage());}}
    private void chooseSample(){new AlertDialog.Builder(this).setTitle("选择同框测试照片").setItems(new String[]{"单组闭眼表情","多组彩色眼睛","绿垫强反光水贴"},(d,which)->loadSample(which==0?"sample_face.jpg":which==1?"sample_sheet.jpg":"sample_lavender_sheet.jpg")).show();}
    private void loadSample(String asset){try{File file=new File(getFilesDir(),asset);if(!file.exists())copyAsset(asset,file);openPhoto(file,false);}catch(Exception e){alert("示例照片不可用",e.getMessage());}}
    private void copyAsset(String name,File file)throws Exception{try(InputStream in=getAssets().open(name);OutputStream out=new FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}

    private String scaleText(Calibration.Result result){return result==null?"未检测到网点 · 可手动标定":String.format(java.util.Locale.CHINA,"切割垫 %.1f px/mm · %s%d 个网点",result.pixelsPerMm,result.automatic?"自动识别 ":"手动标定 ",result.dotCount);}
    private void showCrop(){startScreen(1);header("制作贴纸","拖动选框，框住一组眼睛和眉毛",this::showHome);
        board=new PhotoBoard(this,sourceBitmap,PhotoBoard.CROP);board.setCrop(retainedCrop==null?BlueKey.suggest(sourceBitmap):retainedCrop);root.addView(board,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(20));panel.setBackground(background(Color.WHITE,20));root.addView(panel);
        scaleLabel=text(scaleText(sourceCalibration),12,TEAL,true);margin(scaleLabel,panel,-2,0);
        String cameraInfo=cameraMetadataText();
        if(albumPhoto&&editorBoard==null){
            LinearLayout direction=row();margin(direction,panel,46,7);
            direction.addView(text("照片方向 · 每次旋转 90°",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
            ImageView left=iconButton(R.drawable.ic_rotate_left_90,"向左旋转照片 90 度",INK);
            ImageView right=iconButton(R.drawable.ic_rotate_right_90,"向右旋转照片 90 度",INK);
            LinearLayout.LayoutParams lpLeft=new LinearLayout.LayoutParams(dp(46),dp(46));lpLeft.rightMargin=dp(8);direction.addView(left,lpLeft);
            direction.addView(right,new LinearLayout.LayoutParams(dp(46),dp(46)));
            left.setOnClickListener(v->rotateImportedPhoto(false));right.setOnClickListener(v->rotateImportedPhoto(true));
        }
        LinearLayout actions=row();margin(actions,panel,50,11);
        ImageView reset=iconButton(R.drawable.ic_undo,"重新选择水贴范围",INK);actions.addView(reset,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button adjust=button("调整抠图",true);LinearLayout.LayoutParams adjustParams=new LinearLayout.LayoutParams(0,dp(48),1);adjustParams.leftMargin=dp(8);adjustParams.rightMargin=dp(8);actions.addView(adjust,adjustParams);
        ImageView more=iconButton(R.drawable.ic_more_horiz,"更多框选工具",INK);actions.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));
        reset.setOnClickListener(v->{retainedCrop=null;board.setMode(PhotoBoard.CROP);board.setCrop(BlueKey.suggest(sourceBitmap));});adjust.setOnClickListener(v->previewCutout());
        more.setOnClickListener(v->{android.widget.PopupMenu menu=new android.widget.PopupMenu(this,more);menu.getMenu().add(0,1,0,"手动标定切割垫");if(cameraInfo!=null)menu.getMenu().add(0,2,1,"查看拍摄参数");menu.setOnMenuItemClickListener(item->{if(item.getItemId()==1)manualCalibration(false);else alert("拍摄参数",cameraInfo);return true;});menu.show();});
    }
    private void rotateImportedPhoto(boolean clockwise){
        if(!albumPhoto||editorBoard!=null||sourceBitmap==null||board==null)return;
        Bitmap previous=sourceBitmap,rotated=null;
        try{
            Rect nextCrop=ImageTools.rotateQuarterTurn(board.cropRect(),previous.getWidth(),previous.getHeight(),clockwise);
            rotated=ImageTools.rotateQuarterTurn(previous,clockwise);
            File output=rotatedImportFile==null?new File(getFilesDir(),"corrected_import_"+System.currentTimeMillis()+".png"):rotatedImportFile;
            try(FileOutputStream stream=new FileOutputStream(output)){if(!rotated.compress(Bitmap.CompressFormat.PNG,100,stream))throw new Exception("无法写入校正后的照片");}
            rotatedImportFile=output;faceFile=output;faceBitmap=sourceBitmap=rotated;retainedCrop=nextCrop;
            board.setPhoto(rotated);board.setCrop(nextCrop);previous.recycle();
        }catch(Exception e){if(rotated!=null&&rotated!=sourceBitmap)rotated.recycle();alert("旋转失败",e.getMessage());}
    }
    private String cameraMetadataText(){
        if(faceFile==null)return null;File metadata=new File(faceFile.getAbsolutePath()+".json");if(!metadata.isFile())return null;
        try{byte[] bytes=java.nio.file.Files.readAllBytes(metadata.toPath());org.json.JSONObject info=new org.json.JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder("拍摄信息已记录");if(info.has("focalLengthMm"))s.append(String.format(java.util.Locale.CHINA," · 焦距 %.2f mm",info.getDouble("focalLengthMm")));
            if(info.has("whiteBalanceMode"))s.append(" · 白平衡 ").append("auto".equals(info.getString("whiteBalanceMode"))?"自动":info.getString("whiteBalanceMode"));
            return s.toString();
        }catch(Exception ignored){return null;}
    }
    private void previewCutout(){try{
        editingSticker=null;
        retainedCrop=board.cropRect();
        showCutoutEditor(BlueKey.extractFixed(sourceBitmap,retainedCrop,sensitivity));
    }catch(Exception e){alert("抠图未完成",e.getMessage());}}

    private void showCutoutEditor(BlueKey.Result extracted){
        startScreen(4);header("调整贴纸","确认切分、擦除和角度后，再保存到贴纸库",this::closeCutoutEditor);
        cutoutEditor=new CutoutEditorView(this,extracted.bitmap);
        appliedSensitivity=sensitivity;
        cutoutEditor.setSharpen(sharpenStrength);
        root.addView(cutoutEditor,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(310)));
        LinearLayout panel=column();panel.setPadding(dp(18),dp(13),dp(18),dp(19));panel.setBackground(background(Color.WHITE,20));scroll.addView(panel);
        String dimensions=String.format(java.util.Locale.CHINA,"贴纸预览  %d × %d px",extracted.bitmap.getWidth(),extracted.bitmap.getHeight());
        float cutoutPpm=editingSticker==null?(sourceCalibration==null?0:sourceCalibration.pixelsPerMm):editingPpm;
        if(cutoutPpm>0)dimensions+=String.format(java.util.Locale.CHINA,"  ·  约 %.1f × %.1f mm",extracted.bitmap.getWidth()/cutoutPpm,extracted.bitmap.getHeight()/cutoutPpm);
        margin(text(dimensions,12,TEAL,true),panel,-2,0);
        margin(text("双指放大细节；擦除模式下单指擦；参考线与切分线在旋转时固定。",11,MUTED,false),panel,-2,5);

        if(editingSticker==null){
        sensitivityLabel=text("去底纸强度  "+sensitivity+"%",13,INK,true);margin(sensitivityLabel,panel,-2,12);
        sensitivitySlider=new SeekBar(this);sensitivitySlider.setMax(100);sensitivitySlider.setProgress(sensitivity);
        margin(sensitivitySlider,panel,38,1);
        margin(text("亮斑会按底纸周围颜色清理；可放大检查眼白和细线。",11,MUTED,false),panel,-2,1);
        sensitivitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int progress,boolean user){
                if(!user)return;
                sensitivity=progress;requestCutoutUpdate(false);
            }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){requestCutoutUpdate(true);}
        });
        }else margin(text("二次编辑 · "+editingSticker.name+" · 保留透明度与原有尺寸比例",12,TEAL,true),panel,-2,10);

        margin(text("切分参考网格",13,INK,true),panel,-2,13);
        LinearLayout cutoutGridRow=row();margin(cutoutGridRow,panel,40,4);
        int[] gridCounts={0,3,4,5,6};String[] gridNames={"隐藏","3×3","4×4","5×5","6×6"};
        Button[] cutoutGridButtons=new Button[gridCounts.length];
        for(int i=0;i<gridCounts.length;i++){
            final int divisions=gridCounts[i];Button choice=button(gridNames[i],false);cutoutGridButtons[i]=choice;
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=dp(4);cutoutGridRow.addView(choice,p);
            choice.setOnClickListener(v->{
                cutoutEditor.setGridDivisions(divisions);
                for(int j=0;j<gridCounts.length;j++)markSelected(cutoutGridButtons[j],gridCounts[j]==divisions);
            });
            markSelected(choice,cutoutEditor.gridDivisions()==divisions);
        }
        margin(text("网格覆盖整个预览，贴纸旋转时仍保持水平垂直。",11,MUTED,false),panel,-2,4);
        margin(text("手动参考线",13,INK,true),panel,-2,12);
        LinearLayout guideActions=row();margin(guideActions,panel,40,4);
        Button addHorizontal=button("＋水平线",false),addVertical=button("＋垂直线",false);
        Button undoGuide=button("撤销上条",false),clearGuides=button("清空",false);
        for(Button choice:new Button[]{addHorizontal,addVertical,undoGuide,clearGuides}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=dp(4);guideActions.addView(choice,p);
        }
        addHorizontal.setOnClickListener(v->{if(!cutoutEditor.addGuide(true))Toast.makeText(this,"最多添加 8 条参考线",Toast.LENGTH_SHORT).show();});
        addVertical.setOnClickListener(v->{if(!cutoutEditor.addGuide(false))Toast.makeText(this,"最多添加 8 条参考线",Toast.LENGTH_SHORT).show();});
        undoGuide.setOnClickListener(v->{if(!cutoutEditor.removeLastGuide())Toast.makeText(this,"还没有参考线",Toast.LENGTH_SHORT).show();});
        clearGuides.setOnClickListener(v->cutoutEditor.clearGuides());
        margin(text("拖动粉色线或端点微调；它们只用于对齐，不会保存到贴纸。",11,MUTED,false),panel,-2,3);

        margin(text("保存方式",13,INK,true),panel,-2,15);
        LinearLayout splitButtons=row();margin(splitButtons,panel,42,5);
        Button whole=button("整组保存",false),automatic=button("自动切分",false),manual=button("手动切分",false);
        Button[] choices={whole,automatic,manual};
        for(Button b:choices){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=dp(5);splitButtons.addView(b,p);}
        int[] splitMode={0};
        Runnable markSplit=()->{for(int i=0;i<choices.length;i++)markSelected(choices[i],splitMode[0]==i);};
        LinearLayout splitControls=column();margin(splitControls,panel,-2,5);splitControls.setVisibility(View.GONE);
        TextView splitLabel=text("分界位置 50% · 可直接拖动黄线",12,MUTED,false);margin(splitLabel,splitControls,-2,2);
        SeekBar splitSlider=new SeekBar(this);splitSlider.setMax(96);splitSlider.setProgress(48);margin(splitSlider,splitControls,37,1);
        splitSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){
                if(!user)return;
                int percent=p+2;cutoutEditor.setSplitPercent(percent);
                splitLabel.setText("分界位置 "+percent+"% · 可直接拖动黄线");
                splitMode[0]=2;markSplit.run();
            }
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
        });
        cutoutEditor.onSplitChanged(x->{
            int percent=Math.round(cutoutEditor.splitFraction()*100f);
            splitSlider.setProgress(Math.max(0,Math.min(96,percent-2)));
            splitLabel.setText("分界位置 "+percent+"% · 可直接拖动黄线");
            splitMode[0]=2;markSplit.run();
        });
        whole.setOnClickListener(v->{cutoutEditor.setSplitX(-1);splitMode[0]=0;splitControls.setVisibility(View.GONE);markSplit.run();});
        automatic.setOnClickListener(v->{
            CutoutOps.SplitSuggestion suggestion=CutoutOps.suggestSplit(cutoutEditor.working(),cutoutEditor.angle());
            cutoutEditor.setSplitX(suggestion.x);splitMode[0]=1;splitControls.setVisibility(View.VISIBLE);
            int percent=Math.round(cutoutEditor.splitFraction()*100f);
            splitSlider.setProgress(Math.max(0,Math.min(96,percent-2)));
            splitLabel.setText("分界位置 "+percent+"% · 可直接拖动黄线");markSplit.run();
            if(!suggestion.reliable)Toast.makeText(this,"未找到明显眼间空隙，请拖动分界线核对",Toast.LENGTH_LONG).show();
        });
        manual.setOnClickListener(v->{
            if(!cutoutEditor.splitEnabled())cutoutEditor.setSplitPercent(50);
            splitMode[0]=2;splitControls.setVisibility(View.VISIBLE);
            int percent=Math.round(cutoutEditor.splitFraction()*100f);
            splitSlider.setProgress(Math.max(0,Math.min(96,percent-2)));
            splitLabel.setText("分界位置 "+percent+"% · 可直接拖动黄线");markSplit.run();
        });markSplit.run();

        margin(text("清理边缘",13,INK,true),panel,-2,13);
        LinearLayout eraserRow=row();margin(eraserRow,panel,42,5);
        Button eraser=button("橡皮擦",false),undo=button("撤销擦除",false),reset=button("清除擦除",false);
        for(Button b:new Button[]{eraser,undo,reset}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=dp(5);eraserRow.addView(b,p);}
        LinearLayout brushControls=column();margin(brushControls,panel,-2,4);brushControls.setVisibility(View.GONE);
        TextView brushLabel=text("笔刷大小 8 px",12,MUTED,false);margin(brushLabel,brushControls,-2,1);
        SeekBar brushSlider=new SeekBar(this);brushSlider.setMax(58);brushSlider.setProgress(6);margin(brushSlider,brushControls,35,1);
        eraser.setOnClickListener(v->{boolean enabled=!cutoutEditor.isEraser();cutoutEditor.setEraser(enabled);markSelected(eraser,enabled);brushControls.setVisibility(enabled?View.VISIBLE:View.GONE);});
        undo.setOnClickListener(v->{if(!cutoutEditor.canUndoErase())Toast.makeText(this,"还没有可撤销的擦除",Toast.LENGTH_SHORT).show();else cutoutEditor.undoErase();});
        reset.setOnClickListener(v->cutoutEditor.resetErase());
        brushSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){int radius=p+2;cutoutEditor.setBrushRadius(radius);brushLabel.setText("笔刷大小 "+radius+" px");}
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
        });

        LinearLayout rotationRow=row();margin(rotationRow,panel,30,14);
        TextView rotationLabel=text("旋转  0°",13,INK,true);rotationRow.addView(rotationLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button resetAngle=button("角度归零",false);rotationRow.addView(resetAngle,new LinearLayout.LayoutParams(dp(96),dp(33)));
        AngleKnob rotation=new AngleKnob(this);margin(rotation,panel,96,3);
        rotation.onChange(degrees->{cutoutEditor.setAngle(degrees);rotationLabel.setText(String.format(java.util.Locale.CHINA,"旋转  %+.1f°",degrees));});
        resetAngle.setOnClickListener(v->{rotation.setValue(0);cutoutEditor.setAngle(0);rotationLabel.setText("旋转  0.0°");});
        margin(text("缓慢上下滑动旋钮微调；向上逆时针、向下顺时针，每格 0.5°。",11,MUTED,false),panel,-2,1);
        margin(text("旋转只改变角度，保持图案大小；超出预览时可双指缩小查看。",11,MUTED,false),panel,-2,0);
        TextView sharpLabel=text("锐化强度  "+sharpenStrength+"%",13,INK,true);margin(sharpLabel,panel,-2,10);
        SeekBar sharpSlider=new SeekBar(this);sharpSlider.setMax(100);sharpSlider.setProgress(sharpenStrength);margin(sharpSlider,panel,38,1);
        sharpSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){sharpenStrength=p;cutoutEditor.setSharpen(p);sharpLabel.setText("锐化强度  "+p+"%");}
            public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}
        });
        LinearLayout toneHeading=row();margin(toneHeading,panel,34,11);
        toneHeading.addView(text("色彩调整",13,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button resetTone=button("重置色彩",false);toneHeading.addView(resetTone,new LinearLayout.LayoutParams(dp(100),dp(32)));
        SeekBar contrastSlider=toneSlider(panel,"对比度",cutoutEditor::setContrast);
        SeekBar saturationSlider=toneSlider(panel,"饱和度",cutoutEditor::setSaturation);
        SeekBar brightnessSlider=toneSlider(panel,"明度",cutoutEditor::setBrightness);
        resetTone.setOnClickListener(v->{contrastSlider.setProgress(100);saturationSlider.setProgress(100);brightnessSlider.setProgress(100);});
        margin(text("滑块居中为原始颜色；透明边缘不会改变。",11,MUTED,false),panel,-2,3);
        Button compare=button("按住对比原图",false);margin(compare,panel,39,3);
        compare.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN)cutoutEditor.setComparing(true);
            else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)cutoutEditor.setComparing(false);
            return true;
        });

        LinearLayout actions=row();actions.setPadding(dp(18),dp(4),dp(18),dp(10));actions.setBackgroundColor(Color.WHITE);
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(64)));
        Button back=button(editingSticker==null?"返回框选":"返回管理",false),save=button(editingSticker==null?"保存贴纸":"保存…",true);
        cutoutSaveButton=save;
        actions.addView(back,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-1,1.25f);sp.leftMargin=dp(8);actions.addView(save,sp);
        back.setOnClickListener(v->closeCutoutEditor());save.setOnClickListener(v->saveCutoutEdits(save));
    }

    private void requestCutoutUpdate(boolean immediate){
        if(cutoutEditor==null||editingSticker!=null)return;
        if(pendingCutoutUpdate!=null)cutoutHandler.removeCallbacks(pendingCutoutUpdate);
        if(sensitivityLabel!=null)sensitivityLabel.setText("去底纸强度  "+sensitivity+"% · 更新中");
        if(cutoutSaveButton!=null)cutoutSaveButton.setEnabled(false);
        pendingCutoutUpdate=()->{pendingCutoutUpdate=null;runCutoutUpdate();};
        cutoutHandler.postDelayed(pendingCutoutUpdate,immediate?0:75);
    }
    private void runCutoutUpdate(){
        if(cutoutEditor==null||screen!=4||updatingCutout||editingSticker!=null)return;
        if(sensitivity==appliedSensitivity){finishCutoutUpdate();return;}
        updatingCutout=true;
        final int level=sensitivity;
        final Bitmap photo=sourceBitmap;
        final Rect crop=new Rect(retainedCrop);
        final CutoutEditorView target=cutoutEditor;
        cutoutWorker.execute(()->{
            BlueKey.Result extracted=null;Exception failure=null;
            try{extracted=BlueKey.extractFixed(photo,crop,level);}catch(Exception e){failure=e;}
            final BlueKey.Result ready=extracted;final Exception error=failure;
            cutoutHandler.post(()->{
                updatingCutout=false;
                if(target!=cutoutEditor||screen!=4){
                    if(ready!=null)ready.bitmap.recycle();
                    if(cutoutEditor!=null&&screen==4&&editingSticker==null&&sensitivity!=appliedSensitivity)requestCutoutUpdate(true);
                    return;
                }
                if(error==null){
                    try{target.replaceBase(ready.bitmap);appliedSensitivity=level;}
                    catch(RuntimeException e){ready.bitmap.recycle();Toast.makeText(this,"抠图更新失败："+e.getMessage(),Toast.LENGTH_LONG).show();}
                }else if(level==sensitivity){
                    sensitivity=appliedSensitivity;
                    if(sensitivitySlider!=null)sensitivitySlider.setProgress(sensitivity);
                    Toast.makeText(this,"当前强度无法识别图案："+error.getMessage(),Toast.LENGTH_LONG).show();
                }
                if(sensitivity!=appliedSensitivity)requestCutoutUpdate(true);
                else finishCutoutUpdate();
            });
        });
    }
    private void finishCutoutUpdate(){
        if(sensitivityLabel!=null)sensitivityLabel.setText("去底纸强度  "+appliedSensitivity+"%");
        if(cutoutSaveButton!=null)cutoutSaveButton.setEnabled(true);
    }
    private void releaseCutoutEditor(){
        if(pendingCutoutUpdate!=null){cutoutHandler.removeCallbacks(pendingCutoutUpdate);pendingCutoutUpdate=null;}
        if(cutoutEditor!=null){cutoutEditor.release();cutoutEditor=null;}
        cutoutSaveButton=null;sensitivityLabel=null;sensitivitySlider=null;
    }
    private void closeCutoutEditor(){boolean library=editingSticker!=null;releaseCutoutEditor();editingSticker=null;if(library)showManager();else showCrop();}
    private void saveCutoutEdits(Button button){
        if(cutoutEditor==null)return;
        if(editingSticker!=null){
            String[] options=cutoutEditor.splitEnabled()?new String[]{"保存左右眼副本"}:new String[]{"保存修改","另存副本"};
            new AlertDialog.Builder(this).setTitle("保存二次编辑").setItems(options,(d,n)->saveLibraryEdits(!cutoutEditor.splitEnabled()&&n==0)).setNegativeButton("取消",null).show();return;
        }
        if(sensitivity!=appliedSensitivity){Toast.makeText(this,"正在更新抠图，请稍候",Toast.LENGTH_SHORT).show();return;}
        button.setEnabled(false);
        Bitmap[] finished=null;List<StickerRepo.Sticker> saved=new ArrayList<>();
        try{
            finished=CutoutOps.render(cutoutEditor.working(),cutoutEditor.sharpen(),cutoutEditor.contrast(),
                    cutoutEditor.saturation(),cutoutEditor.brightness(),cutoutEditor.angle(),cutoutEditor.splitX());
            float ppm=sourceCalibration==null?0:sourceCalibration.pixelsPerMm;
            for(int i=0;i<finished.length;i++){
                Bitmap image=finished[i];String name=finished.length==1?sourceName:sourceName+(i==0?" · 画面左眼":" · 画面右眼");
                saved.add(repo.save(image,name,ppm>0?image.getWidth()/ppm:0,ppm>0?image.getHeight()/ppm:0,
                        faceFile.getAbsolutePath(),image.getWidth()));
            }
            sourceName="表情 "+(repo.list().size()+1);
            String message=saved.size()==2?"画面左右两侧的眼睛已分别保存，可单独移动、旋转和锁定。":"整组贴纸已保存，可继续试贴。";
            new AlertDialog.Builder(this).setTitle("贴纸已保存").setMessage(message)
                    .setNegativeButton("继续框选",(d,w)->closeCutoutEditor())
                    .setPositiveButton("现在试贴",(d,w)->{
                        releaseCutoutEditor();showEditor();
                        if(saved.size()==1)addSticker(saved.get(0));
                        else{
                            float gap=Math.max(8,(saved.get(0).sourceWidthPx+saved.get(1).sourceWidthPx)*.12f);
                            addSticker(saved.get(0));board.moveSelectedBy(-(saved.get(0).sourceWidthPx+gap)/2f,0);
                            addSticker(saved.get(1));board.moveSelectedBy((saved.get(1).sourceWidthPx+gap)/2f,0);
                        }
                    }).show();
        }catch(Exception e){
            for(StickerRepo.Sticker sticker:saved)try{repo.deleteSticker(sticker.id);}catch(Exception ignored){}
            alert("保存失败",e.getMessage());
        }finally{if(finished!=null)for(Bitmap image:finished)image.recycle();button.setEnabled(true);}
    }
    private void editLibrarySticker(StickerRepo.Sticker sticker){
        try{
            Bitmap image=android.graphics.BitmapFactory.decodeFile(repo.file(sticker.id).getAbsolutePath());
            if(image==null)throw new Exception("贴纸图片无法读取");
            editingSticker=sticker;editingPpm=sticker.widthMm>0?image.getWidth()/sticker.widthMm:0;
            editingSourceRatio=sticker.sourceWidthPx>0?sticker.sourceWidthPx/image.getWidth():0;
            sharpenStrength=0;
            showCutoutEditor(new BlueKey.Result(image,new Rect(0,0,image.getWidth(),image.getHeight()),0));
        }catch(Exception e){editingSticker=null;alert("无法编辑贴纸",e.getMessage());}
    }
    private void saveLibraryEdits(boolean replace){
        if(cutoutEditor==null||editingSticker==null)return;
        cutoutSaveButton.setEnabled(false);Bitmap[] finished=null;List<StickerRepo.Sticker> saved=new ArrayList<>();
        try{
            finished=CutoutOps.render(cutoutEditor.working(),cutoutEditor.sharpen(),cutoutEditor.contrast(),
                    cutoutEditor.saturation(),cutoutEditor.brightness(),cutoutEditor.angle(),cutoutEditor.splitX());
            StickerRepo.Sticker original=editingSticker;
            for(int i=0;i<finished.length;i++){
                Bitmap image=finished[i];float width=editingPpm>0?image.getWidth()/editingPpm:0,height=editingPpm>0?image.getHeight()/editingPpm:0;
                float sourceWidth=image.getWidth()*editingSourceRatio;
                if(replace&&finished.length==1)repo.update(original,image,width,height,sourceWidth);
                else{
                    String name=original.name+(finished.length==1?" · 副本":i==0?" · 画面左眼":" · 画面右眼");
                    saved.add(repo.save(image,name,width,height,original.sourcePhoto,sourceWidth,original.folderId));
                }
            }
            releaseCutoutEditor();editingSticker=null;showManager();
            Toast.makeText(this,replace?"贴纸修改已保存":"编辑后的副本已保存",Toast.LENGTH_SHORT).show();
        }catch(Exception e){
            for(StickerRepo.Sticker s:saved)try{repo.deleteSticker(s.id);}catch(Exception ignored){}
            alert("保存失败",e.getMessage());
        }finally{
            if(finished!=null)for(Bitmap image:finished)image.recycle();
            if(cutoutSaveButton!=null)cutoutSaveButton.setEnabled(true);
        }
    }

    private AlertDialog busy(String message){
        AlertDialog dialog=new AlertDialog.Builder(this).setMessage(message).create();dialog.setCancelable(false);dialog.show();return dialog;
    }
    private void saveSessionDialog(){
        String suggested=currentSessionName==null?"试贴 "+new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date()):currentSessionName;
        EditText input=nameInput(suggested);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("保存试贴会话").setMessage("保存同框照片、贴纸、位置、角度、锁定与网格，下次从首页继续。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null);
        if(currentSessionId!=null)builder.setNeutralButton("另存会话",null);
        AlertDialog dialog=builder.create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->saveSession(input,dialog,false));
        if(currentSessionId!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->saveSession(input,dialog,true));
    }
    private void saveSession(EditText input,AlertDialog dialog,boolean copy){
        String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("请输入会话名称");return;}
        final SessionRepo.Capture captured;
        try{captured=sessions.capture(editorBoard,faceFile.getAbsolutePath(),faceCalibration);}catch(Exception e){alert("会话未保存",e.getMessage());return;}
        String id=copy?null:currentSessionId;dialog.dismiss();AlertDialog progress=busy("正在保存现场…");
        cutoutWorker.execute(()->{
            String savedId=null;Exception error=null;
            try{savedId=sessions.save(id,name,captured);}catch(Exception e){error=e;}finally{captured.release();}
            String ready=savedId;Exception failure=error;
            cutoutHandler.post(()->{
                if(isDestroyed())return;progress.dismiss();
                if(failure!=null)alert("会话未保存",failure.getMessage());
                else{currentSessionId=ready;currentSessionName=name;Toast.makeText(this,"会话已保存，可从首页继续",Toast.LENGTH_LONG).show();}
            });
        });
    }
    private void loadSession(SessionRepo.Entry entry){
        AlertDialog progress=busy("正在恢复试贴现场…");
        cutoutWorker.execute(()->{
            SessionRepo.Loaded loaded=null;Exception error=null;
            try{loaded=sessions.load(entry.id);}catch(Exception e){error=e;}
            SessionRepo.Loaded ready=loaded;Exception failure=error;
            cutoutHandler.post(()->{
                if(isDestroyed()){if(ready!=null){ready.photo.recycle();for(Bitmap b:ready.stickers)b.recycle();}return;}
                progress.dismiss();if(failure!=null){alert("无法恢复会话",failure.getMessage());return;}
                try{
                    PhotoBoard restored=new PhotoBoard(this,ready.photo,PhotoBoard.EDIT);
                    restored.restore(ready.document.getJSONObject("board"),ready.stickers);
                    org.json.JSONObject c=ready.document.optJSONObject("calibration");
                    Calibration.Result calibration=c==null?null:new Calibration.Result((float)c.getDouble("ppm"),c.getInt("dots"),c.getBoolean("auto"));
                    faceBitmap=sourceBitmap=ready.photo;faceCalibration=sourceCalibration=calibration;
                    faceFile=new File(ready.document.optString("identity",new File(getFilesDir(),entry.id+".jpg").getAbsolutePath()));
                    editorBoard=restored;retainedCrop=null;currentSessionId=entry.id;currentSessionName=entry.name;
                    sourceName="表情 "+(repo.list().size()+1);showEditor();
                    Toast.makeText(this,"已恢复「"+entry.name+"」",Toast.LENGTH_SHORT).show();
                }catch(Exception e){ready.photo.recycle();for(Bitmap b:ready.stickers)b.recycle();alert("会话数据无法恢复",e.getMessage());}
            });
        });
    }
    private void deleteSession(SessionRepo.Entry entry){
        new AlertDialog.Builder(this).setTitle("删除「"+entry.name+"」？").setMessage("仅删除这个保存的会话，贴纸库中的素材会保留。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                try{sessions.delete(entry.id);if(entry.id.equals(currentSessionId)){currentSessionId=currentSessionName=null;}showHome();}
                catch(Exception e){alert("删除会话失败",e.getMessage());}
            }).show();
    }
    private void detectFaceOutline(PhotoBoard target,PointF seed,Button detect,Button reselect){
        if(detectingFace)return;detectingFace=true;detect.setEnabled(false);reselect.setEnabled(false);detect.setText("识别中…");
        float size=target.faceRegionSize();Bitmap photo=target.photoBitmap();
        cutoutWorker.execute(()->{
            List<PointF> points=null;Exception error=null;
            try{points=FaceOutline.detect(photo,seed,size);}catch(Exception e){error=e;}
            List<PointF> ready=points;Exception failure=error;
            cutoutHandler.post(()->{
                detectingFace=false;if(isDestroyed())return;
                detect.setEnabled(true);reselect.setEnabled(true);
                if(target!=editorBoard)return;
                if(failure==null){target.setOutline(ready);Toast.makeText(this,"面雕边界已描绘，可用于辅助定位",Toast.LENGTH_SHORT).show();}
                else Toast.makeText(this,"未找到清晰边界，请重选面雕中心后重试",Toast.LENGTH_LONG).show();
                detect.setText(target.hasOutline()?(target.outlineVisible()?"隐藏描边":"显示描边"):"识别面雕");
            });
        });
    }
    private void manualCalibration(boolean face){
        if(board==null)return;board.setMode(PhotoBoard.CALIBRATE);Toast.makeText(this,"依次点两个相邻圆点（相距 1 cm）",Toast.LENGTH_LONG).show();
        board.onCalibration((a,b)->{
            float d=(float)Math.hypot(a.x-b.x,a.y-b.y);
            if(d<15){Toast.makeText(this,"两点太近，请重试",Toast.LENGTH_SHORT).show();return;}
            Calibration.Result r=new Calibration.Result(d/10f,2,false);faceCalibration=r;sourceCalibration=r;
            board.setMode(face?PhotoBoard.EDIT:PhotoBoard.CROP);if(scaleLabel!=null)scaleLabel.setText((face?"同框比例 · ":"")+scaleText(r));
            Toast.makeText(this,"尺寸标定完成",Toast.LENGTH_SHORT).show();
        });
    }

    private void showEditor(){if(faceBitmap==null){showHome();return;}startScreen(2);header("试贴预览","选择图层后操作 · 照片位置固定",this::showHome);
        if(editorBoard==null){
            editorBoard=new PhotoBoard(this,faceBitmap,PhotoBoard.EDIT);
            Rect decal=BlueKey.suggest(faceBitmap);float center=decal.centerX()/(float)faceBitmap.getWidth();
            boolean separated=center>.62f||center<.38f;
            float targetX=center>.62f?faceBitmap.getWidth()*.26f:center<.38f?faceBitmap.getWidth()*.74f:faceBitmap.getWidth()*.5f;
            editorBoard.setPlacement(targetX,faceBitmap.getHeight()*(separated?.60f:.54f),faceBitmap.getWidth()*(separated?.35f:.5f));
        }
        board=editorBoard;board.setMode(PhotoBoard.EDIT);
        if(board.getParent() instanceof ViewGroup)((ViewGroup)board.getParent()).removeView(board);
        root.addView(board,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(280)));
        LinearLayout panel=column();panel.setPadding(dp(18),dp(14),dp(18),dp(20));panel.setBackground(background(Color.WHITE,20));scroll.addView(panel);
        scaleLabel=text("同框比例 · "+scaleText(faceCalibration),12,TEAL,true);panel.addView(scaleLabel);
        margin(text("选择操作图层",14,INK,true),panel,-2,9);
        HorizontalScrollView layerScroll=new HorizontalScrollView(this);layerScroll.setHorizontalScrollBarEnabled(false);
        margin(layerScroll,panel,46,5);LinearLayout layerRow=row();layerScroll.addView(layerRow);
        LinearLayout opacityGroup=column();margin(opacityGroup,panel,-2,8);
        TextView opacityLabel=text("贴纸不透明度  100%",13,INK,true);opacityGroup.addView(opacityLabel);
        SeekBar opacitySlider=new SeekBar(this);opacitySlider.setMax(100);margin(opacitySlider,opacityGroup,38,0);
        opacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int value,boolean user){if(user)board.setSelectedOpacity(value);}
            public void onStartTrackingTouch(SeekBar slider){board.beginHistoryTransaction();}
            public void onStopTrackingTouch(SeekBar slider){board.endHistoryTransaction();}
        });
        LinearLayout eyeGroup=column();margin(eyeGroup,panel,-2,9);
        LinearLayout eyeHeading=row();eyeGroup.addView(eyeHeading);
        eyeHeading.addView(text("整组左右眼 · 眼距",13,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button eyeToggle=button("开启调整",false);eyeHeading.addView(eyeToggle,new LinearLayout.LayoutParams(dp(104),dp(37)));
        LinearLayout eyeControls=column();margin(eyeControls,eyeGroup,-2,2);
        TextView spacingLabel=text("眼距  原始",12,INK,true);eyeControls.addView(spacingLabel);
        SeekBar spacingSlider=new SeekBar(this);spacingSlider.setMax(90);margin(spacingSlider,eyeControls,38,0);
        spacingSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int value,boolean user){if(user)board.setSelectedEyeSpacing(value-30);}
            public void onStartTrackingTouch(SeekBar slider){board.beginHistoryTransaction();}
            public void onStopTrackingTouch(SeekBar slider){board.endHistoryTransaction();}
        });
        LinearLayout axisHeading=row();eyeControls.addView(axisHeading);
        TextView axisLabel=text("轴线  50%",12,INK,true);axisHeading.addView(axisLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button autoAxis=button("自动找轴",false);axisHeading.addView(autoAxis,new LinearLayout.LayoutParams(dp(104),dp(35)));
        SeekBar axisSlider=new SeekBar(this);axisSlider.setMax(60);margin(axisSlider,eyeControls,38,0);
        axisSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int value,boolean user){if(user)board.setSelectedEyeAxis(value+20);}
            public void onStartTrackingTouch(SeekBar slider){board.beginHistoryTransaction();}
            public void onStopTrackingTouch(SeekBar slider){board.endHistoryTransaction();}
        });
        margin(text("橙色轴线只作定位参考；调整会改变两眼距离。",11,MUTED,false),eyeControls,-2,0);
        eyeToggle.setOnClickListener(v->board.setSelectedEyeSpacingEnabled(!board.selectedEyeSpacingEnabled()));
        autoAxis.setOnClickListener(v->{if(!board.autoSelectedEyeAxis())
            Toast.makeText(this,"没有找到明显眼间空隙，可手动移动轴线",Toast.LENGTH_LONG).show();});
        margin(text("贴纸和网格可从任意位置拖动；照片只可缩放、旋转。查看画布可平移视角。",11,MUTED,false),panel,-2,5);
        LinearLayout sessionTools=row();margin(sessionTools,panel,44,8);
        Button sessionSave=button("保存会话",true),outline=button("识别面雕",false),reselect=button("重选面雕",false);
        for(Button b:new Button[]{sessionSave,outline,reselect}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=dp(4);sessionTools.addView(b,p);
        }
        final PhotoBoard scene=board;
        ImageView undoTrial=iconButton(R.drawable.ic_undo,"撤回上一步试贴操作",INK);
        ((LinearLayout)root.getChildAt(0)).addView(undoTrial,new LinearLayout.LayoutParams(dp(44),dp(42)));
        Runnable refreshUndo=()->{boolean available=scene.canUndo();undoTrial.setEnabled(available);undoTrial.setAlpha(available?1f:.5f);};
        undoTrial.setOnClickListener(v->scene.undo());scene.onHistoryChanged(refreshUndo);refreshUndo.run();
        outline.setText(scene.hasOutline()?(scene.outlineVisible()?"隐藏描边":"显示描边"):"识别面雕");
        sessionSave.setOnClickListener(v->saveSessionDialog());
        outline.setOnClickListener(v->{
            if(scene.hasOutline()){scene.toggleOutline();outline.setText(scene.outlineVisible()?"隐藏描边":"显示描边");}
            else detectFaceOutline(scene,scene.faceSeed(),outline,reselect);
        });
        reselect.setOnClickListener(v->{
            Toast.makeText(this,"请在照片中点一下面雕的中部",Toast.LENGTH_LONG).show();
            scene.pickFace(point->detectFaceOutline(scene,point,outline,reselect));
        });
        LinearLayout outlineAdjust=column();margin(outlineAdjust,panel,-2,9);
        TextView outlineOffsetLabel=text("描边范围  原始",13,INK,true);outlineAdjust.addView(outlineOffsetLabel);
        SeekBar outlineOffsetSlider=new SeekBar(this);outlineOffsetSlider.setMax(200);margin(outlineOffsetSlider,outlineAdjust,38,2);
        margin(text("向左收紧、向右扩大；只影响勾边与透明面雕导出。",11,MUTED,false),outlineAdjust,-2,0);
        outlineOffsetSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int value,boolean user){if(user)scene.setOutlineOffset((value-100)*scene.maxOutlineOffset()/100f);}
            public void onStartTrackingTouch(SeekBar slider){scene.beginHistoryTransaction();}
            public void onStopTrackingTouch(SeekBar slider){scene.endHistoryTransaction();}
        });
        LinearLayout gridRow=row();margin(gridRow,panel,42,10);gridRow.addView(text("网格",13,INK,true));
        int[] counts={0,3,4,5,6};String[] names={"隐藏","3×3","4×4","5×5","6×6"};Button[] gridButtons=new Button[counts.length];
        for(int i=0;i<counts.length;i++){
            final int n=counts[i];Button b=button(names[i],false);gridButtons[i]=b;
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.leftMargin=dp(4);gridRow.addView(b,p);
            b.setOnClickListener(v->{board.setGrid(n);for(int j=0;j<counts.length;j++)markSelected(gridButtons[j],counts[j]==n);});
            markSelected(b,board.getGrid()==n);
        }
        LinearLayout tools=row();margin(tools,panel,42,8);
        Button calibrate=button("标定",false),smaller=button("－",false),bigger=button("＋",false),reset=button("画面复位",false);
        ImageView remove=iconButton(R.drawable.ic_delete_outline,"移除选中的贴纸",INK);
        for(View control:new View[]{calibrate,smaller,bigger,reset,remove}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),control==reset?1.35f:1);p.rightMargin=dp(3);tools.addView(control,p);
        }
        calibrate.setOnClickListener(v->manualCalibration(true));smaller.setOnClickListener(v->board.scaleSelected(.9f));bigger.setOnClickListener(v->board.scaleSelected(1.1f));
        reset.setOnClickListener(v->{if(board.activeTarget()==PhotoBoard.TARGET_PHOTO)board.resetPhoto();else board.resetViewport();});
        remove.setOnClickListener(v->board.removeSelected());
        LinearLayout coordinateGroup=column();margin(coordinateGroup,panel,-2,10);
        TextView coordinateHint=text("单轴定位 · 原照片坐标",12,TEAL,true);coordinateGroup.addView(coordinateHint);
        TextView xLabel=text("X",12,INK,true),yLabel=text("Y",12,INK,true);
        SeekBar xSlider=new SeekBar(this),ySlider=new SeekBar(this);
        xSlider.setMax(board.photoWidth()*2);ySlider.setMax(board.photoHeight()*2);
        margin(xLabel,coordinateGroup,-2,4);margin(xSlider,coordinateGroup,36,0);
        margin(yLabel,coordinateGroup,-2,3);margin(ySlider,coordinateGroup,36,0);
        SeekBar[] positions={xSlider,ySlider};
        for(int i=0;i<positions.length;i++){
            final boolean horizontal=i==0;
            positions[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar slider,int p,boolean user){if(user)scene.setSelectedCoordinate(horizontal,p/2f);}
                public void onStartTrackingTouch(SeekBar slider){scene.beginHistoryTransaction();}
                public void onStopTrackingTouch(SeekBar slider){scene.endHistoryTransaction();}
            });
        }
        LinearLayout rotationGroup=column();margin(rotationGroup,panel,-2,8);
        LinearLayout angleHeading=row();rotationGroup.addView(angleHeading);
        TextView rotationLabel=text("旋转  0.0°",12,INK,true);angleHeading.addView(rotationLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button zeroAngle=button("角度归零",false);angleHeading.addView(zeroAngle,new LinearLayout.LayoutParams(dp(100),dp(35)));
        AngleKnob rotationDial=new AngleKnob(this);margin(rotationDial,rotationGroup,96,1);
        rotationDial.onChange(scene::setSelectedAngle);rotationDial.onDialGesture(scene::beginHistoryTransaction,scene::endHistoryTransaction);
        zeroAngle.setOnClickListener(v->scene.setSelectedAngle(0));
        margin(text("缓慢上下滑动旋钮；两侧按钮每次调整 0.5°。",11,MUTED,false),rotationGroup,-2,2);
        Runnable syncTransform=()->{
            float x=scene.selectedX(),y=scene.selectedY(),angle=scene.selectedAngle();
            xLabel.setText(String.format(java.util.Locale.CHINA,"X  %.1f px · 水平位置",x));
            yLabel.setText(String.format(java.util.Locale.CHINA,"Y  %.1f px · 垂直位置",y));
            xSlider.setProgress(Math.round(x*2));ySlider.setProgress(Math.round(y*2));
            rotationDial.setValue(angle);rotationLabel.setText(String.format(java.util.Locale.CHINA,"旋转  %+.1f°",angle));
            int opacity=scene.selectedOpacity();opacityLabel.setText("贴纸不透明度  "+opacity+"%");opacitySlider.setProgress(opacity);
            int spacing=scene.selectedEyeSpacing(),axis=scene.selectedEyeAxis();
            spacingLabel.setText(spacing==0?"眼距  原始":String.format(java.util.Locale.CHINA,"眼距  %+d%%",spacing));
            spacingSlider.setProgress(spacing+30);
            axisLabel.setText("轴线  "+axis+"% · "+(scene.selectedEyeAxisAutomatic()?"自动":"手动"));
            axisSlider.setProgress(axis-20);
        };
        LinearLayout libraryHeading=row();margin(libraryHeading,panel,39,11);
        libraryHeading.addView(text("添加贴纸",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        Button manage=button("管理贴纸",false);libraryHeading.addView(manage,new LinearLayout.LayoutParams(dp(100),dp(37)));manage.setOnClickListener(v->openManager());
        HorizontalScrollView folderScroll=new HorizontalScrollView(this);folderScroll.setHorizontalScrollBarEnabled(false);margin(folderScroll,panel,40,5);
        LinearLayout folderRow=row();folderScroll.addView(folderRow);addFolderFilters(folderRow,false);
        HorizontalScrollView libraryScroll=new HorizontalScrollView(this);libraryScroll.setHorizontalScrollBarEnabled(false);margin(libraryScroll,panel,78,6);libraryRow=row();libraryScroll.addView(libraryRow);refreshLibrary();
        LinearLayout actions=row();actions.setPadding(dp(14),dp(5),dp(14),dp(8));actions.setBackgroundColor(Color.WHITE);
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(72)));
        FooterAction view=footerAction(R.drawable.ic_pan_tool_alt,"查看","查看画布，拖动观察视角");
        FooterAction add=footerAction(R.drawable.ic_add_circle_outline,"新贴纸","制作新贴纸");
        FooterAction lock=footerAction(R.drawable.ic_lock_open,"锁定","锁定选中的贴纸或网格");
        FooterAction save=footerAction(R.drawable.ic_file_download,"导出","导出预览图");
        for(FooterAction action:new FooterAction[]{view,add,lock,save}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=action==save?0:dp(6);actions.addView(action.view,p);
        }
        view.view.setOnClickListener(v->board.selectView());
        styleFooter(save,false,true);
        final int[] displayedTarget={-1},displayedSticker={-1};
        Runnable refreshControls=()->{
            if(board!=scene||screen!=2)return;
            int targetBefore=displayedTarget[0],stickerBefore=displayedSticker[0];
            layerRow.removeAllViews();
            String[] baseNames={"背景照片","参考网格"};
            for(int i=0;i<baseNames.length;i++){
                final int target=i+PhotoBoard.TARGET_PHOTO;Button chip=button(baseNames[i],false);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(100),dp(42));cp.rightMargin=dp(7);
                layerRow.addView(chip,cp);markSelected(chip,board.activeTarget()==target);
                chip.setOnClickListener(v->{if(target==PhotoBoard.TARGET_PHOTO)board.selectPhoto();else board.selectGrid();});
            }
            for(int i=0;i<board.stickerCount();i++){
                final int index=i;String name=board.stickerName(i);
                if(name.length()>7)name=name.substring(0,7)+"…";
                Button chip=button("贴纸 "+(i+1)+" "+name,false);
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(116),dp(42));cp.rightMargin=dp(7);
                layerRow.addView(chip,cp);markSelected(chip,board.activeStickerIndex()==i);
                chip.setOnClickListener(v->board.selectSticker(index));
            }
            int target=board.activeTarget();boolean sticker=board.hasSelection(),gridTarget=target==PhotoBoard.TARGET_GRID;
            displayedTarget[0]=target;displayedSticker[0]=board.activeStickerIndex();
            if(target!=targetBefore||displayedSticker[0]!=stickerBefore){
                int scrollX=sticker?dp(200+116*displayedSticker[0]):0;
                layerScroll.post(()->layerScroll.smoothScrollTo(scrollX,0));
            }
            boolean locked=gridTarget?board.isGridLocked():sticker&&board.isSelectedLocked();
            opacityGroup.setVisibility(sticker?View.VISIBLE:View.GONE);
            eyeGroup.setVisibility(sticker?View.VISIBLE:View.GONE);
            boolean eyeOn=sticker&&scene.selectedEyeSpacingEnabled();eyeControls.setVisibility(eyeOn?View.VISIBLE:View.GONE);
            eyeToggle.setText(eyeOn?"关闭调整":"开启调整");markSelected(eyeToggle,eyeOn);
            eyeToggle.setEnabled(sticker&&!locked);spacingSlider.setEnabled(eyeOn&&!locked);
            axisSlider.setEnabled(eyeOn&&!locked);autoAxis.setEnabled(eyeOn&&!locked);
            boolean lockable=gridTarget||sticker;
            lock.view.setEnabled(lockable);lock.view.setAlpha(lockable?1f:.45f);
            lock.icon.setImageResource(locked?R.drawable.ic_lock:R.drawable.ic_lock_open);
            lock.label.setText(locked?"已锁":"锁定");
            String lockDescription=gridTarget?(locked?"解锁网格":"锁定网格"):(locked?"解锁贴纸":"锁定贴纸");
            lock.view.setContentDescription(lockDescription);lock.view.setTooltipText(lockDescription);
            styleFooter(lock,locked,false);styleFooter(view,target==PhotoBoard.TARGET_VIEW,false);
            boolean adjustable=!locked;
            for(Button b:new Button[]{smaller,bigger}){b.setEnabled(adjustable);b.setAlpha(adjustable?1f:.55f);}
            remove.setEnabled(sticker&&!locked);remove.setAlpha(sticker&&!locked?1f:.55f);
            reset.setText(target==PhotoBoard.TARGET_PHOTO?"照片复位":"画面复位");
            boolean canRotate=(target==PhotoBoard.TARGET_PHOTO||sticker)&&!locked;
            rotationGroup.setVisibility(target==PhotoBoard.TARGET_PHOTO||sticker?View.VISIBLE:View.GONE);
            rotationDial.setEnabled(canRotate);zeroAngle.setEnabled(canRotate);
            coordinateGroup.setVisibility(sticker||gridTarget?View.VISIBLE:View.GONE);
            coordinateHint.setText(locked?"已锁定 · 解锁后可调整坐标":"单轴定位 · 原照片坐标");
            xSlider.setEnabled(!locked);ySlider.setEnabled(!locked);
            outline.setText(scene.hasOutline()?(scene.outlineVisible()?"隐藏描边":"显示描边"):"识别面雕");
            outlineAdjust.setVisibility(scene.hasOutline()?View.VISIBLE:View.GONE);
            outlineOffsetSlider.setProgress(Math.round(100+100*scene.outlineOffset()/scene.maxOutlineOffset()));
            outlineOffsetLabel.setText(Math.abs(scene.outlineOffset())<.1f?"描边范围  原始":String.format(java.util.Locale.CHINA,"描边范围  %+.1f px",scene.outlineOffset()));
            for(int i=0;i<counts.length;i++)markSelected(gridButtons[i],counts[i]==scene.getGrid());
            syncTransform.run();
        };
        board.onSelectionChanged(refreshControls);board.onTransformChanged(syncTransform);refreshControls.run();
        lock.view.setOnClickListener(v->{
            if(board.activeTarget()==PhotoBoard.TARGET_GRID)board.toggleGridLocked();
            else board.toggleSelectedLocked();
        });
        add.view.setOnClickListener(v->newStickerDialog());save.view.setOnClickListener(v->exportPreview());
    }
    private void markSelected(Button button,boolean selected){
        button.setBackground(outlinedSurface(selected?TEAL:Color.WHITE,selected?TEAL:LINE,9));
        button.setTextColor(selected?Color.WHITE:INK);
    }
    private void refreshLibrary(){
        if(libraryRow==null)return;libraryRow.removeAllViews();int count=0;
        for(StickerRepo.Sticker s:repo.list()){
            if(libraryFilter!=null&&!libraryFilter.equals(s.folderId))continue;
            View tile=stickerTile(s);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(80),dp(74));p.rightMargin=dp(7);
            libraryRow.addView(tile,p);tile.setOnClickListener(v->addSticker(s));count++;
        }
        if(count==0)libraryRow.addView(text("此文件夹还没有贴纸，可去管理页导入或移动",12,MUTED,false));
    }
    private void addFolderFilters(LinearLayout target,boolean manager){
        target.removeAllViews();List<StickerRepo.Folder> folders=repo.folders();
        if(manager&&managerFilter!=null&&!managerFilter.isEmpty()&&folders.stream().noneMatch(f->f.id.equals(managerFilter)))managerFilter=null;
        if(!manager&&libraryFilter!=null&&!libraryFilter.isEmpty()&&folders.stream().noneMatch(f->f.id.equals(libraryFilter)))libraryFilter=null;
        List<String> ids=new ArrayList<>(),names=new ArrayList<>();ids.add(null);names.add("全部");ids.add("");names.add("未分类");
        for(StickerRepo.Folder f:folders){ids.add(f.id);names.add(f.name);}
        for(int i=0;i<ids.size();i++){
            String id=ids.get(i);Button chip=button(names.get(i),false);
            boolean active=java.util.Objects.equals(manager?managerFilter:libraryFilter,id);markSelected(chip,active);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(38));p.rightMargin=dp(6);target.addView(chip,p);
            chip.setOnClickListener(v->{
                if(manager){managerFilter=id;showManager();}
                else{libraryFilter=id;addFolderFilters(target,false);refreshLibrary();}
            });
            if(manager&&i>=2){StickerRepo.Folder folder=folders.get(i-2);chip.setOnLongClickListener(v->{folderOptions(folder);return true;});}
        }
    }
    private void openManager(){managerReturnScreen=screen==2?2:0;managerFilter=null;managerQuery="";managerSelecting=false;managerSelected.clear();showManager();}
    private void returnFromManager(){if(managerReturnScreen==2)showEditor();else showHome();}
    private void managerBack(){
        if(managerSelecting){managerSelecting=false;managerSelected.clear();showManager();return;}
        if(managerFilter!=null){managerFilter=null;managerQuery="";showManager();return;}
        returnFromManager();
    }
    private String folderName(String id){
        if(MANAGER_ALL.equals(id))return "全部贴纸";
        if(id==null||id.isEmpty())return "未分类";
        for(StickerRepo.Folder f:repo.folders())if(f.id.equals(id))return f.name;
        return "未分类";
    }
    private void showManager(){
        if(managerFilter!=null&&!managerFilter.isEmpty()&&!MANAGER_ALL.equals(managerFilter)
                &&repo.folders().stream().noneMatch(f->f.id.equals(managerFilter)))managerFilter=null;
        startScreen(3);header(managerFilter==null?"贴纸管理":folderName(managerFilter),
                managerFilter==null?"文件夹聚合 · 搜索贴纸或文件夹":"贴纸库 / "+folderName(managerFilter),this::managerBack);
        LinearLayout actions=row();actions.setPadding(dp(18),dp(2),dp(18),dp(8));root.addView(actions,new LinearLayout.LayoutParams(-1,dp(58)));
        Button importButton=button("导入贴纸",true),folderButton=button("新建文件夹",false),selectButton=button(managerSelecting?"完成":"多选",false);
        actions.addView(importButton,new LinearLayout.LayoutParams(0,-1,1.1f));
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-1,1.1f);fp.leftMargin=dp(7);actions.addView(folderButton,fp);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-1,.8f);sp.leftMargin=dp(7);actions.addView(selectButton,sp);
        importButton.setOnClickListener(v->importChoiceDialog());folderButton.setOnClickListener(v->folderNameDialog(null));
        LinearLayout search=row();search.setPadding(dp(11),0,dp(11),0);search.setBackground(outlinedSurface(Color.WHITE,LINE,9));
        LinearLayout.LayoutParams searchParams=lp(-1,dp(48),5);searchParams.leftMargin=dp(18);searchParams.rightMargin=dp(18);root.addView(search,searchParams);
        ImageView searchIcon=new ImageView(this);searchIcon.setImageResource(R.drawable.ic_search);searchIcon.setImageTintList(ColorStateList.valueOf(MUTED));search.addView(searchIcon,new LinearLayout.LayoutParams(dp(22),dp(22)));
        EditText query=new EditText(this);query.setSingleLine(true);query.setTextSize(15);query.setTextColor(INK);query.setHintTextColor(MUTED);
        query.setHint(managerFilter==null?"搜索文件夹或贴纸":"搜索此处贴纸");query.setBackgroundColor(Color.TRANSPARENT);
        query.setPadding(dp(10),0,0,0);search.addView(query,new LinearLayout.LayoutParams(0,-1,1));query.setText(managerQuery);query.setSelection(query.length());
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=column();content.setPadding(dp(18),dp(12),dp(18),dp(22));scroll.addView(content);
        LinearLayout selectionPanel=column();selectionPanel.setPadding(dp(14),dp(5),dp(14),dp(8));selectionPanel.setBackgroundColor(Color.WHITE);
        root.addView(selectionPanel,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout selectionHeading=row();selectionPanel.addView(selectionHeading,new LinearLayout.LayoutParams(-1,dp(40)));
        TextView count=text("已选 0 张",13,INK,true);selectionHeading.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        Button all=button("全选",false);selectionHeading.addView(all,new LinearLayout.LayoutParams(dp(82),dp(38)));
        LinearLayout batch=row();selectionPanel.addView(batch,new LinearLayout.LayoutParams(-1,dp(63)));
        FooterAction move=footerAction(R.drawable.ic_folder,"移动","批量移动贴纸"),copy=footerAction(R.drawable.ic_content_copy,"另存","批量另存副本"),
                export=footerAction(R.drawable.ic_file_download,"导出","批量导出 PNG"),delete=footerAction(R.drawable.ic_delete_outline,"删除","批量删除贴纸");
        for(FooterAction action:new FooterAction[]{move,copy,export,delete}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.rightMargin=action==delete?0:dp(6);batch.addView(action.view,p);
        }
        Runnable[] refresh={null};refresh[0]=()->{
            renderManagerContent(content,refresh[0]);
            selectionPanel.setVisibility(managerSelecting?View.VISIBLE:View.GONE);
            selectButton.setText(managerSelecting?"完成":"多选");count.setText("已选 "+managerSelected.size()+" 张");
            boolean hasAny=!managerSelected.isEmpty();
            for(FooterAction action:new FooterAction[]{move,copy,export,delete}){action.view.setEnabled(hasAny);action.view.setAlpha(hasAny?1f:.45f);}
        };
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){managerQuery=s.toString();refresh[0].run();}
            public void afterTextChanged(Editable value){}
        });
        selectButton.setOnClickListener(v->{
            if(managerFilter==null&&!managerSelecting&&managerQuery.trim().isEmpty()){
                managerFilter=MANAGER_ALL;managerSelecting=true;showManager();return;
            }
            managerSelecting=!managerSelecting;if(!managerSelecting)managerSelected.clear();refresh[0].run();
        });
        all.setOnClickListener(v->{List<StickerRepo.Sticker> visible=managerVisibleStickers();boolean allSelected=true;
            for(StickerRepo.Sticker s:visible)if(!managerSelected.contains(s.id)){allSelected=false;break;}
            for(StickerRepo.Sticker s:visible){if(allSelected)managerSelected.remove(s.id);else managerSelected.add(s.id);}refresh[0].run();});
        move.view.setOnClickListener(v->batchMoveDialog());copy.view.setOnClickListener(v->batchCopyDialog());
        export.view.setOnClickListener(v->batchExport());delete.view.setOnClickListener(v->batchDeleteDialog());
        refresh[0].run();
    }
    private boolean managerMatch(String name){return name.toLowerCase(java.util.Locale.ROOT).contains(managerQuery.trim().toLowerCase(java.util.Locale.ROOT));}
    private List<StickerRepo.Sticker> managerVisibleStickers(){
        List<StickerRepo.Sticker> result=new ArrayList<>();
        for(StickerRepo.Sticker s:repo.list())if((managerFilter==null||MANAGER_ALL.equals(managerFilter)||managerFilter.equals(s.folderId))&&managerMatch(s.name))result.add(s);
        return result;
    }
    private void renderManagerContent(LinearLayout content,Runnable refresh){
        content.removeAllViews();List<StickerRepo.Sticker> all=repo.list();
        if(managerFilter==null){
            margin(text("文件夹",17,INK,true),content,-2,3);
            List<String> ids=new ArrayList<>();List<String> names=new ArrayList<>();
            ids.add(MANAGER_ALL);names.add("全部贴纸");ids.add("");names.add("未分类");
            for(StickerRepo.Folder f:repo.folders()){ids.add(f.id);names.add(f.name);}
            int shown=0;
            for(int i=0;i<ids.size();i+=2){
                LinearLayout line=row();boolean lineHas=false;
                for(int j=i;j<Math.min(i+2,ids.size());j++){
                    String id=ids.get(j),name=names.get(j);if(!managerMatch(name))continue;
                    List<StickerRepo.Sticker> contained=new ArrayList<>();for(StickerRepo.Sticker s:all)if(MANAGER_ALL.equals(id)||id.equals(s.folderId))contained.add(s);
                    View tile=managerFolderTile(id,name,contained);
                    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(168),1);if(lineHas)p.leftMargin=dp(10);line.addView(tile,p);lineHas=true;shown++;
                }
                if(lineHas){if(line.getChildCount()==1){View spacer=new View(this);line.addView(spacer,new LinearLayout.LayoutParams(0,1,1));}content.addView(line,lp(-1,dp(168),10));}
            }
            if(shown==0)margin(text("没有匹配的文件夹",13,MUTED,false),content,-2,12);
            if(!managerQuery.trim().isEmpty()){
                List<StickerRepo.Sticker> found=managerVisibleStickers();
                margin(text("贴纸搜索结果 · "+found.size(),17,INK,true),content,-2,22);
                renderManagerStickers(content,found,refresh);
            }else margin(text("打开文件夹查看贴纸；长按文件夹可导出 ZIP 或管理。",12,MUTED,false),content,-2,19);
            return;
        }
        if(!managerFilter.isEmpty()&&!MANAGER_ALL.equals(managerFilter)){
            LinearLayout heading=row();margin(heading,content,37,0);
            heading.addView(text("仅支持一层文件夹",12,MUTED,false),new LinearLayout.LayoutParams(0,-2,1));
            Button options=button("文件夹设置",false);heading.addView(options,new LinearLayout.LayoutParams(dp(110),dp(36)));
            for(StickerRepo.Folder f:repo.folders())if(f.id.equals(managerFilter)){options.setOnClickListener(v->folderOptions(f));break;}
        }
        List<StickerRepo.Sticker> stickers=managerVisibleStickers();
        margin(text(folderName(managerFilter)+" · "+stickers.size()+" 张",17,INK,true),content,-2,8);
        renderManagerStickers(content,stickers,refresh);
    }
    private void renderManagerStickers(LinearLayout content,List<StickerRepo.Sticker> stickers,Runnable refresh){
        if(stickers.isEmpty()){TextView empty=text(managerQuery.trim().isEmpty()?"这里还没有贴纸，可从相册导入。":"没有匹配的贴纸",14,MUTED,false);
            empty.setGravity(Gravity.CENTER);empty.setPadding(dp(22),dp(30),dp(22),dp(30));margin(empty,content,-2,10);return;}
        for(int i=0;i<stickers.size();i+=2){
            LinearLayout line=row();content.addView(line,lp(-1,dp(183),10));
            for(int j=i;j<Math.min(i+2,stickers.size());j++){
                View tile=managerTile(stickers.get(j),refresh);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
                if(j>i)p.leftMargin=dp(10);line.addView(tile,p);
            }
            if(line.getChildCount()==1){View spacer=new View(this);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,1,1);p.leftMargin=dp(10);line.addView(spacer,p);}
        }
    }
    private View managerFolderTile(String id,String name,List<StickerRepo.Sticker> stickers){
        LinearLayout tile=card();tile.setPadding(dp(11),dp(10),dp(11),dp(10));
        LinearLayout preview=row();preview.setBackground(background(0xFFF0F4F2,11));tile.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        if(stickers.isEmpty()){
            ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_folder);icon.setImageTintList(ColorStateList.valueOf(TEAL));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(44),dp(44));preview.setGravity(Gravity.CENTER);preview.addView(icon,ip);
        }else{
            for(int i=0;i<Math.min(3,stickers.size());i++){
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setPadding(dp(4),dp(4),dp(4),dp(4));
                LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-1,1);preview.addView(image,ip);
                try{image.setImageBitmap(ImageTools.load(repo.file(stickers.get(i).id),180));}catch(Exception ignored){}
            }
        }
        LinearLayout caption=row();margin(caption,tile,31,6);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_folder);icon.setImageTintList(ColorStateList.valueOf(TEAL));caption.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
        TextView title=text(name,14,INK,true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(5);caption.addView(title,tp);
        caption.addView(text(String.valueOf(stickers.size()),12,MUTED,false));
        tile.setContentDescription(name+"，"+stickers.size()+" 张贴纸");tile.setClickable(true);tile.setOnClickListener(v->{managerFilter=id;managerQuery="";managerSelecting=false;managerSelected.clear();showManager();});
        if(MANAGER_ALL.equals(id)||id.isEmpty())tile.setOnLongClickListener(v->{
            new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"导出整个文件夹 ZIP"},(d,w)->exportZip(stickers,name)).show();return true;
        });
        else for(StickerRepo.Folder f:repo.folders())if(f.id.equals(id)){
            tile.setOnLongClickListener(v->{folderOptions(f);return true;});break;
        }
        return tile;
    }
    private View managerTile(StickerRepo.Sticker sticker,Runnable refresh){
        boolean selected=managerSelected.contains(sticker.id);
        LinearLayout tile=card();tile.setPadding(dp(10),dp(9),dp(10),dp(8));tile.setBackground(outlinedSurface(selected?0xFFDCF2EC:Color.WHITE,selected?TEAL:LINE,12));
        ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(background(0xFFF3F5F3,10));tile.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        try{image.setImageBitmap(ImageTools.load(repo.file(sticker.id),320));}catch(Exception ignored){}
        TextView name=text((selected?"✓  ":"")+sticker.name,14,INK,true);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);margin(name,tile,-2,8);
        TextView folder=text(folderName(sticker.folderId),11,MUTED,false);folder.setSingleLine(true);folder.setEllipsize(android.text.TextUtils.TruncateAt.END);margin(folder,tile,-2,2);
        tile.setContentDescription(sticker.name+(selected?"，已选中":"，点击查看详情，长按多选"));
        tile.setOnClickListener(v->{if(managerSelecting){if(!managerSelected.add(sticker.id))managerSelected.remove(sticker.id);refresh.run();}else stickerDetail(sticker);});
        tile.setOnLongClickListener(v->{managerSelecting=true;managerSelected.add(sticker.id);refresh.run();return true;});return tile;
    }
    private String detailDate(long time){return time<=0?"未知":new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.CHINA).format(new java.util.Date(time));}
    private void stickerDetail(StickerRepo.Sticker sticker){
        File file=repo.file(sticker.id);LinearLayout content=column();content.setPadding(dp(20),dp(7),dp(20),dp(8));
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(background(0xFFF0F4F2,10));content.addView(preview,new LinearLayout.LayoutParams(-1,dp(250)));
        Bitmap image=null;try{image=ImageTools.load(file,1600);preview.setImageBitmap(image);}catch(Exception ignored){}
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        margin(text(sticker.name,18,INK,true),content,-2,14);
        String size=options.outWidth>0?options.outWidth+" × "+options.outHeight+" px":"未知尺寸";
        String properties="位置  "+folderName(sticker.folderId)+"\n图片  "+size+" · "+String.format(java.util.Locale.CHINA,"%.1f KB",file.length()/1024f)
                +"\n创建  "+detailDate(sticker.createdAt)+"\n修改  "+detailDate(sticker.updatedAt);
        if(sticker.widthMm>0&&sticker.heightMm>0)properties+="\n标定  "+String.format(java.util.Locale.CHINA,"%.1f × %.1f mm",sticker.widthMm,sticker.heightMm);
        TextView info=text(properties,13,MUTED,false);info.setLineSpacing(dp(8),1);margin(info,content,-2,9);
        Bitmap shown=image;AlertDialog dialog=new AlertDialog.Builder(this).setTitle("贴纸详情").setView(content)
                .setNegativeButton("关闭",null).setPositiveButton("更多操作",(d,w)->stickerOptions(sticker)).create();
        dialog.setOnDismissListener(d->{preview.setImageDrawable(null);if(shown!=null&&!shown.isRecycled())shown.recycle();});dialog.show();
    }
    private List<String> selectedStickerIds(){return new ArrayList<>(managerSelected);}
    private void finishManagerBatch(String message){managerSelected.clear();managerSelecting=false;showManager();Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void batchMoveDialog(){
        List<String> ids=selectedStickerIds();if(ids.isEmpty())return;
        List<StickerRepo.Folder> folders=repo.folders();String[] names=new String[folders.size()+1];names[0]="未分类";
        for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        new AlertDialog.Builder(this).setTitle("移动 "+ids.size()+" 张贴纸到").setItems(names,(d,which)->{
            try{repo.moveStickers(ids,which==0?"":folders.get(which-1).id);finishManagerBatch("已移动 "+ids.size()+" 张贴纸");}
            catch(Exception e){alert("移动失败",e.getMessage());}
        }).setNegativeButton("取消",null).show();
    }
    private void batchCopyDialog(){
        List<String> ids=selectedStickerIds();if(ids.isEmpty())return;
        List<StickerRepo.Folder> folders=repo.folders();String[] names=new String[folders.size()+1];names[0]="未分类";
        for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        new AlertDialog.Builder(this).setTitle("另存 "+ids.size()+" 张副本到").setItems(names,(d,which)->{
            try{repo.copyStickers(ids,which==0?"":folders.get(which-1).id);finishManagerBatch("已另存 "+ids.size()+" 张副本");}
            catch(Exception e){alert("另存失败",e.getMessage());}
        }).setNegativeButton("取消",null).show();
    }
    private void batchDeleteDialog(){
        List<String> ids=selectedStickerIds();if(ids.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("删除 "+ids.size()+" 张贴纸？").setMessage("会从贴纸库永久删除这些图片。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                int removed=0;String error=null;
                for(String id:ids)try{repo.deleteSticker(id);removed++;}catch(Exception e){error=e.getMessage();break;}
                if(error==null)finishManagerBatch("已删除 "+removed+" 张贴纸");
                else{managerSelected.clear();managerSelecting=false;showManager();alert("部分删除失败",removed+" 张已删除。"+error);}
            }).show();
    }
    private void batchExport(){
        List<String> ids=selectedStickerIds();if(ids.isEmpty()||exporting)return;
        new AlertDialog.Builder(this).setTitle("导出 "+ids.size()+" 张贴纸")
            .setItems(new String[]{"逐一导出 PNG","打包为 ZIP"},(d,which)->{
                if(which==0)exportPng(ids);else{
                    List<StickerRepo.Sticker> selected=new ArrayList<>();
                    for(String id:ids)for(StickerRepo.Sticker s:repo.list())if(s.id.equals(id)){selected.add(s);break;}
                    exportZip(selected,"所选贴纸");
                }
            }).setNegativeButton("取消",null).show();
    }
    private void exportPng(List<String> ids){
        if(ids.isEmpty()||exporting)return;
        exporting=true;Toast.makeText(this,"正在导出 "+ids.size()+" 张 PNG…",Toast.LENGTH_SHORT).show();
        managerWorker.submit(()->{
            int saved=0;String error=null;
            for(String id:ids){
                Bitmap bitmap=null;
                try{
                    StickerRepo.Sticker item=null;for(StickerRepo.Sticker s:repo.list())if(s.id.equals(id)){item=s;break;}
                    if(item==null)throw new Exception("贴纸已不存在");
                    bitmap=ImageTools.load(repo.file(id),4096);
                    String safe=item.name.replaceAll("[\\\\/:*?\"<>|]","_").trim();if(safe.isEmpty())safe="贴纸";
                    if(safe.length()>35)safe=safe.substring(0,35);
                    String name="水贴_"+safe+"_"+id.substring(0,8)+".png";
                    exports.save(bitmap,name);saved++;
                }catch(Exception e){error=e.getMessage();break;}
                finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
            }
            final int count=saved;final String failure=error;
            runOnUiThread(()->{exporting=false;
                if(failure==null)finishManagerBatch("已导出 "+count+" 张到 "+exports.directoryLabel());
                else alert("导出未完成",count+" 张已导出；"+failure);
            });
        });
    }
    private void exportZip(List<StickerRepo.Sticker> stickers,String label){
        if(stickers.isEmpty()){Toast.makeText(this,"文件夹里没有贴纸",Toast.LENGTH_SHORT).show();return;}
        if(exporting)return;
        exporting=true;Toast.makeText(this,"正在打包 "+stickers.size()+" 张贴纸…",Toast.LENGTH_SHORT).show();
        managerWorker.submit(()->{
            File temp=new File(getCacheDir(),"sticker_export_"+UUID.randomUUID()+".zip");String error=null;
            try{
                StickerArchive.writeZip(repo,stickers,temp);
                String safe=label.replaceAll("[\\\\/:*?\"<>|]","_").trim();if(safe.isEmpty())safe="贴纸";
                if(safe.length()>30)safe=safe.substring(0,30);
                String name="水贴_"+safe+"_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.CHINA).format(new java.util.Date())+".zip";
                exports.saveZip(temp,name);
            }catch(Exception e){error=e.getMessage();}finally{temp.delete();}
            final String failure=error;
            runOnUiThread(()->{exporting=false;
                if(failure==null){if(screen==3)finishManagerBatch("ZIP 已保存到 "+exports.directoryLabel());
                    else Toast.makeText(this,"ZIP 已保存到 "+exports.directoryLabel(),Toast.LENGTH_LONG).show();}
                else alert("打包失败",failure);
            });
        });
    }
    private void stickerOptions(StickerRepo.Sticker sticker){
        List<String> options=new ArrayList<>();if(faceBitmap!=null)options.add("加入当前试贴");
        options.add("再次编辑");options.add("重命名");options.add("移动到文件夹");options.add("另存副本");options.add("导出 PNG");options.add("删除贴纸");
        new AlertDialog.Builder(this).setTitle(sticker.name).setItems(options.toArray(new String[0]),(dialog,which)->{
            String action=options.get(which);
            if(action.equals("加入当前试贴")){showEditor();addSticker(sticker);}
            else if(action.equals("再次编辑"))editLibrarySticker(sticker);
            else if(action.equals("重命名"))renameStickerDialog(sticker);
            else if(action.equals("移动到文件夹"))moveStickerDialog(sticker);
            else if(action.equals("另存副本"))copyStickerDialog(sticker);
            else if(action.equals("导出 PNG"))exportPng(java.util.Collections.singletonList(sticker.id));
            else deleteStickerDialog(sticker);
        }).show();
    }
    private EditText nameInput(String initial){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(initial);input.selectAll();
        input.setPadding(dp(14),dp(12),dp(14),dp(12));return input;
    }
    private void renameStickerDialog(StickerRepo.Sticker sticker){
        EditText input=nameInput(sticker.name);
        new AlertDialog.Builder(this).setTitle("重命名贴纸").setView(input).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w)->{try{repo.renameSticker(sticker.id,input.getText().toString());showManager();}catch(Exception e){alert("重命名失败",e.getMessage());}}).show();
    }
    private void moveStickerDialog(StickerRepo.Sticker sticker){
        List<StickerRepo.Folder> folders=repo.folders();String[] names=new String[folders.size()+1];
        names[0]="未分类";for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        new AlertDialog.Builder(this).setTitle("移动「"+sticker.name+"」到").setItems(names,(d,which)->{
            try{repo.moveSticker(sticker.id,which==0?"":folders.get(which-1).id);showManager();}
            catch(Exception e){alert("移动失败",e.getMessage());}
        }).setNegativeButton("取消",null).show();
    }
    private void copyStickerDialog(StickerRepo.Sticker sticker){
        List<StickerRepo.Folder> folders=repo.folders();String[] names=new String[folders.size()+1];
        names[0]="未分类";for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        new AlertDialog.Builder(this).setTitle("另存「"+sticker.name+"」到").setItems(names,(d,which)->{
            try{repo.copyStickers(java.util.Collections.singletonList(sticker.id),which==0?"":folders.get(which-1).id);showManager();Toast.makeText(this,"已另存副本",Toast.LENGTH_SHORT).show();}
            catch(Exception e){alert("另存失败",e.getMessage());}
        }).setNegativeButton("取消",null).show();
    }
    private void deleteStickerDialog(StickerRepo.Sticker sticker){
        new AlertDialog.Builder(this).setTitle("删除「"+sticker.name+"」？")
            .setMessage("会从贴纸库永久删除。已放入当前试贴画面的图案仍可继续编辑。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                try{repo.deleteSticker(sticker.id);showManager();}catch(Exception e){alert("删除失败",e.getMessage());}
            }).show();
    }
    private void folderNameDialog(StickerRepo.Folder folder){
        EditText input=nameInput(folder==null?"":folder.name);
        new AlertDialog.Builder(this).setTitle(folder==null?"新建文件夹":"重命名文件夹").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                try{
                    if(folder==null)managerFilter=repo.createFolder(input.getText().toString()).id;
                    else repo.renameFolder(folder.id,input.getText().toString());
                    showManager();
                }catch(Exception e){alert("文件夹未保存",e.getMessage());}
            }).show();
    }
    private void folderOptions(StickerRepo.Folder folder){
        new AlertDialog.Builder(this).setTitle(folder.name).setItems(new String[]{"导出整个文件夹 ZIP","重命名文件夹","删除文件夹"},(d,which)->{
            if(which==0){List<StickerRepo.Sticker> items=new ArrayList<>();for(StickerRepo.Sticker s:repo.list())if(folder.id.equals(s.folderId))items.add(s);exportZip(items,folder.name);}
            else if(which==1)folderNameDialog(folder);
            else new AlertDialog.Builder(this).setTitle("删除文件夹？")
                .setMessage("文件夹内的贴纸会移至“未分类”，不会删除贴纸。")
                .setNegativeButton("取消",null).setPositiveButton("删除文件夹",(confirm,w)->{
                    try{repo.deleteFolder(folder.id);managerFilter="";showManager();}
                    catch(Exception e){alert("删除文件夹失败",e.getMessage());}
                }).show();
        }).show();
    }
    private void pickLibraryImage(){
        Intent i=Build.VERSION.SDK_INT>=33?new Intent(MediaStore.ACTION_PICK_IMAGES):new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        if(i.resolveActivity(getPackageManager())==null){i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");}
        startActivityForResult(i,PICK_LIBRARY_IMAGE);
    }
    private void importChoiceDialog(){
        new AlertDialog.Builder(this).setTitle("导入贴纸")
            .setItems(new String[]{"选择单张图片","批量选择 PNG","导入 ZIP 压缩包"},(d,which)->{
                if(which==0)pickLibraryImage();else if(which==1)pickManyPng();else pickLibraryZip();
            }).setNegativeButton("取消",null).show();
    }
    private void pickManyPng(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(intent,PICK_LIBRARY_PNGS);
    }
    private void pickLibraryZip(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed"});
        startActivityForResult(intent,PICK_LIBRARY_ZIP);
    }
    private void importManyPng(List<Uri> files){
        if(files.size()>200){alert("导入数量过多","一次最多选择 200 张 PNG");return;}
        List<StickerRepo.Folder> folders=repo.folders();String[] names=new String[folders.size()+1];names[0]="未分类";
        for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        new AlertDialog.Builder(this).setTitle("将 "+files.size()+" 张 PNG 导入到")
            .setItems(names,(d,which)->beginManyPngImport(files,which==0?"":folders.get(which-1).id))
            .setNegativeButton("取消",null).show();
    }
    private void beginManyPngImport(List<Uri> uris,String folderId){
        if(libraryBusy)return;libraryBusy=true;Toast.makeText(this,"正在导入 "+uris.size()+" 张贴纸…",Toast.LENGTH_SHORT).show();
        managerWorker.submit(()->{
            List<StickerRepo.Sticker> saved=new ArrayList<>();String error=null;
            try{
                for(Uri uri:uris){
                    String raw=displayName(uri);
                    File temp=new File(getCacheDir(),"png_import_"+UUID.randomUUID()+".png");Bitmap bitmap=null;
                    try{
                        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                            if(in==null)throw new Exception("无法读取 "+raw);
                            byte[] bytes=new byte[8192];int n;long length=0;
                            while((n=in.read(bytes))!=-1){length+=n;if(length>20L*1024*1024)throw new Exception("单张 PNG 超过 20 MB："+raw);out.write(bytes,0,n);}
                        }
                        if(!StickerArchive.isPng(temp))throw new Exception("仅支持 PNG："+raw);
                        bitmap=ImageTools.load(temp,2048);
                        String name=raw.replaceFirst("(?i)\\.png$","").trim();if(name.isEmpty())name="贴纸";
                        saved.add(repo.save(bitmap,name,0,0,"",0,folderId));
                    }finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();temp.delete();}
                }
            }catch(Exception e){error=e.getMessage();for(StickerRepo.Sticker s:saved)try{repo.deleteSticker(s.id);}catch(Exception ignored){}}
            final String failure=error;runOnUiThread(()->{libraryBusy=false;
                if(failure==null){if(screen==3)showManager();Toast.makeText(this,"已导入 "+uris.size()+" 张 PNG",Toast.LENGTH_SHORT).show();}
                else alert("导入失败",failure+"；本批次已撤销，请检查图片。");
            });
        });
    }
    private void importStickerZip(Uri uri){
        if(uri==null){alert("导入失败","没有选择 ZIP 文件");return;}
        if(libraryBusy)return;libraryBusy=true;String name=displayName(uri);
        Toast.makeText(this,"正在检查并导入 ZIP…",Toast.LENGTH_SHORT).show();
        managerWorker.submit(()->{
            StickerArchive.Imported imported=null;String error=null;
            try(InputStream source=getContentResolver().openInputStream(uri)){
                if(source==null)throw new Exception("无法打开 ZIP 文件");
                imported=StickerArchive.importZip(repo,source,name,getCacheDir());
            }catch(Exception e){error=e.getMessage();}
            final StickerArchive.Imported result=imported;final String failure=error;
            runOnUiThread(()->{libraryBusy=false;
                if(failure==null){managerFilter=result.folderId;managerQuery="";managerSelected.clear();managerSelecting=false;
                    if(screen==3)showManager();Toast.makeText(this,"已导入 "+result.count+" 张到「"+result.folderName+"」",Toast.LENGTH_LONG).show();}
                else alert("ZIP 导入失败",failure);
            });
        });
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "自定义贴纸";
    }
    private void importStickerImage(Uri uri) throws Exception {
        if(uri==null)throw new Exception("没有选择图片");
        String rawName=displayName(uri);int dot=rawName.lastIndexOf('.');
        String suggested=dot>0?rawName.substring(0,dot):rawName;
        if(suggested.trim().isEmpty()||suggested.matches("[0-9]+"))suggested="自定义贴纸 "+(repo.list().size()+1);
        File temp=new File(getCacheDir(),"sticker_import_"+UUID.randomUUID());
        Bitmap image;
        try{
            try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new Exception("无法打开图片");
                byte[] bytes=new byte[8192];int n;long total=0;
                while((n=in.read(bytes))!=-1){total+=n;if(total>40L*1024*1024)throw new Exception("图片超过 40 MB，请选择较小的文件");out.write(bytes,0,n);}
            }
            image=ImageTools.load(temp,1600);
        }finally{temp.delete();}
        showImportPreview(image,suggested);
    }
    private boolean hasTransparency(Bitmap bitmap){
        if(!bitmap.hasAlpha())return false;
        int step=Math.max(1,Math.min(bitmap.getWidth(),bitmap.getHeight())/100);
        for(int y=0;y<bitmap.getHeight();y+=step)for(int x=0;x<bitmap.getWidth();x+=step)
            if((bitmap.getPixel(x,y)>>>24)<250)return true;
        return false;
    }
    private void showImportPreview(Bitmap image,String suggested){
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(5));
        ImageView preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(background(0xFFEDEEEB,12));preview.setImageBitmap(image);
        content.addView(preview,new LinearLayout.LayoutParams(-1,dp(185)));
        String hint=hasTransparency(image)?"已检测到透明区域；导入后会保留透明度。":"未检测到透明区域；底色会随整张图片一起导入。";
        margin(text(hint,12,MUTED,false),content,-2,10);
        margin(text("贴纸名称",13,INK,true),content,-2,12);
        EditText name=nameInput(suggested);margin(name,content,49,3);
        margin(text("保存到文件夹",13,INK,true),content,-2,13);
        List<StickerRepo.Folder> folders=repo.folders();List<String> names=new ArrayList<>();names.add("未分类");
        for(StickerRepo.Folder f:folders)names.add(f.name);
        Spinner picker=new Spinner(this);picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        if(managerFilter!=null&&!managerFilter.isEmpty())for(int i=0;i<folders.size();i++)if(folders.get(i).id.equals(managerFilter))picker.setSelection(i+1);
        margin(picker,content,48,3);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导入贴纸").setView(content)
            .setNegativeButton("取消",null).setPositiveButton("保存贴纸",null).create();
        dialog.setOnDismissListener(d->{preview.setImageDrawable(null);image.recycle();});
        dialog.show();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String title=name.getText().toString().trim();if(title.isEmpty())throw new Exception("请输入贴纸名称");
                int selected=picker.getSelectedItemPosition();String folderId=selected==0?"":folders.get(selected-1).id;
                repo.save(image,title,0,0,"",0,folderId);dialog.dismiss();showManager();
                Toast.makeText(this,"贴纸已导入",Toast.LENGTH_SHORT).show();
            }catch(Exception e){alert("导入失败",e.getMessage());}
        });
    }
    private void addSticker(StickerRepo.Sticker sticker){try{Bitmap b=ImageTools.load(repo.file(sticker.id),1200);board.addSticker(b,sticker,faceCalibration==null?0:faceCalibration.pixelsPerMm,faceFile.getAbsolutePath());Toast.makeText(this,faceFile.getAbsolutePath().equals(sticker.sourcePhoto)?"按同框比例加入 · 可拖动调整":"历史贴纸已加入 · 尺寸仅供参考",Toast.LENGTH_SHORT).show();}catch(Exception e){alert("贴纸读取失败",e.getMessage());}}
    private void newStickerDialog(){new AlertDialog.Builder(this).setTitle("制作新贴纸").setItems(new String[]{"从当前同框照片框选","重新现场拍摄","重新从相册导入"},(d,which)->{if(which==0)showCrop();else if(which==1)openCamera();else pick();}).show();}
    private void exportPreview(){
        if(exporting||board==null)return;
        if(!board.hasLayers()){Toast.makeText(this,"先添加一张贴纸",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this).setTitle("选择导出范围")
            .setItems(new String[]{"整张试贴图片","透明面雕与贴纸"},(dialog,which)->{
                if(which==1&&!board.hasOutline()){
                    new AlertDialog.Builder(this).setTitle("需要面雕勾边")
                        .setMessage("请先在试贴页点击「识别面雕」，确认边界后再导出透明面雕。")
                        .setPositiveButton("知道了",null).show();return;
                }
                savePreview(which==1);
            }).show();
    }
    private void savePreview(boolean sculptOnly){
        if(exporting||board==null)return;
        if(Build.VERSION.SDK_INT<=28&&exports.usesDownloads()&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingExportSculptOnly=sculptOnly;requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},EXPORT_STORAGE_PERMISSION);return;
        }
        final Bitmap image;
        try{image=sculptOnly?board.renderSculptCutout():board.renderComposite();}catch(Exception e){alert("导出失败",e.getMessage());return;}
        exporting=true;AlertDialog progress=busy("正在保存到默认目录…");
        cutoutWorker.execute(()->{
            ExportStore.Saved saved=null;Exception error=null;
            try{saved=sculptOnly?exports.saveSculpt(image):exports.save(image);}catch(Exception e){error=e;}finally{image.recycle();}
            ExportStore.Saved ready=saved;Exception failure=error;
            cutoutHandler.post(()->{
                exporting=false;if(isDestroyed())return;progress.dismiss();
                if(failure==null)Toast.makeText(this,"已保存到 "+ready.directory+"\n"+ready.name,Toast.LENGTH_LONG).show();
                else new AlertDialog.Builder(this).setTitle("导出失败").setMessage(failure.getMessage()).setNegativeButton("关闭",null).setPositiveButton("修改默认目录",(d,w)->showSettings()).show();
            });
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==EXPORT_STORAGE_PERMISSION){
            if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){if(screen==2)savePreview(pendingExportSculptOnly);}
            else Toast.makeText(this,"保存到 Download 需要存储权限，也可在设置中选择其他目录",Toast.LENGTH_LONG).show();
        }
    }
    private void alert(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message==null?"请重试":message).setPositiveButton("知道了",null).show();}
    @Override public void onBackPressed(){if(screen==4)closeCutoutEditor();else if(screen==3)managerBack();else if(screen!=0)showHome();else super.onBackPressed();}
    @Override protected void onDestroy(){releaseCutoutEditor();cutoutWorker.shutdownNow();super.onDestroy();}
}
