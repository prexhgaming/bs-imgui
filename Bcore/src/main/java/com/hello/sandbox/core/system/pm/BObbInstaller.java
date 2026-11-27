package com.hello.sandbox.core.system.pm;

import android.os.Environment;
import android.text.TextUtils;
import com.hello.sandbox.core.env.BEnvironment;
import com.hello.sandbox.utils.FileUtils;
import com.hello.sandbox.utils.Slog;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a package's OBB expansion files into the sandbox during install.
 *
 * <p>A guest reads {@code /storage/emulated/<userId>/Android/obb/<pkg>/main.<n>.<pkg>.obb}, which
 * the core redirects onto {@link BEnvironment#getExternalObbDir(String, int)}. The APK install
 * itself never touches OBB files, so without this step a game with an expansion file starts, fails
 * to find its data and usually quits — which is why the host app used to copy them by hand after
 * every install.</p>
 *
 * <p>Doing it here means the rule lives in one place: install a package and its OBBs come along.
 * Nothing is overwritten — an existing file is left alone, so a partially downloaded or
 * user-supplied OBB is not clobbered by a reinstall.</p>
 */
public class BObbInstaller {

  private static final String TAG = "BObbInstaller";

  /** OBBs are commonly 1–2 GB, so copy in large chunks instead of FileUtils' 1 KB buffer. */
  private static final int BUFFER_SIZE = 1 << 20; // 1 MiB

  /**
   * Matches {@code main.1234.com.example.game.obb}, its {@code patch.…} counterpart, and the
   * older numbered {@code <n>.….obb} form.
   */
  private static String obbPattern(String packageName) {
    return "^(main|patch)\\.[0-9]+\\." + packageName + "\\.obb$";
  }

  /** The real, shared-storage OBB folder the device's own install would use. */
  private static File deviceObbDir(String packageName) {
    return new File(new File(Environment.getExternalStorageDirectory(), "Android/obb"),
        packageName);
  }

  /**
   * Copies every OBB belonging to {@code packageName} into the sandbox.
   *
   * <p>Never throws: a missing or unreadable expansion file must not fail an install. Callers get
   * the number of files actually copied.</p>
   *
   * @return how many files were copied (0 when there was nothing to do)
   */
  public static int install(String packageName, int userId) {
    if (TextUtils.isEmpty(packageName)) return 0;

    final File source = deviceObbDir(packageName);
    if (!source.isDirectory()) return 0;

    File[] obbs = source.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.matches(obbPattern(packageName));
      }
    });
    if (obbs == null || obbs.length == 0) return 0;

    File target = BEnvironment.getExternalObbDir(packageName, userId);
    FileUtils.mkdirs(target);

    int copied = 0;
    for (File obb : obbs) {
      File destination = new File(target, obb.getName());
      if (destination.exists()) continue;
      try {
        copy(obb, destination);
        copied++;
        Slog.d(TAG, "copied " + obb.getName() + " -> " + destination.getAbsolutePath());
      } catch (Throwable t) {
        Slog.w(TAG, "could not copy " + obb.getAbsolutePath(), t);
        // Do not leave a truncated file behind: the guest would treat it as valid.
        if (destination.exists()) FileUtils.deleteDir(destination);
      }
    }
    return copied;
  }

  /** Removes the sandboxed OBB folder for a package (used on uninstall). */
  public static void uninstall(String packageName, int userId) {
    if (TextUtils.isEmpty(packageName)) return;
    FileUtils.deleteDir(BEnvironment.getExternalObbDir(packageName, userId));
  }

  private static void copy(File source, File destination) throws Exception {
    File parent = destination.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IllegalStateException("cannot create " + parent);
    }
    try (InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(destination)) {
      byte[] buffer = new byte[BUFFER_SIZE];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
      out.flush();
    }
  }
}
