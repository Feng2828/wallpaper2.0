package com.redmi.lwp;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面悬浮球 + 前台服务保活。
 * 前台服务让进程在 HyperOS 内存清理中优先级更高；用「使用情况访问权限」判断前台应用，
 * 只有桌面在前台时才显示小球，进入其它 App 自动隐藏。
 */
public class FloatingBallService extends Service {

    private static final String TAG = "FloatingBall";
    private static final String CHANNEL = "album_lwp_keepalive";
    private static final int NOTI_ID = 1001;

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<String> launchers = new HashSet<>();
    private boolean attached = false;
    private boolean shown = false;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;

    @Override
    public void onCreate() {
        super.onCreate();
        collectLaunchers();
        createChannel();
        try {
            startInForeground();
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
            stopSelf();
            return;
        }
        buildBall();
        ui.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isBallEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void collectLaunchers() {
        launchers.add("com.miui.home");           // HyperOS / MIUI 桌面
        launchers.add("com.android.launcher3");   // AOSP 桌面
        launchers.add("com.miui.home.launcher");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null) launchers.add(ri.activityInfo.packageName);
                }
            }
        } catch (Exception ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "壁纸保活服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持动态壁纸切换与桌面悬浮球运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startInForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("相册动态壁纸运行中")
                .setContentText("点此打开设置 · 常驻服务用于保活")
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    private void buildBall() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ball = new View(this);
        ball.setBackgroundResource(R.drawable.ball_bg);
        ball.setAlpha(0.85f);

        float density = getResources().getDisplayMetrics().density;
        int size = (int) (34 * density);
        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (10 * density);
        lp.y = (int) (170 * density);

        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - downX;
                        float dy = ev.getRawY() - downY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
                        if (moved) {
                            lp.x = (int) (startX - dx);
                            lp.y = (int) (startY + dy);
                            try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved && ev.getAction() == MotionEvent.ACTION_UP) {
                            LiveWallpaperService.requestNext();   // 单击 = 随机换一张
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!Prefs.isBallEnabled(FloatingBallService.this)) {
                stopSelf();
                return;
            }
            setBallVisible(isOnHomeScreen());
            ui.postDelayed(this, 800);
        }
    };

    private void setBallVisible(boolean show) {
        if (show == shown) return;
        if (show) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf();
                return;
            }
            if (!attached) {
                try {
                    wm.addView(ball, lp);
                    attached = true;
                } catch (Exception e) {
                    Log.w(TAG, "addView failed", e);
                    stopSelf();
                    return;
                }
            }
            ball.setVisibility(View.VISIBLE);
        } else if (attached) {
            ball.setVisibility(View.GONE);
        }
        shown = show;
    }

    private boolean isOnHomeScreen() {
        if (!hasUsagePermission()) return true;   // 未授权时降级：保持可见，APP 里会提示去授权
        String fg = foregroundPackage();
        return fg == null || launchers.contains(fg) || getPackageName().equals(fg);
    }

    private boolean hasUsagePermission() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 优先用 UsageEvents 取最近一次前台切换，取不到再用 queryUsageStats 兜底。 */
    private String foregroundPackage() {
        long now = System.currentTimeMillis();
        try {
            UsageStatsManager mgr = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (mgr == null) return null;
            UsageEvents events = mgr.queryEvents(now - 6000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (e.getPackageName() != null) last = e.getPackageName();
                }
            }
            if (last != null) return last;

            List<UsageStats> stats = mgr.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    now - 60_000, now);
            if (stats != null) {
                long newest = 0;
                String pkg = null;
                for (UsageStats s : stats) {
                    if (s.getLastTimeUsed() > newest) {
                        newest = s.getLastTimeUsed();
                        pkg = s.getPackageName();
                    }
                }
                return pkg;
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(poll);
        if (attached && ball != null && wm != null) {
            try { wm.removeView(ball); } catch (Exception ignored) { }
        }
        attached = false;
        shown = false;
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
