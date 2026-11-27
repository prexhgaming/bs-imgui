package com.zyn.bs;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * The bcore launch sequence, lifted from the reference loader's
 * {@code InstallActivity} and stripped of its dialogs so the new UI can drive
 * it. The order of operations is intentionally identical:
 *
 * <ol>
 *   <li>already inside the sandbox → launch</li>
 *   <li>installed on the device → install into bcore, copy OBB, then launch</li>
 *   <li>not installed anywhere → error</li>
 * </ol>
 *
 * <p>Launch itself waits 3 s (the reference showed a loading animation for that
 * long) and the payload hook fires 10 s later, mirroring
 * {@code FloatService.enableESP}.</p>
 */
public class LaunchController {

    private static final String TAG = "LaunchController";

    /** Delay before {@code launchApk}, matching the reference's dialog. */
    private static final long LAUNCH_DELAY_MS = 3000L;
    /** Delay before the native payload hook, matching the reference's ESP start. */
    private static final long PAYLOAD_DELAY_MS = 10000L;

    public enum State {
        IDLE,
        INSTALLING,
        LAUNCHING,
        RUNNING,
        ERROR
    }

    public interface Callback {
        void onStateChanged(State state, String message);
    }

    private final Context context;
    private final String packageName;
    private final BlackBoxCore blackBoxCore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback callback;

    private volatile boolean busy;

    public LaunchController(Context context, String packageName, Callback callback) {
        this.context = context.getApplicationContext();
        this.packageName = packageName;
        this.callback = callback;
        this.blackBoxCore = BlackBoxCore.get();
        this.blackBoxCore.doCreate();
    }

    public boolean isBusy() {
        return busy;
    }

    /** Entry point — safe to call repeatedly; ignored while a launch is running. */
    public void start() {
        if (busy) {
            return;
        }
        busy = true;
        emit(State.IDLE, null);

        new Thread(this::resolveAndRun, "launch-resolve").start();
    }

    private void resolveAndRun() {
        final boolean inSandbox;
        final boolean onDevice;
        try {
            inSandbox = blackBoxCore.isInstalled(packageName, 0);
            onDevice = isInstalledOnDevice();
        } catch (Throwable t) {
            Log.e(TAG, "state resolution failed", t);
            fail("Could not read the sandbox state.");
            return;
        }

        if (inSandbox) {
            post(this::launchGame);
        } else if (onDevice) {
            post(this::installGame);
        } else {
            fail(context.getString(R.string.error_not_installed));
        }
    }

    private boolean isInstalledOnDevice() {
        try {
            context.getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Step 2 — install into bcore, copy OBB, then launch. */
    private void installGame() {
        emit(State.INSTALLING, context.getString(R.string.status_installing));
        mainHandler.postDelayed(() -> {
            try {
                InstallResult result = blackBoxCore.installPackageAsUser(packageName, 0);
                if (result == null || !result.success) {
                    fail(result != null && result.msg != null
                            ? result.msg
                            : context.getString(R.string.error_install_failed));
                    return;
                }
            } catch (Throwable t) {
                Log.e(TAG, "installPackageAsUser failed", t);
                fail(context.getString(R.string.error_install_failed));
                return;
            }
            copyObbIfNeeded();
            launchGame();
        }, 2000L);
    }

    /** Step 1/3 — launch the sandboxed game, then arm the payload. */
    private void launchGame() {
        emit(State.LAUNCHING, context.getString(R.string.status_launching));

        mainHandler.postDelayed(() -> {
            try {
                blackBoxCore.launchApk(packageName, 0);
            } catch (Throwable t) {
                Log.e(TAG, "launchApk failed", t);
                fail(context.getString(R.string.error_install_failed));
                return;
            }
            emit(State.RUNNING, context.getString(R.string.status_running));
            mainHandler.postDelayed(NativeBridge::load, PAYLOAD_DELAY_MS);
        }, LAUNCH_DELAY_MS);
    }

    /**
     * Copies {@code main.<n>.<pkg>.obb} from shared storage into bcore's
     * {@code SdCard} mirror — same paths and same "only if missing" rule as the
     * reference implementation.
     */
    private void copyObbIfNeeded() {
        final File sandboxObb = new File("/storage/emulated/0/SdCard/Android/obb/" + packageName);
        if (hasObb(sandboxObb)) {
            return;
        }
        File deviceObb = new File("/storage/emulated/0/Android/obb/" + packageName);
        File[] sources = deviceObb.listFiles(
                (dir, name) -> name.matches("main\\.\\d+\\." + packageName + "\\.obb"));
        if (sources == null || sources.length == 0) {
            return;
        }
        File source = sources[0];
        File destination = new File(sandboxObb, source.getName());
        try {
            copyFile(source, destination);
        } catch (Exception e) {
            Log.w(TAG, "OBB copy failed", e);
        }
    }

    private boolean hasObb(File folder) {
        File[] files = folder.listFiles(
                (dir, name) -> name.matches("main\\.\\d+\\." + packageName + "\\.obb"));
        return files != null && files.length > 0;
    }

    private static void copyFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private void fail(String message) {
        busy = false;
        emit(State.ERROR, message);
    }

    private void emit(State state, String message) {
        busy = state == State.RUNNING;
        if (callback != null) {
            callback.onStateChanged(state, message);
        }
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /** Releases pending callbacks; call from {@code onDestroy}. */
    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        busy = false;
    }
}
