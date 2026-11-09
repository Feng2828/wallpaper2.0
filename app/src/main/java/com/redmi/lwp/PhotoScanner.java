package com.redmi.lwp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 相册文件夹增量同步。
 * 只有 MainActivity 里点「手动同步」（或首次选定文件夹）才会调用，
 * 全工程没有任何定时器 / WorkManager / 广播去自动扫描，绝不后台偷扫。
 */
public final class PhotoScanner {

    private static final int MAX_DEPTH = 4;      // 最多向下递归 4 层子文件夹
    private static final int MAX_IMAGES = 5000;  // 防止超大目录把 SharedPreferences 撑爆

    private static final String[] PROJ = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    public static final class Result {
        public int added;
        public int removed;
        public int total;
        public String error;

        public String message() {
            if (error != null) return error;
            return "同步完成：新增 " + added + " 张，移除 " + removed + " 张，壁纸池共 " + total + " 张";
        }
    }

    private PhotoScanner() { }

    public static Result sync(Context ctx) {
        Result r = new Result();
        Context app = ctx.getApplicationContext();
        String treeStr = Prefs.getTree(app);
        if (TextUtils.isEmpty(treeStr)) {
            r.error = "请先选择相册文件夹";
            return r;
        }

        Uri tree = Uri.parse(treeStr);
        ContentResolver cr = app.getContentResolver();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            LinkedHashMap<String, Prefs.Item> found = new LinkedHashMap<>();
            walk(cr, tree, rootId, 0, found);

            List<Prefs.Item> old = Prefs.getPool(app);
            if (found.isEmpty() && !old.isEmpty()) {
                // 保护：文件夹临时不可读 / 权限丢失时，不要把壁纸池清空
                r.total = old.size();
                r.error = "文件夹里没读到图片，为防止误清空，壁纸池保持不变。请检查文件夹或重新选择一次";
                return r;
            }

            List<Prefs.Item> merged = new ArrayList<>();
            for (Prefs.Item it : found.values()) {
                boolean isNew = true;
                for (Prefs.Item o : old) {
                    if (o.docId.equals(it.docId)) { isNew = false; break; }
                }
                if (isNew) r.added++;
                merged.add(it);
            }
            for (Prefs.Item o : old) {
                if (!found.containsKey(o.docId)) r.removed++;
            }

            r.total = merged.size();
            Prefs.setPool(app, merged);
            Prefs.setLastSync(app, System.currentTimeMillis());
        } catch (SecurityException e) {
            r.error = "文件夹读取授权已失效，请重新选择相册文件夹";
        } catch (IllegalArgumentException e) {
            r.error = "文件夹地址无效，请重新选择相册文件夹";
        } catch (Exception e) {
            r.error = "同步失败：" + e.getMessage();
        }
        return r;
    }

    private static void walk(ContentResolver cr, Uri tree, String parentId, int depth,
                             Map<String, Prefs.Item> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_IMAGES) return;

        List<String> dirs = new ArrayList<>();
        Cursor c = null;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            c = cr.query(children, PROJ, null, null, null);
            if (c == null) return;
            int iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (c.moveToNext() && out.size() < MAX_IMAGES) {
                String id = iId >= 0 ? c.getString(iId) : null;
                String name = iName >= 0 ? c.getString(iName) : "";
                String mime = iMime >= 0 ? c.getString(iMime) : "";
                if (id == null || mime == null) continue;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (name != null && !name.startsWith(".")) dirs.add(id);
                } else if (mime.startsWith("image/") && isSupported(name)) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    out.put(id, new Prefs.Item(id, docUri.toString(), name));
                }
            }
        } catch (Exception ignored) {
            // 单个子目录读不到，不影响其它目录
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }

        for (String d : dirs) walk(cr, tree, d, depth + 1, out);
    }

    private static boolean isSupported(String name) {
        if (name == null) return true;
        String n = name.toLowerCase(Locale.ROOT);
        return !(n.endsWith(".raw") || n.endsWith(".dng") || n.endsWith(".svg")
                || n.endsWith(".tif") || n.endsWith(".tiff") || n.endsWith(".ico"));
    }
}
