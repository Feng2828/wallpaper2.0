package com.redmi.lwp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 极简本地存储：文件夹 URI、壁纸池、切换间隔、悬浮球开关。 */
public final class Prefs {

    public static final long DEFAULT_INTERVAL = 60_000L;
    private static final String PREF = "album_wallpaper";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_FOLDER = "folder_name";
    private static final String KEY_POOL = "pool_json";
    private static final String KEY_INTERVAL = "interval_ms";
    private static final String KEY_BALL = "ball_enabled";
    private static final String KEY_LAST_SYNC = "last_sync";

    private Prefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String getTree(Context c) { return sp(c).getString(KEY_TREE, null); }

    public static void setTree(Context c, String uri, String name) {
        sp(c).edit().putString(KEY_TREE, uri).putString(KEY_FOLDER, name).commit();
    }

    public static String getFolder(Context c) {
        String s = sp(c).getString(KEY_FOLDER, null);
        return s == null ? "未选择" : s;
    }

    public static long getInterval(Context c) {
        long v = sp(c).getLong(KEY_INTERVAL, DEFAULT_INTERVAL);
        return v <= 0 ? DEFAULT_INTERVAL : v;
    }

    public static void setInterval(Context c, long ms) {
        sp(c).edit().putLong(KEY_INTERVAL, ms).commit();
    }

    public static boolean isBallEnabled(Context c) { return sp(c).getBoolean(KEY_BALL, false); }

    public static void setBallEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_BALL, on).commit();
    }

    public static long getLastSync(Context c) { return sp(c).getLong(KEY_LAST_SYNC, 0L); }

    public static void setLastSync(Context c, long t) {
        sp(c).edit().putLong(KEY_LAST_SYNC, t).commit();
    }

    /** 壁纸池中的一张图。docId 是 SAF 文档树内的稳定标识，用作增删对比的 key。 */
    public static final class Item {
        public final String docId;
        public final String uri;
        public final String name;

        public Item(String docId, String uri, String name) {
            this.docId = docId;
            this.uri = uri;
            this.name = name == null ? "" : name;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", docId);
            o.put("uri", uri);
            o.put("name", name);
            return o;
        }

        static Item fromJson(JSONObject o) {
            return new Item(o.optString("id", ""), o.optString("uri", ""), o.optString("name", ""));
        }
    }

    public static List<Item> getPool(Context c) {
        List<Item> list = new ArrayList<>();
        String s = sp(c).getString(KEY_POOL, "");
        if (s == null || s.isEmpty()) return list;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                Item it = Item.fromJson(a.getJSONObject(i));
                if (!it.docId.isEmpty() && !it.uri.isEmpty()) list.add(it);
            }
        } catch (Exception ignored) { }
        return list;
    }

    public static void setPool(Context c, List<Item> list) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : list) a.put(it.toJson());
        } catch (Exception ignored) { }
        sp(c).edit().putString(KEY_POOL, a.toString()).commit();
    }
}
