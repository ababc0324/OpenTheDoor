package io.github.ababc0324.openthedoor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Open the door —— 天猫校园 VOC 蓝牙门锁独立开锁应用（深色科技风）。
 *
 * 主路径：手动输入已解密的 32 位 hex activationKey（一次性抓取，之后完全离线、永久有效）。
 * 可选便捷路径：粘贴 base64 密文 + voc_lock_secret_key，用 AES/GCM 解密得到 activationKey。
 * 同步：补丁版天猫校园（LSPatch 模块）抓钥匙 → 「一键同步钥匙」/「root 抓取」→ 自动开锁。
 *
 * 预连接：进入前台自动预连接（30s 无操作断开），开锁后保活 60s，退后台立即让出。
 * 顶部状态角标：● 已预连接 / ◌ 预连接中 / ○ 未连接。
 *
 * 协议逐字节对齐 lmc2007/TMallKey（MIT），activationKey 是每把锁固定的钥匙，请妥善保管。
 */
public class MainActivity extends Activity {

    private static final int REQ_BLE_PERMS = 1001;

    // ---------------- 深色配色（通用科技风，无品牌依赖）----------------
    private static final int C_ACCENT = 0xFF22D3EE;      // Cyan（主色）
    private static final int C_ACCENT_DEEP = 0xFF0E7490; // Deep Cyan
    private static final int C_WIN_BG = 0xFF0B0C0E;      // 深墨
    private static final int C_FIELD_BG = 0xFF11151C;    // 输入底
    private static final int C_BORDER = 0xFF2A3240;      // 细描边
    private static final int C_TEXT = 0xFFF4F4F1;        // 正文
    private static final int C_MUTED = 0xFF9096A0;       // 辅助灰
    private static final int C_OK = 0xFF34D399;          // 成功绿
    private static final int C_ERR = 0xFFFF4D6D;         // 错误红
    private static final int C_LOGBG = 0xF00B0E13;       // 日志底
    private static final int C_LOGTEXT = 0xFF9BE8F5;     // 日志字

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
    private LockGlyphView glyph;
    private TextView heroHint;

    private CredentialStore store;
    private DoorLockClient client;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 深色窗口（状态栏/导航栏/背景），防启动白闪
        Window w = getWindow();
        w.setStatusBarColor(C_WIN_BG);
        w.setNavigationBarColor(C_WIN_BG);
        w.setBackgroundDrawable(new ColorDrawable(C_WIN_BG));
        store = new CredentialStore(this);
        // 单例客户端：预连接 + 连接复用（跨多次开锁）
        client = new DoorLockClient(this);
        client.setConnectionListener((ready, connecting, addr) ->
                updateConnStatus(ready, connecting));
        buildUi();
        prefillFromStore();
        requestPermissions();
        // 高级感：内容淡入
        View decor = getWindow().getDecorView();
        decor.setAlpha(0f);
        decor.animate().alpha(1f).setDuration(450).start();
    }

    /** 应用打开时回填上次同步到的钥匙（若有）。 */
    private void prefillFromStore() {
        java.util.Map<String, String> all = store.loadAll();
        if (all.isEmpty()) return;
        String lastMac = null;
        for (String mac : all.keySet()) lastMac = mac;
        String key = lastMac != null ? all.get(lastMac) : null;
        if (lastMac == null || key == null) return;
        etMac.setText(formatMac(lastMac));
        etActKey.setText(key);
        log("已回填上次同步的钥匙（MAC " + formatMac(lastMac) + "）");
    }

    /** 无冒号 MAC（12 hex）-> AA:BB:CC:DD:EE:FF。 */
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

    // ==================== 生命周期（预连接） ====================

    @Override
    protected void onStart() {
        super.onStart();
        client.setForeground(true);
        // 前台预连接：有合法 MAC + 蓝牙权限则后台建立连接（不进协议）
        if (hasBlePermissions()) {
            String mac = etMac.getText().toString().trim();
            if (mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
                client.preconnect(mac);
            }
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

        // 一体化面板：透明承载，区块间细分隔线
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.TRANSPARENT);
        content.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 头部 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(14));

        TextView title = tv("Open the door");
        title.setTextColor(C_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.01f);
        header.addView(title);

        TextView subtitle = tv("VOC 蓝牙门锁  ·  完全离线开锁");
        subtitle.setTextColor(C_MUTED);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(2), 0, 0);
        header.addView(subtitle);

        // 状态角标：● 已预连接 / ◌ 预连接中 / ○ 未连接
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(16), 0, 0);
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

        // ---- 英雄开锁按钮（圆形 + 自绘挂锁）----
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(22), 0, dp(18));

        heroBtn = new FrameLayout(this);
        int heroSize = dp(176);
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(heroSize, heroSize);
        heroLp.gravity = Gravity.CENTER_HORIZONTAL;
        heroBtn.setLayoutParams(heroLp);
        // 圆形渐变 + 光晕环
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

        glyph = new LockGlyphView(this);
        glyph.setColor(0xFF052A33);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(72), dp(72));
        glp.gravity = Gravity.CENTER;
        glyph.setLayoutParams(glp);
        heroBtn.addView(glyph);
        hero.addView(heroBtn);

        heroHint = tv("点击按钮开锁");
        heroHint.setTextColor(C_MUTED);
        heroHint.setTextSize(14);
        heroHint.setGravity(Gravity.CENTER);
        heroHint.setPadding(0, dp(14), 0, 0);
        hero.addView(heroHint);
        panel.addView(hero);
        panel.addView(divider());

        // ---- 门锁凭证 ----
        LinearLayout cardCred = section();
        cardCred.addView(label("门锁凭证"));
        etMac = field("门锁 BLE 地址  如 00:11:22:33:44:55", false);
        cardCred.addView(etMac);
        etActKey = field("activationKey（32 位 hex）", true);
        etActKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cardCred.addView(etActKey);

        cbUseDecrypt = toggle("改用「base64 密文 + voc_lock_secret_key」解密", false);
        cardCred.addView(cbUseDecrypt);
        etSecretKey = field("voc_lock_secret_key（32 字节 hex）", false);
        etSecretKey.setVisibility(View.GONE);
        cardCred.addView(etSecretKey);
        etBase64Key = field("服务器下发的 base64 密文 activationKey", false);
        etBase64Key.setVisibility(View.GONE);
        cardCred.addView(etBase64Key);
        cbUseDecrypt.setOnCheckedChangeListener((btn, checked) -> {
            etSecretKey.setVisibility(checked ? View.VISIBLE : View.GONE);
            etBase64Key.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        panel.addView(cardCred);
        panel.addView(divider());

        // ---- 钥匙同步 ----
        LinearLayout cardSync = section();
        cardSync.addView(label("钥匙同步"));
        btnSync = btn("◈  一键同步钥匙（补丁版天猫校园）", false);
        btnSync.setOnClickListener(v -> onSyncClick());
        cardSync.addView(btnSync);
        btnRoot = btn("⚡  root 抓取钥匙（已 root，无需补丁版）", false);
        btnRoot.setOnClickListener(v -> onRootGrabClick());
        LinearLayout.LayoutParams brlp = (LinearLayout.LayoutParams) btnRoot.getLayoutParams();
        brlp.topMargin = dp(8);
        cardSync.addView(btnRoot);
        cbAutoUnlock = toggle("同步到钥匙后自动开锁", true);
        cbAutoUnlock.setPadding(0, dp(6), 0, 0);
        cardSync.addView(cbAutoUnlock);
        panel.addView(cardSync);
        panel.addView(divider());

        // ---- 运行日志 ----
        LinearLayout cardLog = section();
        cardLog.addView(label("运行日志"));
        logScroll = new ScrollView(this);
        logScroll.setBackground(rounded(C_LOGBG, C_BORDER, dp(12)));
        logScroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvLog = new TextView(this);
        tvLog.setTextColor(C_LOGTEXT);
        tvLog.setTextSize(11.5f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        logScroll.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardLog.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        panel.addView(cardLog);
        panel.addView(divider());

        // ---- 帮助 ----
        TextView help = tv(
                "流程：装补丁版天猫校园 → 登录 → 门锁页点一次开锁（钥匙自动落盘）→ 回到本应用\n" +
                "点「一键同步钥匙」→ 自动开锁。此后完全离线、永久有效。\n" +
                "（已 root 亦可：装 LSPosed + 本模块，用「root 抓取钥匙」。）");
        help.setTextSize(11f);
        help.setTextColor(C_MUTED);
        help.setLineSpacing(dp(2), 1f);
        help.setPadding(dp(20), dp(12), dp(20), dp(4));
        panel.addView(help);
        panel.addView(divider());

        // ---- 页脚（版本 + 归属 + 源码）----
        LinearLayout footer = section();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        String version = "1.0";
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v != null && v.length() > 0) version = v;
        } catch (Exception ignored) { }
        TextView ver = tv("Open the door  v" + version);
        ver.setTextColor(C_MUTED);
        ver.setTextSize(12.5f);
        ver.setGravity(Gravity.CENTER);
        footer.addView(ver);
        TextView attr = tv("协议复刻自 lmc2007/TMallKey（MIT License）");
        attr.setTextColor(C_MUTED);
        attr.setTextSize(11f);
        attr.setGravity(Gravity.CENTER);
        attr.setPadding(0, dp(4), 0, 0);
        footer.addView(attr);
        TextView gh = tv("github.com/ababc0324/OpenTheDoor");
        gh.setTextColor(C_ACCENT);
        gh.setTextSize(12);
        gh.setTypeface(Typeface.DEFAULT_BOLD);
        gh.setGravity(Gravity.CENTER);
        gh.setPadding(0, dp(8), 0, 0);
        gh.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/ababc0324/OpenTheDoor")));
            } catch (Exception ignored) { }
        });
        footer.addView(gh);
        panel.addView(footer);

        setContentView(root);
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

    // ==================== 一键同步 ====================

    private void onSyncClick() {
        if (running) { toast("进行中，请等待"); return; }
        heroHint("同步钥匙中…");
        log("正在从补丁版天猫校园同步钥匙 ...");
        KeySyncHelper.pullAsync(new KeySyncHelper.Callback() {
            @Override
            public void onResult(final java.util.List<String[]> locks) {
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
                    for (String[] lk : locks) {
                        log("  MAC " + formatMac(lk[0]) + "  activationKey=" + lk[1]);
                    }
                    toast("已同步 " + locks.size() + " 把锁的钥匙");
                    heroHint("已同步 " + locks.size() + " 把锁");
                    if (cbAutoUnlock.isChecked()) {
                        startUnlock(formatMac(last[0]), last[1]);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(() -> {
                    log("同步失败: " + message);
                    toast("同步失败");
                    heroHint("同步失败");
                });
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
                    java.util.List<String[]> locks = new java.util.ArrayList<>();
                    for (String l : text.split("\n")) {
                        int tab = l.indexOf('\t');
                        if (tab > 0 && tab < l.length() - 1) {
                            String mac = l.substring(0, tab).trim();
                            String key = l.substring(tab + 1).trim();
                            if (mac.length() > 0 && key.length() > 0) locks.add(new String[]{mac, key});
                        }
                    }
                    if (locks.isEmpty()) {
                        log("root 文件存在但未解析到钥匙");
                        toast("未解析到钥匙");
                        heroHint("未解析到钥匙");
                        return;
                    }
                    for (String[] lk : locks) store.save(lk[0], lk[1]);
                    String[] last = locks.get(locks.size() - 1);
                    etMac.setText(formatMac(last[0]));
                    etActKey.setText(last[1]);
                    log("root 抓取成功，共 " + locks.size() + " 把锁：");
                    for (String[] lk : locks) {
                        log("  MAC " + formatMac(lk[0]) + "  activationKey=" + lk[1]);
                    }
                    toast("已抓取 " + locks.size() + " 把锁的钥匙");
                    heroHint("已抓取 " + locks.size() + " 把锁");
                    if (cbAutoUnlock.isChecked()) startUnlock(formatMac(last[0]), last[1]);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    log("root 抓取异常: " + msg);
                    toast("root 抓取失败");
                    heroHint("root 抓取失败");
                });
            }
        }).start();
    }

    // ==================== 开锁流程 ====================

    private void onUnlockClick() {
        if (running) { toast("进行中，请等待"); return; }
        String mac = etMac.getText().toString().trim();
        if (!mac.matches("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) {
            toast("MAC 地址格式错误");
            return;
        }

        String activationKey;
        if (cbUseDecrypt.isChecked()) {
            String base64 = etBase64Key.getText().toString().trim();
            String secret = etSecretKey.getText().toString().trim();
            if (base64.isEmpty() || secret.isEmpty()) {
                toast("请输入 base64 密文与 voc_lock_secret_key");
                return;
            }
            activationKey = AesUtils.decryptActivationKey(base64, secret);
            if (activationKey == null) {
                log("解密失败：检查 base64 密文与 voc_lock_secret_key");
                toast("解密失败");
                return;
            }
            log("解密得到 activationKey = " + activationKey);
        } else {
            String manual = etActKey.getText().toString().trim();
            activationKey = AesUtils.normalizeActivationKey(manual);
            if (activationKey == null) {
                log("activationKey 格式错误（需 32 位 hex）");
                toast("activationKey 格式错误（需 32 位 hex）");
                return;
            }
        }

        startUnlock(mac, activationKey);
    }

    private void startUnlock(String mac, String activationKey) {
        running = true;
        heroBtn.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        heroHint("开锁中…");
        glyph.setUnlocked(false);
        log("开始开锁 ... activationKey=" + activationKey);
        client.unlock(mac, activationKey, new DoorLockClient.Listener() {
            @Override
            public void onProgress(String message) { log(message); }
            @Override
            public void onSuccess(String message) {
                log(message);
                toast(message);
                heroHint("开锁成功 ✓");
                glyph.setUnlocked(true);
                running = false;
            }
            @Override
            public void onError(String message) {
                log("错误: " + message);
                toast(message);
                heroHint("开锁失败 ✗");
                running = false;
            }
        });
    }

    private void heroHint(String s) {
        heroHint.setText(s);
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
                if (r != PackageManager.PERMISSION_GRANTED) {
                    log("部分权限未授予，可能无法连接蓝牙");
                    break;
                }
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
