package com.redmi.lwp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机 / 覆盖更新安装后恢复悬浮球前台服务（前提是用户已打开悬浮球开关，且系统允许自启动）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        boolean ok = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "android.intent.action.HTC_QUICKBOOT_POWERON".equals(a);
        if (!ok) return;
        if (!Prefs.isBallEnabled(context)) return;

        try {
            Intent s = new Intent(context, FloatingBallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(s);
            } else {
                context.startService(s);
            }
        } catch (Exception ignored) {
            // 后台启动被系统限制时，用户下次打开 APP 会自动恢复
        }
    }
}
