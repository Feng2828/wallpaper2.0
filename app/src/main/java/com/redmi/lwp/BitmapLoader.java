package com.redmi.lwp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;

import java.io.IOException;

/** 用系统 ImageDecoder 解码：自动处理 EXIF 旋转、支持 HEIC/WebP，长边限制在 2560px 兼顾高清与内存。 */
public final class BitmapLoader {

    private static final int MAX_SIDE = 2560;

    private BitmapLoader() { }

    public static Bitmap load(Context ctx, Uri uri, int w, int h) {
        if (w <= 0 || h <= 0) { w = 1080; h = 2340; }
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
            Bitmap decoded = ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                int sw = info.getSize().getWidth();
                int sh = info.getSize().getHeight();
                if (sw > 0 && sh > 0) {
                    float k = Math.min(1f, (float) MAX_SIDE / Math.max(sw, sh));
                    decoder.setTargetSize(Math.max(1, Math.round(sw * k)),
                            Math.max(1, Math.round(sh * k)));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            return centerCrop(decoded, w, h);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.BLACK);
        float k = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(k, k);
        m.postTranslate((w - src.getWidth() * k) / 2f, (h - src.getHeight() * k) / 2f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, m, p);
        if (!src.isRecycled()) src.recycle();
        return out;
    }
}
