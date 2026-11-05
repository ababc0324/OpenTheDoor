package io.github.ababc0324.openthedoor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Open the door —— 天猫校园 VOC 蓝牙门锁独立开锁应用。
 *
 * 「我的门 · 健康看板」身份：
 *   首页 = 门名（可改名）+ 状态角标 + 门锁状态看板（电量/信号/最近开门/自检）
 *          + 圆形开锁按钮（自绘门，成功开门动画）+ 开锁历史 + 凭证/同步 + 隐私中心。
 * 完全离线：开锁不联网；仅「检查更新」联网拉 GitHub Releases（同签名覆盖安装）。
 * 无遥测：历史/门名/凭证均只存本机。
 *
 * 预连接：前台自动预连接（30s 无操作断开），开锁后保活 60s，退后台立即让出。
 * 协议逐字节对齐 lmc2007/TMallKey（MIT，见 LICENSE/NOTICE），activationKey 妥善保管。
 */
public class MainActivity extends Activity {

    private static final int REQ_BLE_PERMS = 1001;
    private static final int REQ_EXPORT = 2001;
    private static final int REQ_IMPORT = 2002;

    private static final int LOW_BATTERY = 20;

    // ---------------- 深色配色 ----------------
    private static final int C_ACCENT = 0xFF22D3EE;
    private static final int C_ACCENT_DEEP = 0xFF0E7490;
    private static final int C_WIN_BG = 0xFF0B0C0E;
    private static final int C_FIELD_BG = 0xFF11151C;
    private static final int C_BORDER = 0xFF2A3240;
    private static final int C_TEXT = 0xFFF4F4F1;
    private static final int C_MUTED = 0xFF9096A0;
    private static final int C_OK = 0xFF34D399;
    private static final int C_ERR = 0xFFFF4D6D;
    private static final int C_LOGBG = 0xF00B0E13;
    private static final int C_LOGTEXT = 0xFF9BE8F5;

    // UI 引用
    private EditText etMac;
    private EditText etActKey;
    private CheckBox cbUseDecrypt;
    private EditText etSecretKey;
    private EditText etBase64Key;
    private TextView tvLog;
    private ScrollView logScroll;
    private Button btnSync;
    private Button btnRoot;
    private CheckBox cbAutoUnlock;
    private TextView statusText;
    private View statusDot;
    private FrameLayout heroBtn;
    private DoorGlyphView doorGlyph;
    private TextView heroHint;
    private TextView doorTitle;
    private TextView doorSubtitle;
    private TextView dashBattery;
    private TextView dashRssi;
    private TextView dashLastOpen;
    private TextView dashSelfTest;
    private TextView dashCreds;
    private TextView lowBattWarn;
    private TextView historyList;
    private Button btnBattery;
    private Button btnSelfTest;

    // 状态
    private CredentialStore store;
    private DoorLockClient client;
    private DoorHistory history;
    private SharedPreferences meta;
    private boolean running = false;
    private int lastBattery = -1;          // -1=未知
    private String lastSelfTest = "未运行";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(C_WIN_BG);
        w.setNavigationBarColor(C_WIN_BG);
        w.setBackgroundDrawable(new ColorDrawable(C_WIN_BG));
        store = new CredentialStore(this);
        history = new DoorHistory(this);
        meta = getSharedPreferences("doorunlock_meta", MODE_PRIVATE);
        client = new DoorLockClient(this);
        client.setConnectionListener((ready, connecting, addr) -> {
            updateConnStatus(ready, connecting);
            refreshDashboard();
        });
        buildUi();
        prefillFromStore();
        refreshDashboard();
        requestPermissions();
        View decor = getWindow().getDecorView();
        decor.setAlpha(0f);
        decor.animate().alpha(1f).setDuration(450).start();
    }

    private String doorName() { return meta.getString("name", "我的门"); }

    private void prefillFromStore() {
        Map<String, String> all = store.loadAll();
        if (all.isEmpty()) return;
        String lastMac = null;
        for (String mac : all.keySet()) lastMac = mac;
        String key = lastMac != null ? all.get(lastMac) : null;
        if (lastMac == null || key == null) return;
        etMac.setText(formatMac(lastMac));
        etActKey.setText(key);
        log("已回填上次同步的钥匙（MAC " + formatMac(lastMac) + "）");
    }

    private static String formatMac(String mac) {
        if (mac == null) return "";
        String m = mac.replace(":", "");
        if (m.length() != 12) return mac;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(m.substring(i, i + 2));
        }
        return sb.toString();
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onStart() {
        super.onStart();
        client.setForeground(true);
        if (hasBlePermissions()) {
            String mac = etMac.getText().toString().trim();
            if (mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
                client.preconnect(mac);
            }
        }
        // 静默检查更新（>24h 一次）
        long last = meta.getLong("last_update_check", 0);
        if (System.currentTimeMillis() - last > 24L * 3600_000) {
            meta.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            silentUpdateCheck();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        client.setForeground(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeySyncReceiver.setHandler((mac, key) -> {
            etMac.setText(formatMac(mac));
            etActKey.setText(key);
            log("收到补丁版同步的钥匙：MAC " + formatMac(mac));
            if (cbAutoUnlock.isChecked() && !running) {
                startUnlock(formatMac(mac), key);
            }
        });
        refreshDashboard();
    }

    @Override
    protected void onPause() {
        super.onPause();
        KeySyncReceiver.setHandler(null);
    }

    // ==================== UI 构建 ====================

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        AmbientBackgroundView bg = new AmbientBackgroundView(this);
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(6), 0, dp(10));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        content.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHeader(panel);
        buildDashboard(panel);
        buildHero(panel);
        buildHistory(panel);
        buildCredential(panel);
        buildSync(panel);
        buildLog(panel);
        buildPrivacy(panel);
        buildFooter(panel);

        setContentView(root);
    }

    private void buildHeader(LinearLayout panel) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(14));

        doorTitle = tv(doorName());
        doorTitle.setTextColor(C_TEXT);
        doorTitle.setTextSize(30);
        doorTitle.setTypeface(Typeface.DEFAULT_BOLD);
        doorTitle.setLetterSpacing(0.01f);
        doorTitle.setOnClickListener(v -> renameDoorDialog());
        header.addView(doorTitle);

        doorSubtitle = tv("VOC 蓝牙门锁  ·  完全离线开锁  ·  点击门名可改名");
        doorSubtitle.setTextColor(C_MUTED);
        doorSubtitle.setTextSize(13);
        doorSubtitle.setPadding(0, dp(2), 0, 0);
        header.addView(doorSubtitle);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(14), 0, 0);
        statusDot = new View(this);
        statusDot.setLayoutParams(new LinearLayout.LayoutParams(dp(10), dp(10)));
        statusDot.setBackground(oval(C_MUTED));
        if (Build.VERSION.SDK_INT >= 28) statusDot.setElevation(dp(6));
        statusRow.addView(statusDot);
        statusText = tv("○ 未连接");
        statusText.setTextColor(C_MUTED);
        statusText.setTextSize(13);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setPadding(dp(8), 0, 0, 0);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(statusText);
        header.addView(statusRow);
        panel.addView(header);
        panel.addView(divider());
    }

    private void buildDashboard(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("门锁状态"));
        // 电量 / 信号 两列
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        dashBattery = dashCell("电量", "—");
        dashRssi = dashCell("信号", "—");
        row.addView(dashBattery, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(dashRssi, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);

        // 最近开门
        LinearLayout lastRow = dashRow();
        lastRow.addView(dashLabel("最近开门"));
        dashLastOpen = dashValue("—");
        lastRow.addView(dashLastOpen);
        card.addView(lastRow);
        // 自检
        LinearLayout selfRow = dashRow();
        selfRow.addView(dashLabel("协议自检"));
        dashSelfTest = dashValue("未运行");
        selfRow.addView(dashSelfTest);
        card.addView(selfRow);
        // 凭证
        LinearLayout credRow = dashRow();
        credRow.addView(dashLabel("本机凭证"));
        dashCreds = dashValue("0 把锁");
        credRow.addView(dashCreds);
        card.addView(credRow);

        lowBattWarn = tv("");
        lowBattWarn.setTextColor(C_ERR);
        lowBattWarn.setTextSize(12.5f);
        lowBattWarn.setTypeface(Typeface.DEFAULT_BOLD);
        lowBattWarn.setVisibility(View.GONE);
        lowBattWarn.setPadding(0, dp(6), 0, 0);
        card.addView(lowBattWarn);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnBattery = miniBtn("◉ 查询电量");
        btnBattery.setOnClickListener(v -> onBatteryClick());
        btnSelfTest = miniBtn("⚙ 一键体检");
        btnSelfTest.setOnClickListener(v -> onSelfTestClick());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        bl.setMargins(0, dp(10), dp(6), 0);
        btnBattery.setLayoutParams(bl);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        sl.setMargins(dp(6), dp(10), 0, 0);
        btnSelfTest.setLayoutParams(sl);
        btnRow.addView(btnBattery);
        btnRow.addView(btnSelfTest);
        card.addView(btnRow);
        panel.addView(card);
        panel.addView(divider());
    }

    private TextView dashCell(String name, String value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setBackground(rounded(0x0D22D3EE, C_BORDER, dp(12)));
        cell.setPadding(dp(10), dp(10), dp(10), dp(10));
        TextView n = tv(name);
        n.setTextColor(C_MUTED);
        n.setTextSize(11);
        n.setGravity(Gravity.CENTER);
        cell.addView(n);
        TextView v = tv(value);
        v.setTextColor(C_TEXT);
        v.setTextSize(19);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        cell.addView(v);
        return v;
    }

    private LinearLayout dashRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, 0);
        return r;
    }

    private TextView dashLabel(String s) {
        TextView t = tv(s);
        t.setTextColor(C_MUTED);
        t.setTextSize(12.5f);
        t.setPadding(0, 0, 0, 0);
        return t;
    }

    private TextView dashValue(String s) {
        TextView t = tv(s);
        t.setTextColor(C_TEXT);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.END);
        t.setPadding(0, 0, 0, 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private Button miniBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13.5f);
        b.setAllCaps(false);
        b.setTextColor(C_ACCENT);
        b.setBackground(ripple(rounded(0x0D22D3EE, C_BORDER, dp(12))));
        b.setHapticFeedbackEnabled(true);
        return b;
    }

    private void buildHero(LinearLayout panel) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(20), 0, dp(16));

        heroBtn = new FrameLayout(this);
        int heroSize = dp(176);
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(heroSize, heroSize);
        heroLp.gravity = Gravity.CENTER_HORIZONTAL;
        heroBtn.setLayoutParams(heroLp);
        GradientDrawable grad = new GradientDrawable();
        grad.setShape(GradientDrawable.OVAL);
        grad.setOrientation(GradientDrawable.Orientation.TL_BR);
        grad.setColors(new int[]{C_ACCENT, C_ACCENT_DEEP});
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x1A22D3EE);
        ring.setStroke(dp(2), 0x6622D3EE);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ring, grad});
        ld.setLayerInset(1, dp(5), dp(5), dp(5), dp(5));
        heroBtn.setBackground(ripple(ld));
        heroBtn.setElevation(dp(10));
        if (Build.VERSION.SDK_INT >= 28) {
            heroBtn.setOutlineAmbientShadowColor(C_ACCENT);
            heroBtn.setOutlineSpotShadowColor(C_ACCENT);
        }
        heroBtn.setClickable(true);
        heroBtn.setOnClickListener(v -> onUnlockClick());

        doorGlyph = new DoorGlyphView(this);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(84), dp(84));
        glp.gravity = Gravity.CENTER;
        doorGlyph.setLayoutParams(glp);
        heroBtn.addView(doorGlyph);
        hero.addView(heroBtn);

        heroHint = tv("点击按钮开锁");
        heroHint.setTextColor(C_MUTED);
        heroHint.setTextSize(14);
        heroHint.setGravity(Gravity.CENTER);
        heroHint.setPadding(0, dp(14), 0, 0);
        hero.addView(heroHint);
        panel.addView(hero);
        panel.addView(divider());
    }

    private void buildHistory(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("开锁历史"));
        historyList = tv("");
        historyList.setTextColor(C_MUTED);
        historyList.setTextSize(12.5f);
        historyList.setLineSpacing(dp(2), 1f);
        historyList.setPadding(0, dp(2), 0, dp(4));
        card.addView(historyList);
        panel.addView(card);
        panel.addView(divider());
    }

    private void buildCredential(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("门锁凭证"));
        etMac = field("门锁 BLE 地址  如 00:11:22:33:44:55", false);
        card.addView(etMac);
        etActKey = field("activationKey（32 位 hex）", true);
        etActKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(etActKey);

        cbUseDecrypt = toggle("改用「base64 密文 + voc_lock_secret_key」解密", false);
        card.addView(cbUseDecrypt);
        etSecretKey = field("voc_lock_secret_key（32 字节 hex）", false);
        etSecretKey.setVisibility(View.GONE);
        card.addView(etSecretKey);
        etBase64Key = field("服务器下发的 base64 密文 activationKey", false);
        etBase64Key.setVisibility(View.GONE);
        card.addView(etBase64Key);
        cbUseDecrypt.setOnCheckedChangeListener((btn, checked) -> {
            etSecretKey.setVisibility(checked ? View.VISIBLE : View.GONE);
            etBase64Key.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        // 加密备份
        LinearLayout bkRow = new LinearLayout(this);
        bkRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnExport = miniBtn("⇧ 导出备份");
        btnExport.setOnClickListener(v -> onExportBackup());
        Button btnImport = miniBtn("⇩ 导入备份");
        btnImport.setOnClickListener(v -> onImportBackup());
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, dp(44), 1f);
        el.setMargins(0, dp(10), dp(6), 0);
        btnExport.setLayoutParams(el);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, dp(44), 1f);
        il.setMargins(dp(6), dp(10), 0, 0);
        btnImport.setLayoutParams(il);
        bkRow.addView(btnExport);
        bkRow.addView(btnImport);
        card.addView(bkRow);
        panel.addView(card);
        panel.addView(divider());
    }

    private void buildSync(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("钥匙同步"));
        btnSync = btn("◈  一键同步钥匙（补丁版天猫校园）", false);
        btnSync.setOnClickListener(v -> onSyncClick());
        card.addView(btnSync);
        btnRoot = btn("⚡  root 抓取钥匙（已 root，无需补丁版）", false);
        btnRoot.setOnClickListener(v -> onRootGrabClick());
        LinearLayout.LayoutParams brlp = (LinearLayout.LayoutParams) btnRoot.getLayoutParams();
        brlp.topMargin = dp(8);
        card.addView(btnRoot);
        cbAutoUnlock = toggle("同步到钥匙后自动开锁", true);
        cbAutoUnlock.setPadding(0, dp(6), 0, 0);
        card.addView(cbAutoUnlock);
        panel.addView(card);
        panel.addView(divider());
    }

    private void buildLog(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("运行日志"));
        logScroll = new ScrollView(this);
        logScroll.setBackground(rounded(C_LOGBG, C_BORDER, dp(12)));
        logScroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvLog = new TextView(this);
        tvLog.setTextColor(C_LOGTEXT);
        tvLog.setTextSize(11.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        panel.addView(card);
        panel.addView(divider());
    }

    private void buildPrivacy(LinearLayout panel) {
        LinearLayout card = section();
        card.addView(label("隐私"));
        TextView st = tv("本应用 100% 离线 · 无遥测。门锁钥匙、开锁历史、门名仅存本机，\n" +
                "从不上传/外传；仅「检查更新」联网（GitHub）。");
        st.setTextColor(C_MUTED);
        st.setTextSize(12);
        st.setLineSpacing(dp(2), 1f);
        st.setPadding(0, 0, 0, dp(4));
        card.addView(st);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button btnClearHist = miniBtn("🗑 清除历史");
        btnClearHist.setOnClickListener(v -> confirm("清除开锁历史？", () -> {
            history.clear();
            refreshDashboard();
            log("已清除开锁历史");
        }));
        Button btnClearAll = miniBtn("⚠ 清除全部数据");
        btnClearAll.setOnClickListener(v -> confirm("清除全部数据（钥匙 + 历史 + 门名）？此操作不可撤销。", () -> {
            for (String mac : new ArrayList<>(store.loadAll().keySet())) store.remove(mac);
            history.clear();
            meta.edit().remove("name").apply();
            etMac.setText("");
            etActKey.setText("");
            doorTitle.setText(doorName());
            refreshDashboard();
            log("已清除全部本地数据");
            toast("已清除全部数据");
        }));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, dp(44), 1f);
        hl.setMargins(0, dp(10), dp(6), 0);
        btnClearHist.setLayoutParams(hl);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(44), 1f);
        al.setMargins(dp(6), dp(10), 0, 0);
        btnClearAll.setLayoutParams(al);
        row.addView(btnClearHist);
        row.addView(btnClearAll);
        card.addView(row);
        panel.addView(card);
        panel.addView(divider());
    }

    private void buildFooter(LinearLayout panel) {
        LinearLayout footer = section();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        String version = UpdateManager.currentVersion(this);
        TextView ver = tv("Open the door  v" + version + "   ·   检查更新");
        ver.setTextColor(C_MUTED);
        ver.setTextSize(12.5f);
        ver.setGravity(Gravity.CENTER);
        ver.setOnClickListener(v -> onCheckUpdateClick());
        footer.addView(ver);
        TextView gh = tv("github.com/ababc0324/OpenTheDoor");
        gh.setTextColor(C_ACCENT);
        gh.setTextSize(12);
        gh.setTypeface(Typeface.DEFAULT_BOLD);
        gh.setGravity(Gravity.CENTER);
        gh.setPadding(0, dp(8), 0, 0);
        gh.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/ababc0324/OpenTheDoor")));
            } catch (Exception ignored) { }
        });
        footer.addView(gh);
        panel.addView(footer);
    }

    // ==================== 看板刷新 ====================

    private void refreshDashboard() {
        if (dashBattery == null) return;
        dashBattery.setText(lastBattery >= 0 ? lastBattery + "%" : "—");
        int rssi = client.getLastRssi();
        dashRssi.setText(client.hasRssi() ? rssi + " dBm" : "—");
        List<DoorHistory.Entry> all = history.loadAll();
        if (!all.isEmpty()) {
            DoorHistory.Entry e = all.get(0);
            String t = android.text.format.DateFormat.format("MM-dd HH:mm", e.ts).toString();
            dashLastOpen.setText(t);
            StringBuilder sb = new StringBuilder();
            int n = Math.min(6, all.size());
            for (int i = 0; i < n; i++) {
                DoorHistory.Entry h = all.get(i);
                sb.append(android.text.format.DateFormat.format("MM-dd HH:mm", h.ts))
                        .append("  ·  ").append(h.name);
                if (h.mac != null && h.mac.length() > 0) sb.append("  (").append(formatMac(h.mac)).append(")");
                if (i < n - 1) sb.append('\n');
            }
            historyList.setText(sb.toString());
        } else {
            dashLastOpen.setText("—");
            historyList.setText("还没有开门记录。开锁成功后这里会记录。");
        }
        dashSelfTest.setText(lastSelfTest);
        dashCreds.setText(store.loadAll().size() + " 把锁");
        // 低电量
        if (lastBattery >= 0 && lastBattery < LOW_BATTERY) {
            lowBattWarn.setText("⚠ 门锁电量偏低（" + lastBattery + "%），建议及时更换电池");
            lowBattWarn.setVisibility(View.VISIBLE);
        } else {
            lowBattWarn.setVisibility(View.GONE);
        }
        // 门名 + 守护次数
        doorTitle.setText(doorName());
        doorSubtitle.setText("VOC 蓝牙门锁  ·  完全离线  ·  已守护 " + history.count() + " 次  ·  点门名改名");
    }

    // ==================== 电量查询 ====================

    private void onBatteryClick() {
        if (running) { toast("进行中，请等待"); return; }
        String mac = etMac.getText().toString().trim();
        if (!mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) { toast("MAC 地址格式错误"); return; }
        String key = AesUtils.normalizeActivationKey(etActKey.getText().toString().trim());
        if (key == null) { toast("请先填写 activationKey"); return; }
        running = true;
        heroHint("查询电量中…");
        log("查询门锁电量 ...");
        client.queryBattery(mac, key, new DoorLockClient.Listener() {
            @Override public void onProgress(String m) { log(m); }
            @Override public void onSuccess(String m) {
                log(m);
                int pct = -1;
                try { pct = Integer.parseInt(m.replaceAll("[^0-9]", "")); } catch (Exception ignored) { }
                if (pct >= 0) lastBattery = pct;
                running = false;
                refreshDashboard();
                toast("电量 " + (pct >= 0 ? pct + "%" : m));
                if (pct >= 0 && pct < LOW_BATTERY) {
                    toast("⚠ 门锁电量偏低：" + pct + "%");
                }
            }
            @Override public void onError(String m) {
                log("电量查询失败: " + m);
                toast("电量查询失败");
                running = false;
                heroHint("电量查询失败");
            }
        });
    }

    // ==================== 一键体检 ====================

    private void onSelfTestClick() {
        if (running) { toast("进行中，请等待"); return; }
        running = true;
        heroHint("体检中…");
        log("运行协议自检（24 向量 + 环境）...");
        new Thread(() -> {
            ProtocolSelfTest.Result rp = ProtocolSelfTest.runProtocol();
            ProtocolSelfTest.Result re = ProtocolSelfTest.runEnvironment(this);
            StringBuilder sb = new StringBuilder();
            sb.append("协议向量 ").append(rp.pass).append("/").append(rp.pass + rp.fail).append(rp.ok() ? " ✓" : " ✗");
            sb.append(" · 环境 ").append(re.pass).append("/").append(re.pass + re.fail);
            if (!re.ok()) sb.append(" ✗");
            String summary = sb.toString();
            for (String l : rp.lines) log("  " + l);
            for (String l : re.lines) log("  " + l);
            runOnUiThread(() -> {
                lastSelfTest = summary + "  ·  " + android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis());
                refreshDashboard();
                log(summary);
                if (rp.ok() && re.ok()) {
                    toast("体检通过：协议 24/24 ✓");
                    heroHint("体检通过 ✓");
                } else {
                    toast("体检有异常（详见日志）");
                    heroHint("体检异常 ✗");
                }
                running = false;
            });
        }).start();
    }

    // ==================== 自更新 ====================

    private void silentUpdateCheck() {
        UpdateManager.checkAsync(this, new UpdateManager.Callback() {
            @Override public void onResult(boolean has, String v, String cl, String url, String sha) {
                if (has) runOnUiThread(() -> showUpdateDialog(v, cl, url, sha));
            }
            @Override public void onError(String m) { /* 静默失败不打扰 */ }
        });
    }

    private void onCheckUpdateClick() {
        heroHint("检查更新中…");
        log("检查更新 ...");
        UpdateManager.checkAsync(this, new UpdateManager.Callback() {
            @Override public void onResult(boolean has, String v, String cl, String url, String sha) {
                runOnUiThread(() -> {
                    heroHint("已是最新版本");
                    if (has) showUpdateDialog(v, cl, url, sha);
                    else toast("已是最新版本 v" + UpdateManager.currentVersion(MainActivity.this));
                });
            }
            @Override public void onError(String m) {
                runOnUiThread(() -> { toast("检查更新失败：" + m); heroHint("检查更新失败"); });
            }
        });
    }

    private void showUpdateDialog(String version, String changelog, String url, String sha) {
        String body = "发现新版本 v" + version + "\n\n" + (changelog == null ? "" : changelog) + "\n\n下载后同签名覆盖安装。";
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(body)
                .setPositiveButton("下载并更新", (d, w) -> downloadAndInstall(url, sha))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void downloadAndInstall(final String url, final String sha) {
        heroHint("下载更新中…");
        new Thread(() -> {
            try {
                FileHolder f = new FileHolder();
                f.file = UpdateManager.download(this, url, sha);
                runOnUiThread(() -> {
                    try {
                        UpdateManager.install(this, f.file);
                        toast("已开始安装更新");
                        heroHint("安装中…");
                    } catch (Exception e) {
                        toast(e.getMessage());
                        heroHint("安装需要授权");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { toast("下载更新失败：" + e.getMessage()); heroHint("更新失败"); });
            }
        }).start();
    }

    private static final class FileHolder { java.io.File file; }

    // ==================== 备份（SAF） ====================

    private void onExportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, "openthedoor-backup.otdb");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void onImportBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            promptPassphrase(false, pass -> {
                new Thread(() -> {
                    try {
                        String payload = CredentialBackup.export(store, pass);
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            os.write(payload.getBytes("UTF-8"));
                        }
                        runOnUiThread(() -> { toast("备份已导出"); log("凭证备份导出成功（口令加密）"); });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("导出失败：" + e.getMessage()));
                    }
                }).start();
            });
        } else if (requestCode == REQ_IMPORT) {
            promptPassphrase(true, pass -> {
                new Thread(() -> {
                    try {
                        StringBuilder sb = new StringBuilder();
                        try (InputStream is = getContentResolver().openInputStream(uri);
                             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                            String line;
                            while ((line = r.readLine()) != null) sb.append(line);
                        }
                        int n = CredentialBackup.importTo(store, sb.toString(), pass);
                        runOnUiThread(() -> {
                            toast("已导入 " + n + " 把锁的钥匙");
                            log("凭证导入成功，共 " + n + " 把锁");
                            prefillFromStore();
                            refreshDashboard();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("导入失败：" + e.getMessage()));
                    }
                }).start();
            });
        }
    }

    private void promptPassphrase(boolean confirm, final java.util.function.Consumer<String> cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText pass = new EditText(this);
        pass.setHint("设置备份口令");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);
        EditText pass2 = null;
        if (confirm) {
            pass2 = new EditText(this);
            pass2.setHint("再次输入口令");
            pass2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            pass2.setLayoutParams(lp);
            box.addView(pass2);
        }
        EditText pass2Final = pass2;
        new AlertDialog.Builder(this)
                .setTitle(confirm ? "导入备份" : "导出备份")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String p = pass.getText().toString();
                    if (p.length() < 4) { toast("口令至少 4 位"); return; }
                    if (confirm && !p.equals(pass2Final.getText().toString())) { toast("两次口令不一致"); return; }
                    cb.accept(p);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 开锁流程 ====================

    private void onUnlockClick() {
        if (running) { toast("进行中，请等待"); return; }
        String mac = etMac.getText().toString().trim();
        if (!mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) { toast("MAC 地址格式错误"); return; }
        String activationKey;
        if (cbUseDecrypt.isChecked()) {
            String base64 = etBase64Key.getText().toString().trim();
            String secret = etSecretKey.getText().toString().trim();
            if (base64.isEmpty() || secret.isEmpty()) { toast("请输入 base64 密文与 voc_lock_secret_key"); return; }
            activationKey = AesUtils.decryptActivationKey(base64, secret);
            if (activationKey == null) { log("解密失败：检查 base64 密文与 voc_lock_secret_key"); toast("解密失败"); return; }
            log("解密得到 activationKey = " + activationKey);
        } else {
            String manual = etActKey.getText().toString().trim();
            activationKey = AesUtils.normalizeActivationKey(manual);
            if (activationKey == null) { log("activationKey 格式错误（需 32 位 hex）"); toast("activationKey 格式错误（需 32 位 hex）"); return; }
        }
        startUnlock(mac, activationKey);
    }

    private void startUnlock(String mac, String activationKey) {
        running = true;
        heroBtn.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        heroHint("开锁中…");
        doorGlyph.setOpened(false, true);
        log("开始开锁 ... activationKey=" + activationKey);
        client.unlock(mac, activationKey, new DoorLockClient.Listener() {
            @Override public void onProgress(String m) { log(m); }
            @Override public void onSuccess(String m) {
                log(m);
                toast("已开门 ✓");
                heroHint("已开门 · 欢迎回来 ✓");
                doorGlyph.setOpened(true, true);
                heroBtn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                history.add(doorName(), mac);
                running = false;
                refreshDashboard();
            }
            @Override public void onError(String m) {
                log("错误: " + m);
                toast(m);
                heroHint("开锁失败 ✗");
                doorGlyph.setOpened(false, false);
                running = false;
            }
        });
    }

    private void heroHint(String s) { heroHint.setText(s); }

    private void renameDoorDialog() {
        EditText e = new EditText(this);
        e.setText(doorName());
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("给门起个名字")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) n = "我的门";
                    meta.edit().putString("name", n).apply();
                    refreshDashboard();
                    log("门名已设为「" + n + "」");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 一键同步 ====================

    private void onSyncClick() {
        if (running) { toast("进行中，请等待"); return; }
        heroHint("同步钥匙中…");
        log("正在从补丁版天猫校园同步钥匙 ...");
        KeySyncHelper.pullAsync(new KeySyncHelper.Callback() {
            @Override public void onResult(final List<String[]> locks) {
                runOnUiThread(() -> {
                    if (locks.isEmpty()) {
                        log("未捕获到钥匙。请先打开补丁版天猫校园 → 门锁页 → 点一次开锁，再回来重试。");
                        toast("未同步到钥匙（请先在补丁版点一次开锁）");
                        heroHint("未同步到钥匙");
                        return;
                    }
                    for (String[] lk : locks) store.save(lk[0], lk[1]);
                    String[] last = locks.get(locks.size() - 1);
                    etMac.setText(formatMac(last[0]));
                    etActKey.setText(last[1]);
                    log("同步成功，共 " + locks.size() + " 把锁：");
                    for (String[] lk : locks) log("  MAC " + formatMac(lk[0]) + "  activationKey=" + lk[1]);
                    toast("已同步 " + locks.size() + " 把锁的钥匙");
                    heroHint("已同步 " + locks.size() + " 把锁");
                    refreshDashboard();
                    if (cbAutoUnlock.isChecked()) startUnlock(formatMac(last[0]), last[1]);
                });
            }
            @Override public void onError(final String message) {
                runOnUiThread(() -> { log("同步失败: " + message); toast("同步失败"); heroHint("同步失败"); });
            }
        });
    }

    // ==================== root 抓取 ====================

    private void onRootGrabClick() {
        if (running) { toast("进行中，请等待"); return; }
        heroHint("root 抓取中…");
        log("正在 root 抓取钥匙 ...");
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "cat /data/data/com.tmall.campus.and/files/doorunlock_sync.txt"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                p.waitFor();
                final int exit = p.exitValue();
                final String text = sb.toString();
                runOnUiThread(() -> {
                    if (exit != 0 || text.trim().isEmpty()) {
                        log("root 抓取失败（需要 root；且需已在 LSPosed/补丁版天猫校园里点过一次开锁）");
                        toast("root 抓取失败");
                        heroHint("root 抓取失败");
                        return;
                    }
                    List<String[]> locks = new ArrayList<>();
                    for (String l : text.split("\n")) {
                        int tab = l.indexOf('\t');
                        if (tab > 0 && tab < l.length() - 1) {
                            String mac = l.substring(0, tab).trim();
                            String key = l.substring(tab + 1).trim();
                            if (mac.length() > 0 && key.length() > 0) locks.add(new String[]{mac, key});
                        }
                    }
                    if (locks.isEmpty()) { log("root 文件存在但未解析到钥匙"); toast("未解析到钥匙"); heroHint("未解析到钥匙"); return; }
                    for (String[] lk : locks) store.save(lk[0], lk[1]);
                    String[] last = locks.get(locks.size() - 1);
                    etMac.setText(formatMac(last[0]));
                    etActKey.setText(last[1]);
                    log("root 抓取成功，共 " + locks.size() + " 把锁：");
                    for (String[] lk : locks) log("  MAC " + formatMac(lk[0]) + "  activationKey=" + lk[1]);
                    toast("已抓取 " + locks.size() + " 把锁的钥匙");
                    heroHint("已抓取 " + locks.size() + " 把锁");
                    refreshDashboard();
                    if (cbAutoUnlock.isChecked()) startUnlock(formatMac(last[0]), last[1]);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> { log("root 抓取异常: " + msg); toast("root 抓取失败"); heroHint("root 抓取失败"); });
            }
        }).start();
    }

    // ==================== 样式助手 ====================

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(20), dp(14), dp(20), dp(14));
        return s;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x4D2A3240);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(20), 0, dp(20), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String text) {
        TextView t = tv(text);
        t.setTextColor(C_ACCENT);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private EditText field(String hint, boolean mono) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(C_MUTED);
        e.setTextSize(14);
        e.setTextColor(C_TEXT);
        e.setSingleLine(true);
        if (mono) e.setTypeface(Typeface.MONOSPACE);
        e.setBackground(rounded(C_FIELD_BG, C_BORDER, dp(12)));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setMinHeight(dp(46));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        e.setLayoutParams(lp);
        return e;
    }

    private Button btn(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(50));
        if (primary) {
            b.setTextColor(0xFF052A33);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{C_ACCENT, C_ACCENT_DEEP});
            grad.setCornerRadius(dp(14));
            GradientDrawable halo = rounded(0x1A22D3EE, 0x6622D3EE, dp(14));
            LayerDrawable ld = new LayerDrawable(new Drawable[]{halo, grad});
            ld.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
            b.setBackground(ripple(ld));
            b.setElevation(dp(8));
            if (Build.VERSION.SDK_INT >= 28) {
                b.setOutlineAmbientShadowColor(C_ACCENT);
                b.setOutlineSpotShadowColor(C_ACCENT);
            }
        } else {
            b.setTextColor(C_ACCENT);
            b.setBackground(ripple(rounded(0x0D22D3EE, C_BORDER, dp(12))));
        }
        b.setHapticFeedbackEnabled(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.RippleDrawable ripple(Drawable bg) {
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(0x40FFFFFF);
        return new android.graphics.drawable.RippleDrawable(ripple, bg, null);
    }

    private CheckBox toggle(String text, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(checked);
        c.setTextColor(C_TEXT);
        c.setTextSize(13.5f);
        c.setPadding(dp(4), dp(4), 0, dp(4));
        return c;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(dp(10), dp(10));
        return g;
    }

    private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp((int) radiusDp));
        if (stroke != fill) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView tv(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** 状态角标：连接状态（由 ConnectionListener 驱动）。 */
    private void updateConnStatus(boolean ready, boolean connecting) {
        if (ready) setStatus("● 已预连接", C_OK);
        else if (connecting) setStatus("◌ 预连接中", C_ACCENT);
        else setStatus("○ 未连接", C_MUTED);
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusText.setTextColor(color);
        statusDot.setBackground(oval(color));
        if (Build.VERSION.SDK_INT >= 28) {
            statusDot.setOutlineAmbientShadowColor(color);
            statusDot.setOutlineSpotShadowColor(color);
        }
    }

    private void log(String msg) {
        if (tvLog == null) return;
        String line = "[" + android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + "] " + msg + "\n";
        String cur = tvLog.getText().toString();
        if (cur.length() > 16000) {
            cur = cur.substring(cur.length() - 12000);
            int nl = cur.indexOf('\n');
            if (nl >= 0) cur = cur.substring(nl + 1);
        }
        tvLog.setText(cur + line);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ==================== 权限 ====================

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_BLE_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { log("部分权限未授予，可能无法连接蓝牙"); break; }
            }
        }
    }

    private void confirm(String msg, final Runnable ok) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("确定", (d, w) -> ok.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
