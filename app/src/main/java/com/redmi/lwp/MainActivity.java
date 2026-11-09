package com.redmi.lwp;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;
    private static final int REQ_PERMS = 1002;
    private static final int REQ_OVERLAY = 1003;
    private static final int REQ_USAGE = 1004;
    private static final int REQ_WALLPAPER = 1005;

    private TextView tvStatus, tvFolder;
    private Button btnFolder, btnSync;
    private RadioGroup rgInterval;
    private Switch swBall;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean syncing = false;
    private boolean ignoreSwitch = false;
    private boolean ignoreRadio = false;
    private boolean pendingBallEnable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvFolder = findViewById(R.id.tv_folder);
        btnFolder = findViewById(R.id.btn_folder);
        btnSync = findViewById(R.id.btn_sync);
        rgInterval = findViewById(R.id.rg_interval);
        swBall = findViewById(R.id.sw_ball);

        btnFolder.setOnClickListener(v -> chooseFolder());
        btnSync.setOnClickListener(v -> doSync());
        findViewById(R.id.btn_set_wallpaper).setOnClickListener(v -> setLiveWallpaper());
        findViewById(R.id.btn_settings).setOnClickListener(v -> openAppDetails());

        rgInterval.setOnCheckedChangeListener((group, checkedId) -> {
            if (ignoreRadio) return;
            long ms = checkedId == R.id.rb_30 ? 30_000L
                    : checkedId == R.id.rb_120 ? 120_000L : 60_000L;
            Prefs.setInterval(this, ms);
            LiveWallpaperService.notifySettingsChanged();
            toast("切换间隔已设为 " + (ms / 1000) + " 秒");
        });

        swBall.setOnCheckedChangeListener((button, on) -> {
            if (ignoreSwitch) return;
            if (on) enableBall(true);
            else disableBall();
            refresh();
        });

        requestBasicPermissions();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        // 更新安装 / 开机后如果用户曾开启悬浮球，打开 APP 时兜底拉起服务
        if (Prefs.isBallEnabled(this) && Settings.canDrawOverlays(this)) startBallService();
    }

    // ---------------- 权限 ----------------

    private void requestBasicPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    private boolean hasImagePermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasUsagePermission() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            return aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 相册文件夹选择（SAF 整目录授权） ----------------

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("系统文件选择器不可用：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                getContentResolver().takePersistableUriPermission(tree, takeFlags);
            } catch (Exception e) {
                toast("无法获得长期授权，请重新选择一次文件夹");
                return;
            }
            Prefs.setTree(this, tree.toString(), folderName(tree));
            toast("已选择文件夹，正在一次性导入全部图片…");
            // 需求：选中文件夹后一次性导入全部图片；此后只有点「手动同步」才会再扫描
            doSync();
            refresh();
        } else if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this) && pendingBallEnable) {
                pendingBallEnable = false;
                enableBall(false);
            }
            refresh();
        } else if (requestCode == REQ_USAGE) {
            refresh();
        } else if (requestCode == REQ_WALLPAPER) {
            refresh();
        }
    }

    /** 从 SAF tree documentId（形如 primary:Pictures/风景）里取出末级文件夹名，零第三方依赖。 */
    private String folderName(Uri tree) {
        String name = null;
        try {
            name = Uri.decode(DocumentsContract.getTreeDocumentId(tree));
        } catch (Exception ignored) { }
        if (TextUtils.isEmpty(name)) return "已选择的文件夹";
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains(":")) name = name.substring(name.lastIndexOf(':') + 1);
        return TextUtils.isEmpty(name) ? "已选择的文件夹" : name;
    }

    // ---------------- 手动同步（全工程唯一的扫描入口） ----------------

    private void doSync() {
        if (syncing) return;
        if (TextUtils.isEmpty(Prefs.getTree(this))) {
            toast("请先选择相册文件夹");
            return;
        }
        syncing = true;
        btnSync.setEnabled(false);
        btnSync.setText("同步中…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            PhotoScanner.Result r = PhotoScanner.sync(app);
            ui.post(() -> {
                syncing = false;
                btnSync.setEnabled(true);
                btnSync.setText("② 手动同步");
                toast(r.message());
                LiveWallpaperService.notifySettingsChanged();
                refresh();
            });
        }, "album-sync").start();
    }

    // ---------------- 悬浮球 ----------------

    private void enableBall(boolean checkOverlay) {
        if (checkOverlay && !Settings.canDrawOverlays(this)) {
            ignoreSwitch = true;
            swBall.setChecked(false);
            ignoreSwitch = false;
            pendingBallEnable = true;
            toast("请先开启「显示在其他应用上层」权限");
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (Exception e) {
                toast("请到系统设置里手动开启悬浮窗权限");
            }
            return;
        }
        if (!hasUsagePermission()) {
            toast("建议开启「使用情况访问」，否则悬浮球无法在进入其它 App 时自动隐藏");
            try {
                startActivityForResult(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), REQ_USAGE);
            } catch (Exception ignored) { }
        }
        Prefs.setBallEnabled(this, true);
        startBallService();
    }

    private void disableBall() {
        Prefs.setBallEnabled(this, false);
        stopService(new Intent(this, FloatingBallService.class));
    }

    private void startBallService() {
        try {
            Intent s = new Intent(this, FloatingBallService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s);
            else startService(s);
        } catch (Exception e) {
            toast("启动悬浮球失败：" + e.getMessage());
        }
    }

    // ---------------- 设为壁纸 / 系统设置 ----------------

    private void setLiveWallpaper() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, LiveWallpaperService.class));
            startActivityForResult(i, REQ_WALLPAPER);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
                        REQ_WALLPAPER);
            } catch (Exception e2) {
                toast("请到系统「设置 → 壁纸」里手动选择「相册随机壁纸」");
            }
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }

    private boolean isWallpaperActive() {
        try {
            WallpaperInfo wi = WallpaperManager.getInstance(this).getWallpaperInfo();
            return wi != null && getPackageName().equals(wi.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 状态展示 ----------------

    private void refresh() {
        ignoreSwitch = true;
        swBall.setChecked(Prefs.isBallEnabled(this));
        ignoreSwitch = false;

        long ms = Prefs.getInterval(this);
        int target = ms == 30_000L ? R.id.rb_30 : ms == 120_000L ? R.id.rb_120 : R.id.rb_60;
        ignoreRadio = true;
        if (rgInterval.getCheckedRadioButtonId() != target) rgInterval.check(target);
        ignoreRadio = false;

        tvFolder.setText("当前相册文件夹：" + Prefs.getFolder(this));

        int count = Prefs.getPool(this).size();
        long t = Prefs.getLastSync(this);
        String syncTime = t == 0 ? "尚未同步" :
                new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
        String usage = hasUsagePermission() ? "已授权（可自动隐藏）" : "未授权（悬浮球会一直显示）";

        tvStatus.setText(
                "壁纸池：" + count + " 张\n"
                        + "上次同步：" + syncTime + "\n"
                        + "动态壁纸：" + (isWallpaperActive() ? "已启用 ✓" : "未设置（请点「设为壁纸」）") + "\n"
                        + "相册权限：" + (hasImagePermission() ? "已授权" : "未授权")
                        + "   悬浮窗：" + (Settings.canDrawOverlays(this) ? "已授权" : "未授权") + "\n"
                        + "切换间隔：" + (ms / 1000) + " 秒\n"
                        + "使用情况访问：" + usage);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
