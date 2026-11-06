package com.cb.tnotify;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 微x通知
 * 顶部标题栏（图标 + 名称居中），中间内容区，底部三个页签。
 * 日志页顶部再放「全部 / 微信 / 支付宝」三段筛选，只展示模块实时 hook 到的消息。
 */
public class MainActivity extends Activity {

    private static final int C_GREEN   = 0xFF07C160;
    private static final int C_BG      = 0xFFEDEDED;
    private static final int C_CARD    = 0xFFFFFFFF;
    private static final int C_TEXT    = 0xFF191919;
    private static final int C_SUB     = 0xFF888888;
    private static final int C_DIVIDER = 0xFFE5E5E5;
    private static final int C_TAB_OFF = 0xFF7A7A7A;

    private SharedPreferences sp;
    private LinearLayout contentHost;
    private TextView[] tabs;
    private int curTab = 0;

    // 日志页状态
    private TextView[] segTabs;
    private LinearLayout listHost;
    private TextView countTv;
    private int curSeg = 0;

    private static final String[] TAB_NAMES = {"首页", "日志", "设置"};
    private static final String[] SEG_NAMES = {"全部", "微信", "支付宝"};

    private static final int SEG_ALL = 0;
    private static final int SEG_WX  = 1;
    private static final int SEG_ALI = 2;

    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FMT_T = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** 监听消息表变化，实时刷新列表 */
    private ContentObserver msgObserver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("wxnotify", MODE_PRIVATE);
        setContentView(buildUi());
        selectTab(0);

        msgObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                if (curTab == 1) renderLog();
                Uploader.run(MainActivity.this);
            }
        };
        try {
            getContentResolver().registerContentObserver(MsgProvider.URI, true, msgObserver);
        } catch (Throwable ignored) {}

        // 上次没发出去的消息，启动后补发一次
        Uploader.run(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (msgObserver != null) {
            try { getContentResolver().unregisterContentObserver(msgObserver); } catch (Throwable ignored) {}
            msgObserver = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (curTab == 1) renderLog();
    }

    // ================= 骨架 =================

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setBackgroundColor(C_GREEN);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));

        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_launcher_wx);
        bar.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView title = new TextView(this);
        title.setText("微x通知");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(10);
        bar.addView(title, tlp);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout contentWrap = new FrameLayout(this);
        contentWrap.setBackgroundColor(C_BG);
        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        contentWrap.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(this);
        line.setBackgroundColor(C_DIVIDER);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(C_CARD);
        tabBar.setPadding(0, dp(6), 0, dp(8));

        tabs = new TextView[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(TAB_NAMES[i]);
            tv.setTextSize(13f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(12), 0, dp(12));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
            tabs[i] = tv;
            tabBar.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void selectTab(int idx) {
        curTab = idx;
        for (int i = 0; i < tabs.length; i++) {
            boolean on = (i == idx);
            tabs[i].setTextColor(on ? C_GREEN : C_TAB_OFF);
            tabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        contentHost.removeAllViews();
        if (idx == 1) {
            contentHost.addView(buildLogPage(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (idx == 2) {
            contentHost.addView(buildSettingsPage(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            contentHost.addView(placeholder("微x通知\n\n微信 / 支付宝 消息监听\n到「日志」看实时捕获的消息"),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ================= 日志页 =================

    private View buildLogPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // 顶部三段：全部 / 微信 / 支付宝
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundColor(C_CARD);
        seg.setPadding(dp(10), dp(8), dp(10), dp(8));

        segTabs = new TextView[SEG_NAMES.length];
        for (int i = 0; i < SEG_NAMES.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(SEG_NAMES[i]);
            tv.setTextSize(14f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(9), 0, dp(9));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    curSeg = idx;
                    renderLog();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            segTabs[i] = tv;
            seg.addView(tv, lp);
        }
        page.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View div = new View(this);
        div.setBackgroundColor(C_DIVIDER);
        page.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 条数
        countTv = new TextView(this);
        countTv.setTextSize(12f);
        countTv.setTextColor(C_SUB);
        countTv.setPadding(dp(16), dp(10), dp(16), dp(6));
        page.addView(countTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        listHost = new LinearLayout(this);
        listHost.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        renderLog();
        return page;
    }

    private void renderLog() {
        // 顶部三段样式
        for (int i = 0; i < segTabs.length; i++) {
            boolean on = (i == curSeg);
            segTabs[i].setTextColor(on ? Color.WHITE : C_TAB_OFF);
            segTabs[i].setBackgroundColor(on ? C_GREEN : 0x00000000);
            segTabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (listHost == null) return;
        listHost.removeAllViews();

        String[] rows = new String[0];
        int total = 0;

        String sel = null;
        String[] args = null;
        if (curSeg == SEG_WX) {
            sel = "pkg=?";
            args = new String[]{MsgProvider.PKG_WX};
        } else if (curSeg == SEG_ALI) {
            sel = "pkg=?";
            args = new String[]{MsgProvider.PKG_ALI};
        }

        int[] stat = new int[1];
        listHost.addView(buildList(sel, args, stat), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        total = stat[0];
        countTv.setText("共 " + total + " 条");
    }

    private View buildList(String sel, String[] args, int[] stat) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), 0, dp(10), dp(10));

        // 开启关键词过滤时，列表只显示命中的消息
        boolean useFilter = sp.getBoolean("kwFilter", true);
        String kwRaw = sp.getString("keywords", "");
        if (kwRaw == null || kwRaw.trim().isEmpty()) kwRaw = Uploader.DEFAULT_KEYWORDS;
        String[] kws = kwRaw.split("[,，]");

        Cursor c = null;
        try {
            ContentResolver cr = getContentResolver();
            c = cr.query(MsgProvider.URI, null, sel, args, "id DESC");
            if (c == null) {
                box.addView(emptyHint("读不到数据"));
                return box;
            }
            stat[0] = 0;   // 稍后按实际展示数量回填
            if (c.getCount() == 0) {
                box.addView(emptyHint("还没有捕获到消息\n在微信 / 支付宝收发消息后这里会出现"));
                return box;
            }
            int iFrom = c.getColumnIndex(MsgProvider.C_FROM);
            int iText = c.getColumnIndex(MsgProvider.C_TEXT);
            int iTime = c.getColumnIndex(MsgProvider.C_TIME);
            int iKind = c.getColumnIndex(MsgProvider.C_KIND);
            int iPkg = c.getColumnIndex(MsgProvider.C_PKG);
            int iId = c.getColumnIndex(MsgProvider.C_ID);
            int iStatus = c.getColumnIndex(MsgProvider.C_STATUS);
            int iReqUrl = c.getColumnIndex(MsgProvider.C_REQ_URL);
            int iReqBody = c.getColumnIndex(MsgProvider.C_REQ_BODY);
            int iResp = c.getColumnIndex(MsgProvider.C_RESP);
            int n = 0;
            while (c.moveToNext() && n < 300) {
                long id = iId >= 0 ? c.getLong(iId) : 0L;
                String from = iFrom >= 0 ? c.getString(iFrom) : "";
                String text = iText >= 0 ? c.getString(iText) : "";
                long time = iTime >= 0 ? c.getLong(iTime) : 0L;
                String kind = iKind >= 0 ? c.getString(iKind) : "";
                String pkg = iPkg >= 0 ? c.getString(iPkg) : "";
                String status = iStatus >= 0 ? c.getString(iStatus) : "";
                String reqUrl = iReqUrl >= 0 ? c.getString(iReqUrl) : "";
                String reqBody = iReqBody >= 0 ? c.getString(iReqBody) : "";
                String resp = iResp >= 0 ? c.getString(iResp) : "";

                if (useFilter && !Uploader.hitKeyword(Uploader.cleanText(text, null), kws)) {
                    continue;
                }

                box.addView(buildRow(id, pkg, kind, from, text, time,
                        status, reqUrl, reqBody, resp),
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                n++;
                stat[0] = n;
            }
        } catch (Throwable t) {
            box.addView(emptyHint("读取失败：" + t.getMessage()));
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return box;
    }

    private View buildRow(final long id, String pkg, String kind, final String from,
                          final String text, final long time, final String status,
                          final String reqUrl, final String reqBody, final String resp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(this);
        boolean isWx = MsgProvider.PKG_WX.equals(pkg);
        boolean isSelf = MsgProvider.PKG_SELF.equals(pkg);
        tag.setText(isSelf ? "测试" : (isWx ? "微信" : "支付宝"));
        tag.setTextSize(11f);
        tag.setTextColor(Color.WHITE);
        tag.setBackgroundColor(isSelf ? 0xFF888888 : (isWx ? C_GREEN : 0xFF1677FF));
        tag.setPadding(dp(6), dp(2), dp(6), dp(2));
        head.addView(tag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView who = new TextView(this);
        String label = from == null ? "" : from;
        if ("box".equals(kind)) label = label + "（消息盒子）";
        who.setText(label);
        who.setTextSize(12f);
        who.setTextColor(C_SUB);
        who.setSingleLine(true);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wlp.leftMargin = dp(8);
        head.addView(who, wlp);

        // 上报状态点
        String st = status == null ? "" : status;
        boolean isSummary = Uploader.cleanText(text, null)
                .matches("(?s).*今日收款\\s*\\d+\\s*笔.*");
        if (MsgProvider.ST_OK.equals(st) || MsgProvider.ST_FAIL.equals(st) || isSummary) {
            TextView dot = new TextView(this);
            if (isSummary && !MsgProvider.ST_OK.equals(st)) {
                dot.setText("汇总");
                dot.setBackgroundColor(0xFF999999);
            } else {
                dot.setText(MsgProvider.ST_OK.equals(st) ? "已上报" : "失败");
                dot.setBackgroundColor(MsgProvider.ST_OK.equals(st) ? C_GREEN : 0xFFD9534F);
            }
            dot.setTextSize(10f);
            dot.setTextColor(Color.WHITE);
            dot.setPadding(dp(5), dp(1), dp(5), dp(1));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.rightMargin = dp(8);
            head.addView(dot, dlp);
        }

        TextView tv = new TextView(this);
        tv.setText(time > 0 ? FMT_T.format(new Date(time)) : "");
        tv.setTextSize(11f);
        tv.setTextColor(C_SUB);
        head.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 微信收款是 XML appmsg，列表里显示解析后的可读文本
        String shownText = Uploader.cleanText(text, null);
        TextView body = new TextView(this);
        body.setText(shownText);
        body.setTextSize(14f);
        body.setTextColor(C_TEXT);
        body.setMaxLines(3);
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDetail(id, from, text, time, status, reqUrl, reqBody, resp);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                confirmDelete(id, text);
                return true;
            }
        });
        card.setLayoutParams(clp);
        return card;
    }

    private void confirmDelete(final long id, String text) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录")
                .setMessage(text == null ? "" : text)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            getContentResolver().delete(MsgProvider.URI,
                                    MsgProvider.C_ID + "=?",
                                    new String[]{String.valueOf(id)});
                        } catch (Throwable ignored) {}
                        renderLog();
                    }
                })
                .show();
    }

    /** 详情：状态 / 来源 / 正文 / 上报请求 / 响应 */
    private void showDetail(long id, String from, String text, long time,
                            String status, String reqUrl, String reqBody, String resp) {
        final String copyText = text == null ? "" : text;

        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        root.setBackground(bg);
        root.setPadding(dp(20), dp(18), dp(20), dp(6));

        TextView title = new TextView(this);
        title.setText("消息详情");
        title.setTextSize(18f);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // 状态 + 时间
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, dp(12), 0, 0);

        TextView st = new TextView(this);
        String stText = "待上报";
        int stColor = C_SUB;
        if (MsgProvider.ST_OK.equals(status)) { stText = "上报成功"; stColor = C_GREEN; }
        else if (MsgProvider.ST_FAIL.equals(status)) { stText = "上报失败"; stColor = 0xFFD9534F; }
        else if (MsgProvider.ST_SKIP.equals(status)) { stText = "未命中关键词"; stColor = C_SUB; }
        else if (MsgProvider.ST_PENDING.equals(status)) { stText = "待上报"; stColor = C_SUB; }
        st.setText(stText);
        st.setTextSize(13f);
        st.setTextColor(stColor);
        st.setTypeface(null, Typeface.BOLD);
        topRow.addView(st, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tm = new TextView(this);
        tm.setText(time > 0 ? FMT.format(new Date(time)) : "");
        tm.setTextSize(11f);
        tm.setTextColor(C_SUB);
        topRow.addView(tm);
        root.addView(topRow);

        // 可滚动内容
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(detailLabel("来源"));
        box.addView(detailValue((from == null || from.isEmpty()) ? "-" : from));

        box.addView(detailLabel("消息内容"));
        box.addView(detailValue(Uploader.cleanText(text, null)));

        if (reqUrl != null && !reqUrl.isEmpty()) {
            box.addView(detailLabel("请求地址"));
            box.addView(detailValue(reqUrl));
        }
        if (reqBody != null && !reqBody.isEmpty()) {
            box.addView(detailLabel("请求内容"));
            box.addView(detailValue(reqBody));
        }
        if (resp != null && !resp.isEmpty()) {
            box.addView(detailLabel("响应结果"));
            box.addView(detailValue(resp));
        }

        sv.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tip = new TextView(this);
        tip.setText("长按列表中的记录可直接删除");
        tip.setTextSize(11f);
        tip.setTextColor(C_SUB);
        tip.setPadding(0, dp(10), 0, dp(2));
        root.addView(tip);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        btnRow.addView(textButton("复制", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("msg", copyText));
                Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        }));
        final long rid = id;
        final boolean canRetry = MsgProvider.ST_FAIL.equals(status)
                || MsgProvider.ST_PENDING.equals(status);
        if (canRetry) {
            btnRow.addView(textButton("重试", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Uploader.retry(MainActivity.this, rid);
                    Toast.makeText(MainActivity.this, "已重新上报", Toast.LENGTH_SHORT).show();
                    if (curTab == 1) renderLog();
                }
            }));
        }
        btnRow.addView(textButton("删除", new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete(id, copyText);
            }
        }));
        final Dialog fd = dlg;
        btnRow.addView(textButton("关闭", new View.OnClickListener() {
            @Override public void onClick(View v) { fd.dismiss(); }
        }));
        root.addView(btnRow);

        dlg.setContentView(root);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dlg.show();
        if (w != null) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            w.setAttributes(lp);
        }
    }

    private TextView textButton(String s, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(14f);
        tv.setTextColor(C_GREEN);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView detailLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12f);
        tv.setTextColor(C_SUB);
        tv.setPadding(0, dp(14), 0, dp(5));
        return tv;
    }

    private TextView detailValue(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13f);
        tv.setTextColor(C_TEXT);
        tv.setBackgroundColor(0xFFF4F4F4);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        return tv;
    }

    // ================= 设置页 =================

    private static final String[] MODES = {"码支付V1", "码支付V2"};
    private static final String DEF_KEYWORDS =
            "微信收款助手,微信支付收款,二维码赞赏到账,店员消息,收款单到账,"
          + "个人收款码到账,收款通知,微信收款商业版,对外收款,收钱码,店员通,"
          + "已转入余额,你已成功收款,通过扫码向你付款,成功收款";

    private Spinner spMode;
    private EditText etDomain, etPid, etKey;

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(10), dp(10), dp(24));

        // ============ 卡片 1：接口配置 ============
        LinearLayout c1 = card();

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView ml = new TextView(this);
        ml.setText("接口模式");
        ml.setTextSize(16f);
        ml.setTextColor(C_TEXT);
        modeRow.addView(ml, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        spMode = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, MODES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(ad);
        int sel = sp.getInt("mode", 0);
        spMode.setSelection(sel >= 0 && sel < MODES.length ? sel : 0);
        modeRow.addView(spMode, new LinearLayout.LayoutParams(
                dp(190), ViewGroup.LayoutParams.WRAP_CONTENT));
        c1.addView(modeRow);

        c1.addView(fieldLabel("平台域名"));
        etDomain = inputBox("https://pay.example.com（结尾不用加 /）",
                sp.getString("domain", ""));
        c1.addView(etDomain);

        c1.addView(fieldLabel("PID"));
        etPid = inputBox("wxpay1,alipay2（逗号分隔，通道ID跟在后面）",
                sp.getString("pid", ""));
        c1.addView(etPid);

        c1.addView(fieldLabel("密钥"));
        etKey = inputBox("平台分配的上报密钥", sp.getString("key", ""));
        c1.addView(etKey);

        c1.addView(hintLine("上报地址：格式应为 wxpay1,alipay2（逗号分隔）"));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, dp(16), 0, 0);

        TextView btnScan = pillButton("扫一扫", 0xFFE8F8EE, C_GREEN);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this,
                        "扫码接入暂未开放，请手动填写域名 / PID / 密钥",
                        Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnScan);

        TextView btnSave = pillButton("保存配置", C_GREEN, Color.WHITE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(10);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveConfig(); }
        });
        btnRow.addView(btnSave, slp);

        c1.addView(btnRow);
        page.addView(c1, cardParams());

        // ============ 卡片 2：转发规则 ============
        LinearLayout c2 = card();
        c2.setPadding(0, dp(4), 0, dp(4));
        c2.addView(sectionHeader("转发规则"));

        c2.addView(arrowItem("发送测试通知",
                "往通知栏发一条模拟收款，走完整上报流程，可在日志里看结果",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { sendTestNotice(); }
                }));
        c2.addView(divider());

        c2.addView(switchItem("关键词过滤", "只转发命中关键词的通知"));
        c2.addView(divider());

        c2.addView(arrowItem("关键词配置", keywordSummary(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { editKeywords(); }
                }));
        c2.addView(divider());

        c2.addView(arrowItem("排除关键词", excludeSummary(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) { editExclude(); }
                }));

        page.addView(c2, cardParams());

        // ============ 清空记录 ============
        TextView clear = new TextView(this);
        clear.setText("清空全部记录");
        clear.setTextSize(15f);
        clear.setTextColor(0xFFD9534F);
        clear.setGravity(Gravity.CENTER);
        clear.setBackgroundColor(C_CARD);
        clear.setPadding(0, dp(16), 0, dp(16));
        clear.setClickable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        page.addView(clear, clp);

        sv.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    // ---------- 设置页小部件 ----------

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(C_CARD);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        return c;
    }

    private TextView fieldLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13f);
        tv.setTextColor(C_SUB);
        tv.setPadding(0, dp(12), 0, dp(6));
        return tv;
    }

    private EditText inputBox(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(0xFFAEAEAE);
        et.setTextSize(14f);
        et.setTextColor(C_TEXT);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (value != null && !value.isEmpty()) et.setText(value);
        et.setBackgroundColor(0xFFF4F4F4);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        return et;
    }

    private TextView hintLine(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12f);
        tv.setTextColor(C_SUB);
        tv.setPadding(0, dp(10), 0, 0);
        return tv;
    }

    private TextView pillButton(String text, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10.5f);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(24));
        tv.setBackground(g);
        tv.setClickable(true);
        return tv;
    }

    private TextView sectionHeader(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12f);
        tv.setTextColor(C_SUB);
        tv.setPadding(dp(14), dp(10), dp(14), dp(8));
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(C_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private View arrowItem(String title, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(C_TEXT);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12f);
        s.setTextColor(C_SUB);
        s.setPadding(0, dp(4), 0, 0);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20f);
        arrow.setTextColor(0xFFBBBBBB);
        arrow.setPadding(dp(8), 0, 0, 0);
        row.addView(arrow);

        row.setClickable(true);
        row.setOnClickListener(click);
        return row;
    }

    private View switchItem(String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(C_TEXT);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(12f);
        s.setTextColor(C_SUB);
        s.setPadding(0, dp(4), 0, 0);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(sp.getBoolean("kwFilter", true));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                sp.edit().putBoolean("kwFilter", on).apply();
            }
        });
        row.addView(sw);
        return row;
    }

    private String keywordText() {
        return sp.getString("keywords", DEF_KEYWORDS);
    }

    private String keywordSummary() {
        String[] arr = keywordText().split(",");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : arr) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (n > 0) sb.append("，");
            sb.append(t);
            n++;
            if (n >= 4) break;
        }
        int total = 0;
        for (String s : arr) if (!s.trim().isEmpty()) total++;
        if (total > 4) sb.append(" 等 ").append(total).append(" 个");
        else sb.append(" 共 ").append(total).append(" 个");
        return sb.toString();
    }

    private String excludeText() {
        return sp.getString("exclude", Uploader.DEFAULT_EXCLUDE);
    }

    private String excludeSummary() {
        int total = 0;
        for (String x : excludeText().split("[,，]")) {
            if (!x.trim().isEmpty()) total++;
        }
        return "含这些字不上报（退款、红包等），共 " + total + " 个";
    }

    private void editExclude() {
        final EditText et = new EditText(this);
        et.setText(excludeText());
        et.setTextSize(13f);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("排除关键词")
                .setMessage("命中任一个就不上报（退款、优惠、红包等）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("exclude", Uploader.DEFAULT_EXCLUDE).apply();
                        selectTab(2);
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("exclude", et.getText().toString().trim()).apply();
                        selectTab(2);
                    }
                })
                .show();
    }

    private void saveConfig() {
        SharedPreferences.Editor e = sp.edit();
        e.putInt("mode", spMode.getSelectedItemPosition());
        e.putString("domain", etDomain.getText().toString().trim());
        e.putString("pid", etPid.getText().toString().trim());
        e.putString("key", etKey.getText().toString().trim());
        e.apply();
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void editKeywords() {
        final EditText et = new EditText(this);
        et.setText(keywordText());
        et.setTextSize(13f);
        et.setMinLines(8);
        et.setGravity(Gravity.TOP);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("关键词配置")
                .setMessage("用英文逗号分隔，命中任意一个即转发")
                .setView(et)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("keywords", DEF_KEYWORDS).apply();
                        selectTab(2);
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        sp.edit().putString("keywords", et.getText().toString().trim()).apply();
                        selectTab(2);
                    }
                })
                .show();
    }

    /** 发一条测试通知，写入库后立即走真实上报 */
    private void sendTestNotice() {
        String domain = sp.getString("domain", "");
        String pid = sp.getString("pid", "");
        String key = sp.getString("key", "");
        String text = "微信收款助手: 微信支付收款0.01元";

        notifyTest(text);

        if (domain.isEmpty() || pid.isEmpty() || key.isEmpty()) {
            Toast.makeText(this,
                    "请先填完整 平台域名 / PID / 密钥 再测试",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(MsgProvider.C_PKG, MsgProvider.PKG_SELF);
            v.put(MsgProvider.C_FROM, "微信收款助手");
            v.put(MsgProvider.C_TEXT, text);
            v.put(MsgProvider.C_TIME, System.currentTimeMillis());
            v.put(MsgProvider.C_KIND, "wx");
            v.put(MsgProvider.C_CREATED, System.currentTimeMillis());
            v.put(MsgProvider.C_STATUS, MsgProvider.ST_PENDING);
            getContentResolver().insert(MsgProvider.URI, v);
        } catch (Throwable ignored) {}

        Toast.makeText(this, "已投递测试消息，正在上报…", Toast.LENGTH_SHORT).show();
        Uploader.run(this);
        if (curTab == 1) renderLog();
    }

    private void notifyTest(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "tnotify_test";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel nc = new NotificationChannel(
                        ch, "测试通知", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(nc);
            }
            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    Build.VERSION.SDK_INT >= 23
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ch)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.drawable.ic_launcher_wx)
             .setContentTitle("微x通知 · 测试")
             .setContentText(text)
             .setAutoCancel(true)
             .setContentIntent(pi);
            nm.notify(1001, b.build());
        } catch (Throwable t) {
            Toast.makeText(this, "通知失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("确定删除全部已捕获的消息？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            getContentResolver().delete(MsgProvider.URI, null, null);
                        } catch (Throwable ignored) {}
                        if (curTab == 1) renderLog();
                    }
                })
                .show();
    }

    // ================= 小工具 =================

    private View emptyHint(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13f);
        tv.setTextColor(C_SUB);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(80), 0, dp(40));
        return tv;
    }

    private View placeholder(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(14f);
        tv.setTextColor(C_SUB);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
