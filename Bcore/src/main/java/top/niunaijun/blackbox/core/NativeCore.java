package top.niunaijun.blackbox.core;


import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;
import android.content.Context;
import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;


public class NativeCore {
    public static final String TAG = "NativeCore";
    private static boolean isInjected = true; //false will work
    public static String libtarget = "libbgmi.so";

    static {
        System.loadLibrary("blackbox");

        if (!isInjected) {
            Context context = BlackBoxCore.getContext();
            File libFile = new File(context.getFilesDir(), "loader/" + libtarget);
            if (libFile.exists()) {
                Log.i(TAG, "Loading " + libtarget + " from loader directory");
                try {
                    System.load(libFile.getAbsolutePath());
                    isInjected = true;
                    Log.i(TAG, libtarget + " successfully loaded from: " + libFile.getAbsolutePath());
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "Failed to load " + libtarget + ": " + e.getMessage());
                }
            } else {
                Log.e(TAG, libtarget + " not found at: " + libFile.getAbsolutePath());
            }
        } else {
            Log.i(TAG, "Library already injected");
        }
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    @Keep
    public static int getCallingUid(int origCallingUid) {
        try {
            // System UIDs should remain unchanged
            if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
                return origCallingUid;
            // Non-user app UIDs should remain unchanged
            if (origCallingUid > Process.LAST_APPLICATION_UID)
                return origCallingUid;

            if (origCallingUid == BlackBoxCore.getHostUid()) {
                //microG
                String appPackageName = BlackBoxCore.getAppPackageName();
                if (appPackageName != null && appPackageName.equals("com.google.android.gms")){
                    //return Process.ROOT_UID;
                }
                //webview WV.qE Process.myUid()函数没有做hook所以只能特殊处理
                //if (Binder.getCallingUid() == Process.myUid()) {
                //throw new SecurityException("recordMetrics() may only be called by non-embedded WebView processes");
                if (appPackageName != null && appPackageName.equals("com.google.android.webview")){
                    return Process.myUid();
                }

                // Enhanced UID handling for sandboxed environments
                try {
                    int callingBUid = BlackBoxCore.getCallingBUid();
                    if (callingBUid > 0 && callingBUid < Process.LAST_APPLICATION_UID) {
                        return callingBUid;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting calling BUid: " + e.getMessage());
                }

                // Special handling for system settings access to prevent UID mismatch
                try {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    for (StackTraceElement element : stackTrace) {
                        String className = element.getClassName();
                        String methodName = element.getMethodName();

                        // Check if this is a system settings access that might cause UID issues
                        if ((className.contains("Settings") || className.contains("FeatureFlag")) &&
                                (methodName.contains("getString") || methodName.contains("getInt") ||
                                        methodName.contains("getLong") || methodName.contains("getFloat"))) {

                            Log.d(TAG, "System settings access detected, using system UID to prevent mismatch");
                            // Return a system UID to prevent SecurityException
                            return Process.SYSTEM_UID; // UID 1000
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error analyzing stack trace for UID resolution: " + e.getMessage());
                }

                // Fallback to host UID if calling BUid is invalid
                return BlackBoxCore.getHostUid();
            }
            return origCallingUid;
        } catch (Exception e) {
            Log.e(TAG, "Error in getCallingUid: " + e.getMessage());
            // Return a safe fallback UID
            return Process.myUid();
        }
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

}
