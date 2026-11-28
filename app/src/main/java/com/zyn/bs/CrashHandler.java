package com.zyn.bs;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Last-chance crash reporter: writes an uncaught exception to external storage
 * <b>before</b> the process dies.
 *
 * <p>Installed from {@link BoxApplication}'s static initializer, which runs when
 * the Application class is loaded — before {@code attachBaseContext()}, before
 * the engine is touched and before anything else in the app executes. That is
 * deliberately earlier than the engine's own boot, so a crash inside
 * {@code BlackBoxCore.doAttachBaseContext()}/{@code doCreate()} is captured too.</p>
 *
 * <p>Destination, first one that accepts the write:</p>
 * <ol>
 *   <li>{@code Download/bs-crash-logs}</li>
 *   <li>{@code Documents/bs-crash-logs}</li>
 *   <li>the app-specific external dir (no permission needed)</li>
 *   <li>the app's internal {@code files} dir (always writable)</li>
 * </ol>
 *
 * <p>Public Download/Documents need {@code WRITE_EXTERNAL_STORAGE} to be granted;
 * this handler cannot ask for it, so it silently falls back when it is not. The
 * write is synchronous and fsync'ed, because the process is about to be killed.
 * It never swallows the crash: the previously installed handler is always
 * invoked afterwards so the app still dies the way the platform expects.</p>
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    /** Folder created inside Download / Documents (and inside the app dirs). */
    private static final String DIR_NAME = "bs-crash-logs";
    private static final String FILE_PREFIX = "crash-";
    private static final String FILE_SUFFIX = ".log";

    private static final CrashHandler INSTANCE = new CrashHandler();

    /** Set once a context is available; may stay null if the crash is very early. */
    private static volatile Context appContext;

    /** Handler that was installed before us; always called after logging. */
    private static volatile Thread.UncaughtExceptionHandler previous;

    private static volatile boolean installed;

    /** Guards against re-entry if the handler itself throws. */
    private static final AtomicBoolean writing = new AtomicBoolean(false);

    private CrashHandler() {
    }

    /**
     * Installs the handler with no context. Safe to call from a static
     * initializer: the log directory is resolved when a crash actually happens.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(INSTANCE);
        Log.i(TAG, "installed (no context yet)");
    }

    /**
     * Supplies the context used to resolve log directories. Call this as the very
     * first statement of {@code attachBaseContext()}, before {@code super}.
     */
    public static void attach(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        appContext = app != null ? app : context;
        install();
        Log.i(TAG, "context attached: " + appContext.getPackageName());
    }

    /**
     * Re-installs the handler if something else (the engine, a library) replaced
     * the default one after boot. Keeps our logging outermost so later crashes
     * are still captured, without losing the handler we displaced.
     */
    public static void reassert() {
        try {
            Thread.UncaughtExceptionHandler current =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (current == INSTANCE) {
                return;
            }
            previous = current;
            Thread.setDefaultUncaughtExceptionHandler(INSTANCE);
            Log.i(TAG, "re-installed over " + (current != null ? current.getClass().getName() : "null"));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            writeCrashLog(thread, error);
        } catch (Throwable ignored) {
            // The handler must never mask the original crash.
        }
        // Hand over to whoever was installed before us (normally the platform
        // handler, which kills the process). Never return normally.
        Thread.UncaughtExceptionHandler delegate = previous;
        if (delegate != null && delegate != this) {
            delegate.uncaughtException(thread, error);
            return;
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    // ------------------------------------------------------------------ write

    private void writeCrashLog(Thread thread, Throwable error) {
        if (!writing.compareAndSet(false, true)) {
            return; // already reporting; don't fight ourselves
        }
        byte[] payload = buildReport(thread, error).getBytes(StandardCharsets.UTF_8);
        String name = FILE_PREFIX
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date())
                + FILE_SUFFIX;

        File written = null;
        for (File dir : candidates()) {
            if (dir == null) {
                continue;
            }
            if (write(dir, name, payload)) {
                written = dir;
                break;
            }
        }
        if (written != null) {
            Log.e(TAG, "crash log written to " + new File(written, name).getAbsolutePath());
        } else {
            Log.e(TAG, "no writable location for the crash log");
        }
    }

    /** Destination order: public Download, public Documents, then app dirs. */
    private static File[] candidates() {
        File downloads = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DIR_NAME);
        File documents = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                DIR_NAME);

        Context context = appContext;
        if (context == null) {
            context = currentApplication();
        }
        File externalApp = null;
        File internal = null;
        if (context != null) {
            try {
                File base = context.getExternalFilesDir(null);
                if (base != null) {
                    externalApp = new File(base, DIR_NAME);
                }
            } catch (Throwable ignored) {
            }
            try {
                internal = new File(context.getFilesDir(), DIR_NAME);
            } catch (Throwable ignored) {
            }
        }
        return new File[] {downloads, documents, externalApp, internal};
    }

    /** Best effort: recover the Application without a stored context. */
    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object application = activityThread.getMethod("getApplication").invoke(thread);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean write(File dir, String name, byte[] payload) {
        FileOutputStream out = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            out = new FileOutputStream(new File(dir, name));
            out.write(payload);
            out.flush();
            out.getFD().sync();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------- report

    private static String buildReport(Thread thread, Throwable error) {
        StringBuilder sb = new StringBuilder(4096);
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(new Date());

        sb.append("=== bs crash report ===\n");
        sb.append("time      : ").append(now).append('\n');
        sb.append("process   : ").append(processName()).append('\n');
        sb.append("pid       : ").append(android.os.Process.myPid()).append('\n');
        sb.append("thread    : ").append(thread != null ? thread.getName() : "?").append('\n');
        appendPackage(sb);
        sb.append("device    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android   : ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("abis      : ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("java      : ").append(System.getProperty("java.vm.version")).append('\n');
        sb.append('\n');
        sb.append("--- stack trace ---\n");
        sb.append(stackTraceOf(error));
        sb.append('\n');
        sb.append("--- thread dump ---\n");
        sb.append(threadDump());
        return sb.toString();
    }

    private static void appendPackage(StringBuilder sb) {
        Context context = appContext;
        if (context == null) {
            context = currentApplication();
        }
        if (context == null) {
            sb.append("package   : (unknown, context not ready)\n");
            return;
        }
        sb.append("package   : ").append(context.getPackageName()).append('\n');
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("version   : ").append(info.versionName)
                    .append(" (").append(info.versionCode).append(")\n");
        } catch (Throwable ignored) {
        }
    }

    private static String stackTraceOf(Throwable error) {
        if (error == null) {
            return "(no throwable)\n";
        }
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        try {
            error.printStackTrace(printer);
            printer.flush();
        } catch (Throwable ignored) {
        }
        return writer.toString();
    }

    /** All live threads with their stacks: shows what the process was doing. */
    private static String threadDump() {
        StringBuilder sb = new StringBuilder();
        try {
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int count = Thread.enumerate(threads);
            for (int i = 0; i < count; i++) {
                Thread t = threads[i];
                if (t == null) {
                    continue;
                }
                sb.append('"').append(t.getName()).append("\" id=").append(t.getId())
                        .append(" state=").append(t.getState()).append('\n');
                for (StackTraceElement element : t.getStackTrace()) {
                    sb.append("    at ").append(element).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String processName() {
        try {
            byte[] buffer = new byte[256];
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            try {
                int read = in.read(buffer);
                if (read > 0) {
                    int end = 0;
                    while (end < read && buffer[end] != 0) {
                        end++;
                    }
                    return new String(buffer, 0, end, StandardCharsets.UTF_8);
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }
}
