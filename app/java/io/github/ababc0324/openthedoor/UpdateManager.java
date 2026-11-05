package io.github.ababc0324.openthedoor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 应用内自更新 —— 直接从 GitHub Releases 拉取最新版并**同签名覆盖安装**。
 *
 * 流程：checkAsync() 查 releases/latest → 版本比对 → 有新版弹窗 →
 *      download()（流式 + SHA-256 校验，与 GitHub asset digest 比对）→
 *      install()（content:// 由 {@link ApkProvider} 提供，ACTION_VIEW 系统安装器）。
 *
 * 安全：HTTPS + asset digest(sha256) + 同签名（签名不同系统拒绝覆盖）三重保障。
 * 回调在后台线程执行，调用方自行切主线程。
 */
public final class UpdateManager {

    public interface Callback {
        /** @param hasUpdate 是否存在比当前更新的版本 */
        void onResult(boolean hasUpdate, String latestVersion, String changelog, String apkUrl, String sha256);
        void onError(String message);
    }

    public static final String REPO = "ababc0324/OpenTheDoor";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String APK_NAME = "doorunlock.apk";

    private UpdateManager() {}

    public static String currentVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "0";
        } catch (Exception e) { return "0"; }
    }

    /** 后台检查更新（回调在后台线程）。 */
    public static void checkAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "OpenTheDoor");
                int code = conn.getResponseCode();
                if (code != 200) {
                    cb.onError("更新服务不可用 (HTTP " + code + ")");
                    return;
                }
                String body = readAll(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String tag = json.optString("tag_name", "").replaceFirst("^[vV]", "");
                String changelog = json.optString("body", "");
                String url = null, sha = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (APK_NAME.equals(a.optString("name"))) {
                            url = a.optString("browser_download_url");
                            String d = a.optString("digest", "");
                            if (d.startsWith("sha256:")) sha = d.substring(7);
                            break;
                        }
                    }
                }
                boolean has = url != null && compareVersion(tag, currentVersion(ctx)) > 0;
                cb.onResult(has, tag, changelog, url, sha);
            } catch (Exception e) {
                cb.onError(e.getMessage() != null ? e.getMessage() : "网络错误");
            }
        }).start();
    }

    /** 下载 APK 到 cache 并校验 sha256；失败抛异常。 */
    public static File download(Context ctx, String url, String sha256) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "OpenTheDoor");
        int code = conn.getResponseCode();
        if (code != 200) throw new IllegalStateException("下载失败 HTTP " + code);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); md.update(buf, 0, n); }
        }
        if (sha256 != null && sha256.length() >= 64) {
            String actual = hex(md.digest());
            if (!actual.equalsIgnoreCase(sha256)) {
                out.delete();
                throw new IllegalStateException("SHA-256 校验失败");
            }
        }
        return out;
    }

    /** 触发系统安装（content:// 由 ApkProvider 提供；API 26+ 需允许未知来源）。 */
    public static void install(Activity act, File apk) throws Exception {
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
            throw new IllegalStateException("请在系统设置中允许「安装未知应用」后重试");
        }
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk");
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }

    /** 版本逐段数字比较：a>b → 1，a<b → -1，相等 → 0。 */
    public static int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseInt(pa[i]) : 0;
            int y = i < pb.length ? parseInt(pb[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
