package com.cb.tnotify;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

/**
 * 消息落库入口。
 *
 * 模块（跑在微信 / 支付宝进程里）通过 ContentResolver.insert 把实时消息写到本 Provider，
 * App 自己读这张表展示。两边跨进程，所以必须走 Provider。
 *
 * 只存 hook 到的实时消息，不回溯读微信的历史库。
 */
public class MsgProvider extends ContentProvider {

    public static final String AUTHORITY = "com.cb.tnotify.msg";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/msg");

    static final String TBL = "msg";
    static final String C_ID = "id";
    static final String C_PKG = "pkg";
    static final String C_FROM = "from_";
    static final String C_TEXT = "text_";
    static final String C_TIME = "time_";
    static final String C_KIND = "kind";
    static final String C_CREATED = "created";

    /** 上报状态：pending / ok / fail */
    static final String C_STATUS = "status_";
    static final String C_REQ_URL = "req_url";
    static final String C_REQ_BODY = "req_body";
    static final String C_RESP = "resp_body";

    static final String ST_PENDING = "pending";
    static final String ST_OK = "ok";
    static final String ST_FAIL = "fail";
    static final String ST_SKIP = "skip";

    /** 表内最多保留多少条记录 */
    static final int MAX_ROWS = 2000;

    static final String PKG_WX = "com.tencent.mm";
    static final String PKG_ALI = "com.eg.android.AlipayGphone";
    /** 本应用自己发的测试消息 */
    static final String PKG_SELF = "com.cb.tnotify";

    private static final int M_ALL = 1;
    private static final int M_ONE = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "msg", M_ALL);
        MATCHER.addURI(AUTHORITY, "msg/#", M_ONE);
    }

    private Db db;

    static class Db extends SQLiteOpenHelper {
        Db(Context c) {
            super(c, "msgs.db", null, 2);
        }

        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE " + TBL + " ("
                    + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + C_PKG + " TEXT,"
                    + C_FROM + " TEXT,"
                    + C_TEXT + " TEXT,"
                    + C_TIME + " INTEGER,"
                    + C_KIND + " TEXT,"
                    + C_CREATED + " INTEGER,"
                    + C_STATUS + " TEXT,"
                    + C_REQ_URL + " TEXT,"
                    + C_REQ_BODY + " TEXT,"
                    + C_RESP + " TEXT)");
            d.execSQL("CREATE INDEX idx_pkg ON " + TBL + "(" + C_PKG + ")");
            d.execSQL("CREATE INDEX idx_created ON " + TBL + "(" + C_CREATED + ")");
        }

        @Override public void onUpgrade(SQLiteDatabase d, int o, int n) {
            if (o < 2) {
                try { d.execSQL("ALTER TABLE " + TBL + " ADD COLUMN " + C_STATUS + " TEXT"); } catch (Throwable ignored) {}
                try { d.execSQL("ALTER TABLE " + TBL + " ADD COLUMN " + C_REQ_URL + " TEXT"); } catch (Throwable ignored) {}
                try { d.execSQL("ALTER TABLE " + TBL + " ADD COLUMN " + C_REQ_BODY + " TEXT"); } catch (Throwable ignored) {}
                try { d.execSQL("ALTER TABLE " + TBL + " ADD COLUMN " + C_RESP + " TEXT"); } catch (Throwable ignored) {}
            }
        }
    }

    @Override public boolean onCreate() {
        db = new Db(getContext());
        return true;
    }

    @Override public Uri insert(Uri uri, ContentValues v) {
        if (v == null) return null;
        if (!v.containsKey(C_CREATED)) {
            v.put(C_CREATED, System.currentTimeMillis());
        }

        // 去重：同一 pkg+from+text+kind 在 60 秒内只留一条
        // （支付宝主进程和 tools 进程会各推一次同样的消息）
        String pkg = v.getAsString(C_PKG);
        String from = v.getAsString(C_FROM);
        String text = v.getAsString(C_TEXT);
        String kind = v.getAsString(C_KIND);
        if (text != null) {
            Cursor c = null;
            try {
                long now = System.currentTimeMillis();
                c = db.getReadableDatabase().query(TBL, new String[]{C_ID},
                        C_PKG + "=? AND " + C_FROM + "=? AND " + C_TEXT + "=? AND " + C_KIND
                                + "=? AND " + C_CREATED + ">?",
                        new String[]{pkg == null ? "" : pkg, from == null ? "" : from,
                                text, kind == null ? "" : kind, String.valueOf(now - 60000L)},
                        null, null, null, "1");
                if (c != null && c.moveToFirst()) {
                    return ContentUris.withAppendedId(URI, c.getLong(0));
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
            }
        }

        long id = db.getWritableDatabase().insert(TBL, null, v);
        if (id <= 0) return null;

        // 只保留最近 MAX_ROWS 条，避免跑久了表越来越大
        if (id % 20 == 0) {
            try {
                db.getWritableDatabase().execSQL(
                        "DELETE FROM " + TBL + " WHERE " + C_ID + " NOT IN (SELECT "
                                + C_ID + " FROM " + TBL + " ORDER BY " + C_ID
                                + " DESC LIMIT " + MAX_ROWS + ")");
            } catch (Throwable ignored) {}
        }

        Context c = getContext();
        if (c != null) c.getContentResolver().notifyChange(URI, null);
        return ContentUris.withAppendedId(URI, id);
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String order) {
        Cursor c = db.getReadableDatabase().query(TBL, proj, sel, args, null, null,
                order == null ? (C_ID + " DESC") : order);
        Context ctx = getContext();
        if (ctx != null) c.setNotificationUri(ctx.getContentResolver(), URI);
        return c;
    }

    @Override public int delete(Uri uri, String sel, String[] args) {
        int n = db.getWritableDatabase().delete(TBL, sel, args);
        Context c = getContext();
        if (c != null) c.getContentResolver().notifyChange(URI, null);
        return n;
    }

    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) {
        if (v == null) return 0;
        int n = db.getWritableDatabase().update(TBL, v, sel, args);
        if (n > 0) {
            Context c = getContext();
            if (c != null) c.getContentResolver().notifyChange(URI, null);
        }
        return n;
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.tnotify.msg";
    }
}
