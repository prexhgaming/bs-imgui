package com.zyn.bs;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent log for a device we cannot attach a debugger to.
 *
 * <p>{@link android.util.Log} only reaches logcat: it is lost as soon as the
 * cable is unplugged, and a failed sandbox launch is exactly the kind of bug
 * that is reported to us later as a screenshot of a toast. Everything written
 * here is also mirrored to logcat, and additionally appended to</p>
 *
 * <pre>files/logs/loader.log</pre>
 *
 * <p>inside the app's private storage. On a debug build (what CI ships) that
 * file can be pulled without root:</p>
 *
 * <pre>adb exec-out run-as com.zyn.bs cat files/logs/loader.log</pre>
 *
 * <p>The file is rotated once it passes {@link #MAX_BYTES}, so it cannot grow
 * without bound on a long-lived install. Every method is failure-tolerant: a
 * logging problem must never be the reason a launch fails.</p>
 */
public final class CrashLog {

    private static final String TAG = "CrashLog";

    private static final String DIR_NAME = "logs";
    private static final String FILE_NAME = "loader.log";

    /** Rotate to {@code loader.log.1} beyond this size. */
    private static final long MAX_BYTES = 512 * 1024;

    private static final Object LOCK = new Object();

    private static volatile File sFile;

    private CrashLog() {
    }

    /**
     * Resolves the log file and installs an uncaught-exception handler that
     * records the stack trace before the process dies.
     */
    public static void init(Context context) {
        try {
            File dir = new File(context.getFilesDir(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "could not create " + dir);
            }
            sFile = new File(dir, FILE_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "log file unavailable, logcat only", t);
            return;
        }

        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                append("FATAL", "uncaught in " + thread.getName(), throwable);
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /** Logs an informational line to logcat and the file. */
    public static void i(String tag, String message) {
        Log.i(tag, message);
        append("I/" + tag, message, null);
    }

    /** Logs a warning to logcat and the file. */
    public static void w(String tag, String message, Throwable t) {
        Log.w(tag, message, t);
        append("W/" + tag, message, t);
    }

    /** Logs an error to logcat and the file. */
    public static void e(String tag, String message, Throwable t) {
        Log.e(tag, message, t);
        append("E/" + tag, message, t);
    }

    /**
     * Renders a throwable for display. {@code getMessage()} is often null (a
     * bare {@code NullPointerException}, for instance), which is how a toast
     * ends up saying just "null" and hiding the real cause — the class name is
     * always shown instead.
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null || message.isEmpty() ? name : name + ": " + message;
    }

    /** Appends one record, rotating the file first when it is too large. */
    public static void append(String level, String message, Throwable t) {
        File file = sFile;
        if (file == null) {
            return;
        }
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());

        StringBuilder record = new StringBuilder(160);
        record.append(stamp).append(' ').append(level).append(' ')
                .append(message == null ? "" : message).append('\n');
        if (t != null) {
            StringWriter stack = new StringWriter();
            t.printStackTrace(new PrintWriter(stack));
            record.append(stack);
        }

        synchronized (LOCK) {
            rotateIfNeeded(file);
            FileWriter writer = null;
            try {
                writer = new FileWriter(file, true);
                writer.write(record.toString());
            } catch (IOException e) {
                Log.w(TAG, "could not write " + file, e);
            } finally {
                closeQuietly(writer);
            }
        }
    }

    private static void rotateIfNeeded(File file) {
        try {
            if (!file.exists() || file.length() < MAX_BYTES) {
                return;
            }
            File old = new File(file.getParentFile(), FILE_NAME + ".1");
            if (old.exists() && !old.delete()) {
                return;
            }
            if (!file.renameTo(old)) {
                Log.w(TAG, "could not rotate " + file);
            }
        } catch (Throwable t) {
            Log.w(TAG, "rotation failed", t);
        }
    }

    private static void closeQuietly(FileWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the record is already written or lost.
        }
    }
}
