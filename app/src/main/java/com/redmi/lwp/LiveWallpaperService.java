package com.redmi.lwp;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 动态壁纸引擎：随机抽图、定时切换、亮屏解锁自动换图。 */
public class LiveWallpaperService extends WallpaperService {

    private static final String TAG = "AlbumLwp";
    private static volatile LwpEngine sEngine;
    private static volatile boolean sPendingNext;
    private static volatile boolean sPendingReload;

    @Override
    public Engine onCreateEngine() {
        return new LwpEngine();
    }

    /** 悬浮球 / 亮屏解锁调用：立即随机换一张。引擎尚未启动时记为待处理。 */
    public static void requestNext() {
        LwpEngine e = sEngine;
        if (e != null) e.postNext();
        else sPendingNext = true;
    }

    /** 同步完成 / 间隔变更：重新读取设置。 */
    public static void notifySettingsChanged() {
        LwpEngine e = sEngine;
        if (e != null) e.postReload();
        else sPendingReload = true;
    }

    private final class LwpEngine extends Engine {

        private final Handler ui = new Handler(Looper.getMainLooper());
        private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        private final Random rnd = new Random();

        private volatile int renderToken = 0;
        private volatile boolean destroyed = false;
        private boolean receiverRegistered = false;
        private int width = 0;
        private int height = 0;
        private String currentId = null;
        private Bitmap current = null;
        private int unlockRetry = 0;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                showRandom();
                schedule();
            }
        };

        private final Runnable unlockCheck = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                KeyguardManager km = (KeyguardManager)
                        LiveWallpaperService.this.getSystemService(Context.KEYGUARD_SERVICE);
                PowerManager pm = (PowerManager)
                        LiveWallpaperService.this.getSystemService(Context.POWER_SERVICE);
                boolean unlocked = km == null || !km.isDeviceLocked();
                boolean awake = pm == null || pm.isInteractive();
                if (!unlocked || !awake) {
                    if (unlockRetry++ < 5) ui.postDelayed(unlockCheck, 1000);
                    return;
                }
                unlockRetry = 0;
                showRandom();
                schedule();
            }
        };

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(a) || Intent.ACTION_SCREEN_ON.equals(a)) {
                    unlockRetry = 0;
                    ui.removeCallbacks(unlockCheck);
                    ui.postDelayed(unlockCheck, 700);
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            sEngine = this;
            width = getDesiredMinimumWidth();
            height = getDesiredMinimumHeight();

            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    LiveWallpaperService.this.registerReceiver(
                            screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    LiveWallpaperService.this.registerReceiver(screenReceiver, f);
                }
                receiverRegistered = true;
            } catch (Exception e) {
                Log.w(TAG, "register screen receiver failed", e);
            }

            if (sPendingReload) { sPendingReload = false; }
            if (sPendingNext) {
                sPendingNext = false;
                showRandom();
            } else {
                showRandom();
            }
            schedule();
        }

        private void schedule() {
            ui.removeCallbacks(tick);
            if (destroyed) return;
            long d = Prefs.getInterval(LiveWallpaperService.this);
            if (d < 1000) d = Prefs.DEFAULT_INTERVAL;
            ui.postDelayed(tick, d);
        }

        private void cancel() {
            ui.removeCallbacks(tick);
            ui.removeCallbacks(unlockCheck);
        }

        private void postNext() {
            ui.post(() -> {
                if (destroyed) return;
                showRandom();
                schedule();
            });
        }

        private void postReload() {
            ui.post(() -> {
                if (destroyed) return;
                List<Prefs.Item> pool = Prefs.getPool(LiveWallpaperService.this);
                boolean stillThere = false;
                for (Prefs.Item it : pool) {
                    if (it.docId.equals(currentId)) { stillThere = true; break; }
                }
                if (currentId == null || !stillThere) showRandom();
                schedule();
            });
        }

        /** 随机抽取，池里多于一张时尽量避开上一张。 */
        private void showRandom() {
            if (destroyed) return;
            List<Prefs.Item> pool = Prefs.getPool(LiveWallpaperService.this);
            if (pool.isEmpty()) {
                drawPlaceholder("请在 APP 中选择相册文件夹并同步图片");
                return;
            }
            Prefs.Item pick = pool.get(rnd.nextInt(pool.size()));
            if (pool.size() > 1) {
                for (int i = 0; i < 8 && pick.docId.equals(currentId); i++) {
                    pick = pool.get(rnd.nextInt(pool.size()));
                }
            }
            currentId = pick.docId;
            render(pick);
        }

        private void render(final Prefs.Item item) {
            final int myToken = ++renderToken;
            final int w = width > 0 ? width : getDesiredMinimumWidth();
            final int h = height > 0 ? height : getDesiredMinimumHeight();
            worker.getQueue().clear();      // 连续点悬浮球时丢弃排队中的旧解码任务
            worker.execute(() -> {
                Bitmap b = null;
                try {
                    b = BitmapLoader.load(LiveWallpaperService.this, Uri.parse(item.uri), w, h);
                } catch (Throwable t) {
                    Log.w(TAG, "decode failed: " + item.name, t);
                }
                final Bitmap result = b;
                ui.post(() -> {
                    if (destroyed || myToken != renderToken) {
                        if (result != null && !result.isRecycled()) result.recycle();
                        return;
                    }
                    if (result == null) {
                        drawPlaceholder("这张图片无法读取（格式不支持或已被移动）");
                        return;
                    }
                    draw(result);
                });
            });
        }

        private void draw(Bitmap b) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) {
                    if (!b.isRecycled()) b.recycle();
                    return;
                }
                if (current != null && current != b && !current.isRecycled()) current.recycle();
                current = b;
                c.drawBitmap(b, 0, 0, null);
            } catch (Exception e) {
                Log.w(TAG, "draw failed", e);
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
                }
            }
        }

        private void drawPlaceholder(String msg) {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                c.drawColor(Color.rgb(26, 35, 32));
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.rgb(220, 228, 223));
                p.setTextSize(34f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(msg, c.getWidth() / 2f, c.getHeight() / 2f, p);
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
                }
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            if (destroyed) return;
            List<Prefs.Item> pool = Prefs.getPool(LiveWallpaperService.this);
            Prefs.Item target = null;
            for (Prefs.Item it : pool) {
                if (it.docId.equals(currentId)) { target = it; break; }
            }
            if (target != null) render(target);
            else if (current != null) draw(current);
            else showRandom();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) {
                schedule();
            } else {
                cancel();
            }
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            cancel();
            if (receiverRegistered) {
                try { LiveWallpaperService.this.unregisterReceiver(screenReceiver); }
                catch (Exception ignored) { }
                receiverRegistered = false;
            }
            worker.shutdownNow();
            if (current != null && !current.isRecycled()) current.recycle();
            current = null;
            if (sEngine == this) sEngine = null;
            super.onDestroy();
        }
    }
}
