package com.cb.tnotify;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 码支付 V1 上报引擎。
 *
 * 协议：
 *   POST {site}/api/pay/{通道ID}/notify
 *   Content-Type: application/x-www-form-urlencoded
 *   from      来源包名（com.tencent.mm / com.eg.android.AlipayGphone）
 *   content   内嵌 JSON 字符串
 *   timestamp 13 位毫秒
 *   sign      base64( HMAC_SHA256( key=密钥, msg=timestamp + "\n" + 密钥 ) )
 *   成功判定   HTTP 2xx 且响应正文 trim 后等于 "200"
 *
 * PID 是路由表，格式 wxpay1,alipay2：
 *   wxpayN  → 微信，通道 N
 *   alipayN → 支付宝，通道 N
 *   qqpayN  → QQ，通道 N
 */
public final class Uploader {

    private static final Pattern ROUTE_RE =
            Pattern.compile("^(wxpay|alipay|qqpay)([0-9]{1,20})$");

    private static final SimpleDateFormat SEC_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    /** 带金额但不是真到账的消息，上报会误匹配订单 */
    static final String DEFAULT_EXCLUDE =
            "退款,优惠,红包,提现,扣款,失败,已退还,验证码,账单,还款";

    /** 到账前提醒：钱还没进账户，不能当成收款上报 */
    static final String[] PENDING_HINTS = {
            "想向你转账", "向你转账", "等待你确认",
            "请及时收款", "待收款"
    };

    /** 与设置页一致的默认关键词；设置页没保存过时用它 */
    static final String DEFAULT_KEYWORDS =
            "微信收款助手,微信支付收款,二维码赞赏到账,店员消息,收款单到账,"
          + "个人收款码到账,收款通知,微信收款商业版,对外收款,收钱码,店员通,"
          + "已转入余额,你已成功收款,通过扫码向你付款,成功收款";

    private static volatile boolean sRunning = false;

    private Uploader() { }

    // ---------------- 路由 ----------------

    static final class Route {
        final String payType;
        final String channelId;

        Route(String t, String c) { payType = t; channelId = c; }

        String from() {
            if ("alipay".equals(payType)) return "com.eg.android.AlipayGphone";
            if ("qqpay".equals(payType)) return "com.tencent.mobileqq";
            return "com.tencent.mm";
        }
    }

    /** 解析 PID；格式不对或同一支付方式重复返回 null */
    static List<Route> parseRoutes(String pid) {
        if (pid == null) return null;
        String raw = pid.trim();
        if (raw.isEmpty()) return null;
        String[] parts = raw.split("[,，]");
        List<Route> out = new ArrayList<Route>();
        Set<String> seen = new HashSet<String>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            Matcher m = ROUTE_RE.matcher(t);
            if (!m.find()) return null;
            String type = m.group(1);
            if (!seen.add(type)) return null;
            out.add(new Route(type, m.group(2)));
        }
        return out.isEmpty() ? null : out;
    }

    /** 配置有误时返回给用户看的提示；正常返回 null */
    static String routeError(String pid) {
        if (pid == null) return null;
        String raw = pid.trim();
        if (raw.isEmpty()) return null;
        String[] parts = raw.split("[,，]");
        Set<String> seen = new HashSet<String>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("(?i)^(wxpay|alipay|qqpay)$")) {
                return "PID 缺少通道ID：" + t.toLowerCase(Locale.ROOT)
                        + " 应写成 " + t.toLowerCase(Locale.ROOT) + "1";
            }
            Matcher m = ROUTE_RE.matcher(t);
            if (!m.find()) return "PID 格式应为 wxpay1,alipay2（逗号分隔）";
            if (!seen.add(m.group(1))) {
                return "同一支付方式只能配一个通道：" + t + " 重复";
            }
        }
        return null;
    }

    /** 包名 → 支付方式 */
    static String payTypeOf(String pkg) {
        if (pkg == null) return null;
        if (MsgProvider.PKG_WX.equals(pkg)) return "wxpay";
        if (MsgProvider.PKG_SELF.equals(pkg)) return "self";
        // 支付宝包名是 com.eg.android.AlipayGphone，必须忽略大小写
        String low = pkg.toLowerCase(Locale.ROOT);
        if (low.contains("alipay")) return "alipay";
        if (low.contains("mobileqq")) return "qqpay";
        return null;
    }

    /** 包名 → 路由；找不到不回落到第一条，避免串联 */
    static Route routeFor(List<Route> routes, String pkg) {
        if (routes == null) return null;
        String type = payTypeOf(pkg);
        if ("self".equals(type)) return routes.get(0);   // 测试消息用第一条
        for (Route r : routes) {
            if (r.payType.equals(type)) return r;
        }
        return null;
    }

    // ---------------- 对外入口 ----------------

    public static void run(final Context ctx) {
        if (sRunning) return;
        sRunning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    doUpload(ctx);
                } catch (Throwable ignored) {
                } finally {
                    sRunning = false;
                }
            }
        }).start();
    }

    /** 手动重试一条 */
    public static void retry(final Context ctx, final long id) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    resetToPending(ctx, id);
                    doUpload(ctx);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private static void resetToPending(Context ctx, long id) {
        try {
            ContentValues v = new ContentValues();
            v.put(MsgProvider.C_STATUS, MsgProvider.ST_PENDING);
            ctx.getContentResolver().update(MsgProvider.URI, v,
                    MsgProvider.C_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {}
    }

    // ---------------- 主流程 ----------------

    private static void doUpload(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("wxnotify", Context.MODE_PRIVATE);
        String site = trim(sp.getString("domain", "")).replaceAll("/+$", "");
        String pid = trim(sp.getString("pid", ""));
        String secret = trim(sp.getString("key", ""));

        if (site.isEmpty() || secret.isEmpty()) return;
        List<Route> routes = parseRoutes(pid);
        if (routes == null) return;

        boolean useFilter = sp.getBoolean("kwFilter", true);
        String kwRaw = sp.getString("keywords", "");
        if (kwRaw == null || kwRaw.trim().isEmpty()) kwRaw = DEFAULT_KEYWORDS;
        String[] kws = kwRaw.split("[,，]");

        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    MsgProvider.URI,
                    new String[]{MsgProvider.C_ID, MsgProvider.C_PKG,
                            MsgProvider.C_FROM, MsgProvider.C_TEXT,
                            MsgProvider.C_TIME},
                    MsgProvider.C_STATUS + " IS NULL OR " + MsgProvider.C_STATUS + "=?",
                    new String[]{MsgProvider.ST_PENDING},
                    MsgProvider.C_ID + " ASC");
            if (c == null) return;

            while (c.moveToNext()) {
                long id = c.getLong(0);
                String pkg = c.getString(1);
                String from = c.getString(2);
                String text = c.getString(3);
                long time = c.getLong(4);
                if (text == null) text = "";

                // 微信收款是 XML appmsg，先解析成可读文本
                String[] src = new String[1];
                text = cleanText(text, src);
                if (src[0] != null && !src[0].isEmpty()) from = src[0];

                // 到账前提醒与排除词：不上报，否则会把未到账的消息拿去匹配订单
                String[] excludes = sp.getString("exclude", DEFAULT_EXCLUDE).split("[,，]");
                if (shouldSkipUpload(text, excludes)) {
                    markStatus(ctx, id, MsgProvider.ST_SKIP, null, null, null);
                    continue;
                }

                Route route = routeFor(routes, pkg);
                if (route == null) {
                    markStatus(ctx, id, MsgProvider.ST_FAIL, null, null,
                            "PID 里没有对应通道，请补上 " + payTypeOf(pkg) + "N");
                    continue;
                }

                sendOne(ctx, id, pkg, from, text, time, site, secret, route);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private static void sendOne(Context ctx, long id, String pkg, String title,
                                String msg, long time, String site,
                                String secret, Route route) {
        String url = site + "/api/pay/" + route.channelId + "/notify";
        String ts = String.valueOf(System.currentTimeMillis());

        String useMsg = "alipay".equals(payTypeOf(pkg)) ? normalizeAlipay(msg) : msg;
        String inner = "{\"title\":\"" + esc(pick(title, pkg))
                + "\",\"msg\":\"" + esc(useMsg)
                + "\",\"package_name\":\"" + esc(route.from())
                + "\",\"receive_time\":\"" + microTime(time)
                + "\",\"device_name\":\"" + esc(deviceName()) + "\"}";

        String sign = hmacSign(ts, secret);
        String form = "from=" + enc(route.from())
                + "&content=" + enc(inner)
                + "&timestamp=" + enc(ts)
                + "&sign=" + enc(sign);

        String shownBody = "from: " + route.from() + "\n"
                + "content:\n" + inner + "\n"
                + "timestamp: " + ts + "\n"
                + "sign: " + sign;

        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tnotify:push");
                wl.setReferenceCounted(false);
                wl.acquire(30000L);
            }
        } catch (Throwable ignored) {}

        try {
            String[] r = httpForm(url, form);
            boolean httpOk = r[0] != null && r[0].startsWith("2");
            String body = r[1] == null ? "" : r[1].trim();
            boolean ok = httpOk && "200".equals(body);

            String shownResp = (r[0] == null ? "无响应" : r[0]) + ": "
                    + (body.isEmpty() ? "(空响应)" : body);

            String status;
            if (ok) {
                status = MsgProvider.ST_OK;
            } else if (isNetworkFailure(body) || r[0] == null || "0".equals(r[0])) {
                status = MsgProvider.ST_PENDING;   // 网络类失败，保持待发
            } else {
                status = MsgProvider.ST_FAIL;
            }
            markStatus(ctx, id, status, url, shownBody, shownResp);
        } catch (Throwable t) {
            markStatus(ctx, id, MsgProvider.ST_PENDING, url, shownBody,
                    String.valueOf(t));
        } finally {
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Throwable ignored) {}
        }
    }

    // ---------------- 工具 ----------------

    /** 是否应该不上报：到账前提醒 / 命中排除词 */
    static boolean shouldSkipUpload(String text, String[] excludes) {
        if (text == null || text.isEmpty()) return true;
        for (String h : PENDING_HINTS) {
            if (text.contains(h)) return true;
        }
        if (excludes != null) {
            for (String k : excludes) {
                String t = k == null ? "" : k.trim();
                if (!t.isEmpty() && text.contains(t)) return true;
            }
        }
        return false;
    }

    static boolean hitKeyword(String text, String[] kws) {
        if (TextUtils.isEmpty(text)) return false;
        for (String k : kws) {
            String t = k == null ? "" : k.trim();
            if (t.isEmpty()) continue;
            if (text.contains(t)) return true;
        }
        // 兜底：收款或转账 + 金额，都算收款类
        // 支付宝真实文案：￥0.10收款到账通知 / 收到一笔转账￥0.10
        if (text.contains("收款") || text.contains("转账")) {
            if (text.contains("￥") || text.contains("¥") || text.contains("元")) return true;
        }
        // 汇总类：「今日收款 1 笔」「今日收款0笔」
        if (text.matches("(?s).*今日收款\\s*\\d+\\s*笔.*")) return true;
        return false;
    }

    /** 微信收款消息是 XML appmsg，解析成可读文本；outTitle[0] 回传来源名 */
    static String cleanText(String raw, String[] outTitle) {
        if (raw == null) return "";
        String t = raw.trim();
        if (!t.startsWith("<msg") && !t.startsWith("<?xml")) return raw;

        String title = xmlField(t, "title");
        String des = xmlField(t, "des");
        String name = xmlField(t, "name");

        if (outTitle != null && outTitle.length > 0) {
            outTitle[0] = (name == null || name.isEmpty()) ? "微信收款助手" : name;
        }

        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) sb.append(title);
        if (des != null && !des.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(des.replace("\\n", "\n"));
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    /** 取 <key><![CDATA[...]]></key> 或 <key>...</key> 的首个值 */
    static String xmlField(String xml, String key) {
        String cdata = "<" + key + "><![CDATA[";
        int i = xml.indexOf(cdata);
        if (i >= 0) {
            i += cdata.length();
            int j = xml.indexOf("]]></" + key + ">", i);
            return j > i ? xml.substring(i, j) : "";
        }
        String plain = "<" + key + ">";
        i = xml.indexOf(plain);
        if (i < 0) return "";
        i += plain.length();
        int j = xml.indexOf("</" + key + ">", i);
        return j > i ? xml.substring(i, j).trim() : "";
    }

    /** 从文本里抽金额，抽不到返回 null */
    static String amountOf(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:¥|￥|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
                .matcher(text);
        if (m.find()) return m.group(1).replace(",", "");
        m = java.util.regex.Pattern
                .compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:元|圆|块钱)")
                .matcher(text);
        if (m.find()) return m.group(1).replace(",", "");
        return null;
    }

    /**
     * 支付宝消息盒子的文案是营销卡片（「￥0.10收款到账通知，领积分兑好礼」），
     * 平台认不出来。抽出金额重新组成标准收款文案。
     */
    static String normalizeAlipay(String text) {
        String amt = amountOf(text);
        if (amt == null) return text;
        return "收款到账" + amt + "元";
    }

    private static String pick(String title, String pkg) {
        // 平台按 title 识别来源类型：微信统一「微信收款助手」，支付宝统一「支付宝」
        String type = payTypeOf(pkg);
        if ("wxpay".equals(type)) {
            return TextUtils.isEmpty(title) ? "微信收款助手" : title;
        }
        // 平台只有 title 恰好等于「收款通知」时才解析 msg
        if ("alipay".equals(type)) return "收款通知";
        if ("qqpay".equals(type)) return "QQ";
        return TextUtils.isEmpty(title) ? "Android" : title;
    }

    private static void markStatus(Context ctx, long id, String status,
                                   String reqUrl, String reqBody, String resp) {
        try {
            ContentValues v = new ContentValues();
            v.put(MsgProvider.C_STATUS, status);
            if (reqUrl != null) v.put(MsgProvider.C_REQ_URL, reqUrl);
            if (reqBody != null) v.put(MsgProvider.C_REQ_BODY, reqBody);
            if (resp != null) v.put(MsgProvider.C_RESP, resp);
            ctx.getContentResolver().update(MsgProvider.URI, v,
                    MsgProvider.C_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {}
    }

    private static String hmacSign(String ts, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
            byte[] raw = mac.doFinal((ts + "\n" + secret).getBytes("UTF-8"));
            return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** yyyy-MM-dd HH:mm:ss.SSSSSS（微秒位用毫秒补齐） */
    private static String microTime(long time) {
        return SEC_FMT.format(new Date(time))
                + String.format(Locale.CHINA, ".%06d", (time % 1000L) * 1000L);
    }

    private static String deviceName() {
        String m = Build.MODEL == null ? "" : Build.MODEL.trim();
        return m.isEmpty() ? "Android" : m;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isNetworkFailure(String msg) {
        if (msg == null) return true;
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("socket") || m.contains("timeout") || m.contains("timed out")
                || m.contains("unable to resolve") || m.contains("connection")
                || m.contains("network") || m.contains("broken pipe")
                || m.contains("econnreset") || m.contains("econnrefused")
                || m.contains("ssl") || m.contains("hostname");
    }

    /** 表单上送；必须带 UA，否则会被 Cloudflare 拦成 403 */
    private static String[] httpForm(String urlStr, String form) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "okhttp/4.12.0");

            byte[] bytes = form.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            return new String[]{String.valueOf(code), text};
        } catch (Throwable t) {
            return new String[]{"0", String.valueOf(t)};
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { is.close(); } catch (Throwable ignored) {}
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
