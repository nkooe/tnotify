package com.cb.tnotify;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微x通知 - 微信消息监听
 *
 * 实现要点：
 * 1) 用 legacy Xposed API（de.robv.android.xposed.*）
 *    微信 8.0.79 带 Tinker 热修复 + 应用分身，实测新版 libxposed API 的模块不会被注入，
 *    纯 legacy 模块（只带 assets/xposed_init，不带 META-INF/xposed/）才能进微信。
 * 2) 微信数据库层是自研 WCDB（com.tencent.wcdb.database.SQLiteDatabase），
 *    不经过 android.database.sqlite.SQLiteDatabase，所以两套都要 hook。
 * 3) 只监听 message 表的写入（insert / insertWithOnConflict / replace），
 *    在 beforeEach 直接取 ContentValues 明文，此时尚未加密落盘。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "TNOTIFY";
    private static final String PKG_MM = "com.tencent.mm";
    private static final String PKG_ALI = "com.eg.android.AlipayGphone";

    private static volatile Context sAppCtx;
    private static volatile boolean sEnabled = true;
    private static volatile String sPkg = "";

    /** 已上报的 msgId，避免 insert / insertWithOnConflict 重复上报 */
    private static final Set<String> sSeen = new HashSet<String>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            if (lpparam == null || lpparam.packageName == null) return;
            String pkg = lpparam.packageName;
            if (!PKG_MM.equals(pkg) && !PKG_ALI.equals(pkg)) return;

            log("attached pkg=" + pkg + " process="
                    + (lpparam.processName == null ? "?" : lpparam.processName));

            sPkg = pkg;

            if (PKG_MM.equals(pkg)) {
                hookStandardSqlite();
                hookWcdb(lpparam.classLoader);
            } else {
                // 支付宝：标准 SQLite（libalipay_sqlite_bindings.so）
                hookStandardSqlite();
                probeAlipayChat(lpparam.classLoader);
                hookAlipayMBox(lpparam.classLoader);
            }

            log("ready enabled=" + isEnabled());
        } catch (Throwable t) {
            log("init error: " + t);
        }
    }

    // ---------------- 标准 SQLite ----------------

    private void hookStandardSqlite() {
        hookDbClass(SQLiteDatabase.class);
    }

    // ---------------- WCDB ----------------

    private void hookWcdb(ClassLoader cl) {
        Class<?> db = null;
        try {
            db = Class.forName("com.tencent.wcdb.database.SQLiteDatabase", false, cl);
        } catch (Throwable t) {
            log("wcdb not present: " + t);
            return;
        }

        Class<?> cv = null;
        try {
            cv = Class.forName("com.tencent.wcdb.ContentValues", false, cl);
        } catch (Throwable ignored) {
            // 微信这个版本 WCDB 复用 android.content.ContentValues
            cv = ContentValues.class;
        }
        log("wcdb db=" + db.getName() + " cv=" + cv.getName());
        hookDbClassWith(db, cv, "wcdb");
    }

    // ---------------- 支付宝消息 ----------------

    /** hook 支付宝 ChatMessageProcesser.processMessages(List, long, String) */
    private void probeAlipayChat(ClassLoader cl) {
        final Class<?> proc;
        try {
            proc = Class.forName(
                    "com.alipay.mobile.socialchatsdk.chat.processer.ChatMessageProcesser", false, cl);
        } catch (Throwable t) {
            log("alipay ChatMessageProcesser not found: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(proc, "processMessages",
                    java.util.List.class, long.class, String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!isEnabled()) return;
                                Object a0 = p.args == null ? null : p.args[0];
                                if (!(a0 instanceof java.util.List)) return;
                                for (Object o : (java.util.List<?>) a0) {
                                    dumpAliMsg(o);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("alipay processMessages hooked");
        } catch (Throwable t) {
            log("alipay processMessages fail: " + t);
        }

        // 兜底：消息对象一创建就抓
        try {
            Class<?> cm = Class.forName("com.alipay.mobile.chatsdk.api.ChatMessage", false, cl);
            int cnt = 0;
            for (java.lang.reflect.Constructor<?> ctor : cm.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!isEnabled()) return;
                                dumpAliMsg(p.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    });
                    cnt++;
                } catch (Throwable ignored) {}
            }
            log("alipay ChatMessage ctors hooked: " + cnt);
        } catch (Throwable t) {
            log("alipay ChatMessage ctor fail: " + t);
        }

        // 兜底 2：ChatMessageProcesser 的所有方法
        try {
            XposedBridge.hookAllMethods(proc, "processMessages", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!isEnabled()) return;
                        Object a0 = p.args == null ? null : p.args[0];
                        if (!(a0 instanceof java.util.List)) return;
                        for (Object o : (java.util.List<?>) a0) dumpAliMsg(o);
                    } catch (Throwable ignored) {}
                }
            });
            log("alipay processMessages(all) hooked");
        } catch (Throwable t) {
            log("alipay processMessages(all) fail: " + t);
        }
    }

    /** 支付宝消息：只输出来源、类型、时间和正文 */
    private void dumpAliMsg(Object msg) {
        if (msg == null) return;

        String from = null, to = null, time = null, bizType = null;
        String bizMemo = null, bizRemind = null, tmplData = null, msgId = null, clientId = null;

        Class<?> c = msg.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String n = f.getName();
                try {
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v == null) continue;
                    String sv;
                    if (v instanceof String) {
                        sv = (String) v;
                    } else if (v instanceof Number || v instanceof Boolean) {
                        sv = String.valueOf(v);
                    } else {
                        continue;
                    }
                    if (sv.isEmpty() || "{}".equals(sv)) continue;

                    if ("fromUId".equals(n) || "fromLoginId".equals(n)) { if (from == null) from = sv; }
                    else if ("toUId".equals(n)) to = sv;
                    else if ("createTimeMills".equals(n) && !"0".equals(sv)) time = sv;
                    else if ("createTime".equals(n) && time == null && !"0".equals(sv)) time = sv;
                    else if ("bizType".equals(n)) bizType = sv;
                    else if ("bizMemo".equals(n)) bizMemo = sv;
                    else if ("bizRemind".equals(n)) bizRemind = sv;
                    else if ("templateData".equals(n)) tmplData = sv;
                    else if ("msgId".equals(n) && !"0".equals(sv)) msgId = sv;
                    else if ("clientMsgId".equals(n)) clientId = sv;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }

        // 正文优先 bizMemo，其次解析 templateData 里的 "m"
        String text = bizMemo;
        if (text == null && tmplData != null) {
            int i = tmplData.indexOf("\"m\":\"");
            if (i >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 5; j < tmplData.length(); j++) {
                    char ch = tmplData.charAt(j);
                    if (ch == '\\' && j + 1 < tmplData.length()) {
                        char nx = tmplData.charAt(j + 1);
                        if (nx == '"' || nx == '\\') { sb.append(nx); j++; continue; }
                        if (nx == 'n') { sb.append(' '); j++; continue; }
                        sb.append(ch);
                        continue;
                    }
                    if (ch == '"') break;
                    sb.append(ch);
                }
                text = sb.toString();
            }
        }
        if (text == null || text.isEmpty()) return;   // 无正文的（回执/已读）不输出

        // 去重（msgId 优先，其次 clientMsgId + 正文）
        String dedupKey = msgId != null ? ("ali:" + msgId)
                : (clientId != null ? ("alic:" + clientId) : null);
        if (dedupKey == null && text != null) {
            dedupKey = "alit:" + from + ":" + text;
        }
        if (dedupKey != null) {
            synchronized (sSeen) {
                if (sSeen.contains(dedupKey)) return;
                if (sSeen.size() > 500) sSeen.clear();
                sSeen.add(dedupKey);
            }
        }

        log("ALIMSG from=" + nz(from) + " to=" + nz(to)
                + " type=" + nz(bizType)
                + " time=" + nz(time)
                + (bizRemind != null ? " mark=" + bizRemind : "")
                + " text=" + shorten(text, 200));

        long ts = 0L;
        try { if (time != null) ts = Long.parseLong(time.trim()); } catch (Throwable ignored) {}
        if (ts <= 0) ts = System.currentTimeMillis();
        persist(PKG_ALI, from, text, ts, "ali");
    }

    /** 把 Map / Collection / 数组 展开成可读字符串 */
    private static String deepString(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof java.util.Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(e.getKey()).append('=').append(deepString(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof java.util.Collection) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (java.util.Collection<?>) v) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(deepString(o));
            }
            return sb.append(']').toString();
        }
        if (v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(", ");
                sb.append(deepString(java.lang.reflect.Array.get(v, i)));
            }
            return sb.append(']').toString();
        }
        String s = String.valueOf(v);
        return s.startsWith(v.getClass().getName() + "@") ? "" : s;
    }

    /** 支付宝消息盒子（push 服务通知）探测 */
    private void hookAlipayMBox(ClassLoader cl) {
        final Class<?> proxy;
        try {
            proxy = Class.forName("com.alipay.android.phone.mbox.biz.proxy.MBoxMultiProxy", false, cl);
        } catch (Throwable t) {
            log("alipay MBoxMultiProxy not found: " + t);
            return;
        }
        String[] methods = {
                "postNotification", "postChannelNotification",
                "postJinniNotification", "mockSyncMessageReceived",
                "notifyHomepageRefresh"
        };
        for (final String m : methods) {
            try {
                XposedBridge.hookAllMethods(proxy, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!isEnabled()) return;
                            StringBuilder sb = new StringBuilder("MBOX ").append(m).append(": ");
                            if (p.args != null) {
                                for (int i = 0; i < p.args.length; i++) {
                                    Object a = p.args[i];
                                    if (a == null) continue;
                                    if (a instanceof String || a instanceof Number || a instanceof Boolean) {
                                        sb.append("[").append(i).append("]=")
                                          .append(shorten(String.valueOf(a), 200)).append(' ');
                                    } else {
                                        sb.append("[").append(i).append("]=")
                                          .append(a.getClass().getSimpleName()).append(' ');
                                    }
                                }
                            }
                            log(sb.toString());

                            // 从推送 JSON 里提取消息项（subtitle + 紧随其后的 title）
                            if (p.args != null) {
                                for (Object a : p.args) {
                                    if (!(a instanceof String)) continue;
                                    String sv = (String) a;
                                    if (sv.length() < 40 || !sv.contains("\"subtitle\"")) continue;

                                    int si = sv.indexOf("\"subtitle\":\"");
                                    while (si >= 0) {
                                        String sub = jsonVal(sv.substring(si), "subtitle");
                                        int ti = sv.indexOf("\"title\":\"", si);
                                        String title = null, tm = null;
                                        if (ti > si && ti - si < 900) {
                                            title = jsonVal(sv.substring(ti), "title");
                                            int mi = sv.indexOf("\"time\":\"", si);
                                            if (mi > si && mi - si < 900) {
                                                tm = jsonVal(sv.substring(mi), "time");
                                            }
                                        }
                                        if (sub != null && !sub.isEmpty()) {
                                            // 过滤 header 汇总（"2条新支付消息" 之类），只要真正的消息项
                                            boolean header = sub.contains("条新支付消息")
                                                    || sub.contains("条未读") && sub.contains("消息")
                                                    || "最近消息".equals(title);
                                            if (!header) {
                                                log("ALIMSG box from=" + nz(title) + " time=" + nz(tm)
                                                        + " text=" + shorten(sub, 200));
                                                long ts = 0L;
                                                try { if (tm != null) ts = Long.parseLong(tm.trim()); }
                                                catch (Throwable ignored) {}
                                                if (ts <= 0) ts = System.currentTimeMillis();
                                                persist(PKG_ALI, title, sub, ts, "box");
                                            }
                                        }
                                        si = sv.indexOf("\"subtitle\":\"", si + 12);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                log("alipay mbox." + m + " hooked");
            } catch (Throwable t) {
                log("alipay mbox." + m + " fail: " + t);
            }
        }
    }

    // ---------------- 通用 hook 逻辑 ----------------

    private void hookDbClass(Class<?> db) {
        hookDbClassWith(db, ContentValues.class, "sqlite");
    }

    private void hookDbClassWith(final Class<?> db, Class<?> cv, String tag) {
        hookInsertLike(db, cv, "insert", tag, 3);
        hookInsertLike(db, cv, "insertWithOnConflict", tag, 4);
        hookInsertLike(db, cv, "insertOrThrow", tag, 3);
        hookInsertLike(db, cv, "replace", tag, 3);
        hookInsertLike(db, cv, "replaceOrThrow", tag, 3);
        hookCompiledStatements(db, tag);
    }

    /** 预编译语句路径：compileStatement 记 SQL，executeInsert/execute 时读绑定参数 */
    private static final java.util.Map<Object, String> sStmtSql =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, String>());

    private void hookCompiledStatements(final Class<?> db, String tag) {
        try {
            XposedHelpers.findAndHookMethod(db, "compileStatement", String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object r = p.getResult();
                                if (r != null && p.args != null && p.args[0] instanceof String) {
                                    sStmtSql.put(r, (String) p.args[0]);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
            log("hooked " + tag + ".compileStatement");
        } catch (Throwable t) {
            log(tag + ".compileStatement fail " + t);
        }

        Class<?> stmt = null;
        try {
            stmt = Class.forName("android.database.sqlite.SQLiteStatement");
        } catch (Throwable t) {
            return;
        }
        for (final String m : new String[]{"executeInsert", "execute", "executeUpdateDelete"}) {
            try {
                XposedHelpers.findAndHookMethod(stmt, m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!isEnabled()) return;
                            String sql = sStmtSql.get(p.thisObject);
                            if (sql == null) return;
                            reportCompiled(sql, p.thisObject, m);
                        } catch (Throwable ignored) {}
                    }
                });
                log("hooked stmt." + m);
            } catch (Throwable ignored) {}
        }
    }

    /** 从 SQLiteStatement 里读绑定的参数 */
    private void reportCompiled(String sql, Object stmt, String via) {
        String low = sql.toLowerCase(Locale.US);
        boolean isWx = PKG_MM.equals(sPkg) && low.contains("message");
        boolean isAli = PKG_ALI.equals(sPkg) && low.contains("chat_message");
        if (!isWx && !isAli) return;

        StringBuilder sb = new StringBuilder("STMT[").append(via).append("] ").append(shorten(sql, 160));
        try {
            Object arr = XposedHelpers.getObjectField(stmt, "mBindArgs");
            if (arr instanceof Object[]) {
                Object[] args = (Object[]) arr;
                for (int i = 0; i < args.length && i < 24; i++) {
                    if (args[i] == null) continue;
                    sb.append(" | [").append(i).append("]=").append(shorten(String.valueOf(args[i]), 70));
                }
            }
        } catch (Throwable t) {
            sb.append(" <args fail: ").append(t).append(">");
        }
        log(sb.toString());
    }

    /**
     * hook 形如 (String table, String nullColumnHack, ContentValues values[, int flag]) 的方法
     */
    private void hookInsertLike(Class<?> db, Class<?> cv, String method, final String tag, int argc) {
        try {
            Object cb = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isEnabled()) return;
                        Object[] a = param.args;
                        if (a == null || a.length < 3) return;
                        if (!(a[0] instanceof String) || a[2] == null) return;
                        report((String) a[0], a[2], tag + "." + method);
                    } catch (Throwable ignored) {}
                }
            };
            if (argc == 4) {
                XposedHelpers.findAndHookMethod(db, method,
                        String.class, String.class, cv, int.class, (XC_MethodHook) cb);
            } else {
                XposedHelpers.findAndHookMethod(db, method,
                        String.class, String.class, cv, (XC_MethodHook) cb);
            }
            log("hooked " + tag + "." + method);
        } catch (Throwable t) {
            // 某些重载在特定版本不存在，正常跳过
        }
    }

    // ---------------- 消息提取 ----------------

    private void report(String table, Object cvObj, String via) {
        String tl = table == null ? "" : table.toLowerCase(Locale.US);

        boolean isWx = PKG_MM.equals(sPkg) && tl.contains("message");
        boolean isAli = PKG_ALI.equals(sPkg)
                && (tl.equals("chat_message") || tl.startsWith("chat_message"));

        if (!isWx && !isAli) return;

        // 支付宝：字段名未知，先把整条 ContentValues 打出来
        if (isAli) {
            StringBuilder sb = new StringBuilder("ALI[").append(via).append("] table=").append(table);
            try {
                Object setObj = cvObj.getClass().getMethod("valueSet").invoke(cvObj);
                if (setObj instanceof Set) {
                    for (Object o : (Set<?>) setObj) {
                        if (!(o instanceof Map.Entry)) continue;
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                        if (e.getKey() == null) continue;
                        sb.append(" | ").append(e.getKey()).append('=')
                          .append(shorten(String.valueOf(e.getValue()), 90));
                    }
                }
            } catch (Throwable t) {
                sb.append(" <read fail: ").append(t).append(">");
            }
            log(sb.toString());
            return;
        }

        String talker = null, content = null, msgId = null, type = null, createTime = null, isSend = null;

        try {
            Object setObj = cvObj.getClass().getMethod("valueSet").invoke(cvObj);
            if (setObj instanceof Set) {
                for (Object o : (Set<?>) setObj) {
                    if (!(o instanceof Map.Entry)) continue;
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                    if (e.getKey() == null) continue;
                    String k = String.valueOf(e.getKey());
                    String kl = k.toLowerCase(Locale.US);
                    String v = e.getValue() == null ? null : String.valueOf(e.getValue());

                    if (kl.equals("talker")) talker = v;
                    else if (kl.equals("content")) content = v;
                    else if (kl.equals("msgid")) msgId = v;
                    else if (kl.equals("type")) type = v;
                    else if (kl.equals("createtime")) createTime = v;
                    else if (kl.equals("issend")) isSend = v;
                }
            }
        } catch (Throwable t) {
            log("read cv fail: " + t);
            return;
        }

        if (msgId == null && content == null) return;

        // 去重
        if (msgId != null) {
            synchronized (sSeen) {
                if (sSeen.contains(msgId)) return;
                if (sSeen.size() > 500) sSeen.clear();
                sSeen.add(msgId);
            }
        }

        String dir = "1".equals(isSend) ? "我->" : "<-";
        log("MSG " + dir + " talker=" + nz(talker)
                + " type=" + nz(type)
                + " time=" + nz(createTime)
                + " content=" + show(content)
                + "  (" + via + ")");

        long ts = 0L;
        try { if (createTime != null) ts = Long.parseLong(createTime.trim()); } catch (Throwable ignored) {}
        if (ts <= 0) ts = System.currentTimeMillis();
        persist(PKG_MM, talker, content, ts, "wx");
    }

    // ---------------- 工具 ----------------

    // ---------------- 落库 ----------------

    /** Context 还没就绪时先把消息暂存在内存，等 Application 起来再补写 */
    private static final java.util.List<String[]> sPending =
            new java.util.ArrayList<String[]>();
    private static volatile boolean sFlusherStarted = false;

    /** 把实时消息写入 App 侧的 MsgProvider，供界面展示 */
    static void persist(String pkg, String from, String text, long time, String kind) {
        if (text == null || text.isEmpty()) return;
        Context c = ctx();
        if (c != null) {
            doInsert(c, pkg, from, text, time, kind);
            return;
        }
        synchronized (sPending) {
            if (sPending.size() > 300) sPending.remove(0);
            sPending.add(new String[]{pkg, from, text, String.valueOf(time), kind});
            log("persist queued " + kind + " (no ctx yet)");
        }
        startFlusher();
    }

    /** 轮询等待 Context 就绪，把队列里的消息补写进去 */
    private static void startFlusher() {
        if (sFlusherStarted) return;
        sFlusherStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 180; i++) {          // 最多等 90 秒
                    try { Thread.sleep(500); } catch (Throwable ignored) {}
                    Context c = ctx();
                    if (c == null) continue;
                    java.util.List<String[]> batch;
                    synchronized (sPending) {
                        if (sPending.isEmpty()) return;
                        batch = new java.util.ArrayList<String[]>(sPending);
                        sPending.clear();
                    }
                    for (String[] r : batch) {
                        long t = 0L;
                        try { t = Long.parseLong(r[3]); } catch (Throwable ignored) {}
                        doInsert(c, r[0], r[1], r[2], t, r[4]);
                    }
                    log("persist flushed " + batch.size());
                    return;
                }
            }
        }).start();
    }

    private static void doInsert(Context c, String pkg, String from,
                                 String text, long time, String kind) {
        try {
            ContentValues v = new ContentValues();
            v.put("pkg", pkg);
            v.put("from_", from == null ? "" : from);
            v.put("text_", text);
            v.put("time_", time);
            v.put("kind", kind == null ? "" : kind);
            v.put("created", System.currentTimeMillis());
            android.net.Uri u = c.getContentResolver().insert(
                    android.net.Uri.parse("content://com.cb.tnotify.msg/msg"), v);
            log("persist " + (u != null ? "ok" : "null") + " " + kind
                    + " len=" + text.length());
        } catch (Throwable t) {
            log("persist fail: " + t);
        }
    }

    /** 拿当前进程的 Context（微信 / 支付宝） */
    static Context ctx() {
        Context c = sAppCtx;
        if (c != null) return c;
        try {
            Object app = AndroidAppHelper.currentApplication();
            if (app instanceof Context) {
                sAppCtx = (Context) app;
                return sAppCtx;
            }
        } catch (Throwable ignored) {}
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                sAppCtx = (Context) app;
                return sAppCtx;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String nz(String s) { return s == null ? "-" : s; }

    /** 从 JSON 文本里取第一个 "key":"value" 的 value */
    private static String jsonVal(String json, String key) {
        if (json == null || key == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = i + pat.length(); j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\' && j + 1 < json.length()) {
                char nx = json.charAt(j + 1);
                if (nx == '"' || nx == '\\') { sb.append(nx); j++; continue; }
                if (nx == 'n') { sb.append(' '); j++; continue; }
                sb.append(ch);
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "null";
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String show(String s) {
        if (s == null) return "null";
        s = s.replace("\n", "\\n").replace("\r", "");
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    static boolean isEnabled() {
        Context c = sAppCtx;
        if (c == null) {
            try {
                Object app = AndroidAppHelper.currentApplication();
                if (app instanceof Context) {
                    sAppCtx = (Context) app;
                    c = (Context) app;
                }
            } catch (Throwable ignored) {}
        }
        if (c == null) return sEnabled;
        try {
            SharedPreferences sp = c.getSharedPreferences("wxnotify", Context.MODE_PRIVATE);
            return sp.getBoolean("enabled", true);
        } catch (Throwable t) {
            return sEnabled;
        }
    }

    private static void log(String msg) {
        try { Log.println(4, TAG, msg); } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + ": " + msg); } catch (Throwable ignored) {}
    }
}
