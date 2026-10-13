package com.modelcamera.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

final class ImageTools {
    private ImageTools() {}

    static Bitmap load(File file, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bitmap == null) throw new IOException("无法读取照片");
        int orientation = new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int degrees = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (degrees != 0) {
            Matrix matrix = new Matrix(); matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle(); bitmap = rotated;
        }
        if (Math.max(bitmap.getWidth(), bitmap.getHeight()) > maxSide) {
            float scale = maxSide / (float)Math.max(bitmap.getWidth(), bitmap.getHeight());
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth()*scale), Math.round(bitmap.getHeight()*scale), true);
            bitmap.recycle(); bitmap = resized;
        }
        return bitmap;
    }

    static Rect clamp(Rect rect, Bitmap bitmap) {
        return new Rect(Math.max(0, rect.left), Math.max(0, rect.top), Math.min(bitmap.getWidth(), rect.right), Math.min(bitmap.getHeight(), rect.bottom));
    }

    static Bitmap rotateQuarterTurn(Bitmap source, boolean clockwise) {
        Matrix matrix=new Matrix();matrix.postRotate(clockwise?90:-90);
        return Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
    }

    static Rect rotateQuarterTurn(Rect box,int sourceWidth,int sourceHeight,boolean clockwise){
        return clockwise
            ? new Rect(sourceHeight-box.bottom,box.left,sourceHeight-box.top,box.right)
            : new Rect(box.top,sourceWidth-box.right,box.bottom,sourceWidth-box.left);
    }
}
