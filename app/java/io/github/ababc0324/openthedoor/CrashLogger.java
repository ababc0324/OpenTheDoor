package io.github.ababc0324.openthedoor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃日志器 —— 把未捕获异常堆栈写入本机文件，供无 logcat 时诊断闪退。
 *
 * 在 MainActivity.onCreate 首行调用 {@link #install(Context)}；之后任何未捕获异常
 * 都会追加到 {@code <app外部存储>/crash.log}（无外部存储回退 filesDir）。
 * 不屏蔽崩溃：记录后仍交给系统默认处理器（应用照常退出），只用于事后定位。
 *
 * 存储优化：
 *   - 头部附**设备型号 / Android 版本 / 应用版本**，跨机型/版本可对比；
 *   - **128KB 上限轮转**：超过则 crash.log → crash.log.1（覆盖旧），避免无限增长；
 *   - 外部 App 专属目录优先（文件管理器/adb 可取），失败回退内部 filesDir；
 *   - 用 {@code getApplicationContext()}（不持有 Activity，防泄漏）。
 */
public final class CrashLogger {

    private static final int MAX_BYTES = 128 * 1024;   // 128KB 上限（超出轮转）
    private static volatile boolean installed = false;

    private CrashLogger() {}

    public static synchronized void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeLog(app, t, e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void writeLog(Context ctx, Thread t, Throwable e) {
        try {
            File f = pickFile(ctx);
            if (f == null) return;
            rotateIfNeeded(f);
            String body = build(ctx, t, e);
            FileOutputStream fos = new FileOutputStream(f, true);
            try { fos.write(body.getBytes("UTF-8")); } finally { fos.close(); }
        } catch (Throwable ignored) { }
    }

    /** 外部 App 专属目录优先（用户可访问），失败回退内部 filesDir。 */
    private static File pickFile(Context ctx) {
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) return new File(ext, "crash.log");
        } catch (Throwable ignored) { }
        try {
            File in = ctx.getFilesDir();
            if (in != null) return new File(in, "crash.log");
        } catch (Throwable ignored) { }
        return null;
    }

    /** 超过上限：crash.log → crash.log.1（覆盖旧的），再继续写新。 */
    private static void rotateIfNeeded(File f) {
        if (f.length() < MAX_BYTES) return;
        File old = new File(f.getParentFile(), "crash.log.1");
        if (old.exists()) old.delete();
        if (f.exists()) f.renameTo(old);
    }

    private static String build(Context ctx, Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
          .append(" =====\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append("  android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            sb.append("app: ").append(ctx.getPackageName()).append(" v")
              .append(pi.versionName).append(" (").append(vc).append(")\n");
        } catch (Throwable ignored) { }
        sb.append("thread: ").append(t.getName()).append('\n');
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        sb.append(sw.toString()).append('\n');
        return sb.toString();
    }
}
