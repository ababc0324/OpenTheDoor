package io.github.ababc0324.openthedoor.sync;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSPatch / Xposed 模块：把**天猫校园自己**开锁时传给 native 的 activationKey 抓出来，
 * 同步给独立开锁应用（io.github.ababc0324.openthedoor），实现「登录 → 点一次开锁 → 钥匙自动同步 → 离线永久开锁」。
 *
 * 原理（与 lmc2007/TMallKey 的 lspatch-module 一致）：
 *   天猫校园的 H5 在点「开锁」时经 WindVane 桥调用 native：
 *     VocLockHelperV2.unlock(Context, JSBParams, UnlockCallback)
 *   JSBParams 里就是**已解密**的 macAddress + activationKey（32 hex，每把锁固定，等价钥匙）。
 *   门锁是离线 BLE，activationKey 静态、不经过任何服务器校验，
 *   所以抓到一次即可永久离线开锁，无需服务器 API、无需 voc_lock_secret_key。
 *
 * 数据通道（实测踩坑后定稿，与 TMallKey 相同结论）：
 *   1. 本地回环 Socket 127.0.0.1:42742（带 token）—— 主通道，应用随时来拉；
 *   2. 广播 io.github.ababc0324.openthedoor.ACTION_KEY —— 兜底，应用在前台时可即时收到。
 *   （ContentProvider 在 Android 11+ 有包可见性限制，弃用。）
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.tmall.campus.and";
    private static final String RECEIVER_PKG = "io.github.ababc0324.openthedoor";
    private static final String ACTION_KEY = "io.github.ababc0324.openthedoor.ACTION_KEY";
    private static final String EXTRA_MAC = "mac";
    private static final String EXTRA_KEY = "activationKey";
    private static final int PORT = 42742;
    private static final String TOKEN = "doorunlock-sync-v1";

    /** mac -> activationKey，按 mac 去重（保留最新）。 */
    private static final Map<String, String> doorLocks = new ConcurrentHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) return;
        if (!TARGET_PKG.equals(lpparam.processName)) return; // 只挂主进程
        XposedBridge.log("[doorunlock-sync] loaded in " + lpparam.processName);
        loadPersistedIfNeeded();
        hookDoorUnlock(lpparam.classLoader);
        startServer();
    }

    // ---------------- 持久化（跨进程重启仍然可用：只需解锁一次） ----------------

    private static String filePath() {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) return null;
            return new java.io.File(ctx.getFilesDir(), "doorunlock_sync.txt").getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void loadPersistedIfNeeded() {
        if (!doorLocks.isEmpty()) return;
        String path = filePath();
        if (path == null) return;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) {
                    String mac = line.substring(0, tab).trim();
                    String key = line.substring(tab + 1).trim();
                    if (mac.length() > 0 && key.length() > 0) doorLocks.put(mac, key);
                }
            }
            r.close();
            if (!doorLocks.isEmpty()) {
                XposedBridge.log("[doorunlock-sync] restored " + doorLocks.size() + " doorlocks from disk");
            }
        } catch (Throwable t) {
            XposedBridge.log("[doorunlock-sync] load persisted failed: " + t);
        }
    }

    private static void persist() {
        String path = filePath();
        if (path == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : doorLocks.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            java.io.File f = new java.io.File(path);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            XposedBridge.log("[doorunlock-sync] persist failed: " + t);
        }
    }

    // ---------------- 门锁钥匙 hook ----------------

    private void hookDoorUnlock(ClassLoader cl) {
        int hooked = 0;
        // V1 / V2 / VocLockManager 的 unlock 签名一致：unlock(Context, JSBParams, UnlockCallback)。
        // 旧锁固件 / 新版 App 可能走不同实现，全部挂上，按类存在与否容错。
        for (String cls : new String[]{
                "com.tmall.campus.doorlock.VocLockHelperV2",
                "com.tmall.campus.doorlock.VocLockHelper",
                "com.tmall.campus.doorlock.session.VocLockManager",
        }) {
            try {
                XposedHelpers.findAndHookMethod(cls, cl, "unlock",
                        "android.content.Context",
                        "com.tmall.campus.doorlock.JSBParams",
                        "com.tmall.campus.doorlock.UnlockCallback",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object jsb = param.args.length > 1 ? param.args[1] : null;
                                    if (jsb == null) return;
                                    Object mac = XposedHelpers.callMethod(jsb, "getMacAddress");
                                    Object key = XposedHelpers.callMethod(jsb, "getActivationKey");
                                    if (mac == null || key == null) return;
                                    String macS = mac.toString().trim();
                                    String keyS = key.toString().trim();
                                    if (macS.isEmpty() || keyS.isEmpty()) return;
                                    doorLocks.put(macS, keyS);
                                    persist();
                                    XposedBridge.log("[doorunlock-sync] captured " + cls
                                            + " mac=" + macS + " keyLen=" + keyS.length());
                                    pushBroadcast(macS, keyS);
                                } catch (Throwable t) {
                                    XposedBridge.log("[doorunlock-sync] capture error: " + t);
                                }
                            }
                        });
                hooked++;
                XposedBridge.log("[doorunlock-sync] " + cls + ".unlock hooked");
            } catch (Throwable t) {
                XposedBridge.log("[doorunlock-sync] " + cls + " NOT hooked: " + t.getMessage());
            }
        }
        if (hooked == 0) {
            XposedBridge.log("[doorunlock-sync] WARN: no VocLockHelper found — "
                    + "天猫校园版本可能已变更，请反馈「我的-关于」里的版本号");
        }
    }

    private void pushBroadcast(String mac, String key) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) return;
            Intent i = new Intent(ACTION_KEY)
                    .setPackage(RECEIVER_PKG)
                    .putExtra(EXTRA_MAC, mac)
                    .putExtra(EXTRA_KEY, key)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log("[doorunlock-sync] broadcast error: " + t);
        }
    }

    // ---------------- 本地 Socket 服务（主通道） ----------------

    private void startServer() {
        Thread serverThread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                XposedBridge.log("[doorunlock-sync] socket on 127.0.0.1:" + PORT);
                while (true) {
                    Socket sock = server.accept();
                    try {
                        sock.setSoTimeout(3000);
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(sock.getInputStream(), "UTF-8"));
                        String line = r.readLine();
                        String out;
                        if (line != null && TOKEN.equals(line.trim())) {
                            out = buildEnvelope();
                            XposedBridge.log("[doorunlock-sync] served envelope (doorlocks=" + doorLocks.size() + ")");
                        } else {
                            out = "no token";
                        }
                        OutputStream os = sock.getOutputStream();
                        os.write(out.getBytes("UTF-8"));
                        os.flush();
                    } catch (Throwable ignore) {
                        // 单连接异常不杀服务
                    }
                    try { sock.close(); } catch (Throwable ignore) {}
                }
            } catch (Throwable t) {
                XposedBridge.log("[doorunlock-sync] server died: " + t);
            }
        });
        serverThread.setDaemon(true);
        serverThread.setName("doorunlock-sync-server");
        serverThread.start();
    }

    private String buildEnvelope() {
        loadPersistedIfNeeded(); // 进程重启后 doorLocks 可能为空，从磁盘补
        StringBuilder sb = new StringBuilder("{\"doorlocks\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : doorLocks.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"mac\":\"").append(jsonEscape(e.getKey()))
              .append("\",\"activationKey\":\"").append(jsonEscape(e.getValue())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
