package io.github.ababc0324.openthedoor;

import android.content.Context;

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
 * 都会追加到 {@code <app外部存储>/crash.log}（无外部存储时回退到 filesDir）。
 * 不屏蔽崩溃：记录后仍交给系统默认处理器（应用照常退出），只用于事后定位。
 */
public final class CrashLogger {

    private static volatile boolean installed = false;

    private CrashLogger() {}

    public static synchronized void install(final Context ctx) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
                if (dir != null) {
                    File f = new File(dir, "crash.log");
                    StringBuilder sb = new StringBuilder();
                    sb.append("===== ")
                      .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                      .append(" =====\nthread: ").append(t.getName()).append('\n');
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    sb.append(sw.toString()).append('\n');
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write(sb.toString().getBytes("UTF-8"));
                    fos.close();
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
