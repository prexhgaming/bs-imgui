package com.hello.sandbox.core.system;

import static com.hello.sandbox.core.env.BEnvironment.EMPTY_JAR;
import static com.hello.sandbox.core.env.BEnvironment.JUNIT_JAR;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.hello.sandbox.SandBoxCore;
import com.hello.sandbox.core.env.AppSystemEnv;
import com.hello.sandbox.core.env.BEnvironment;
import com.hello.sandbox.core.system.accounts.BAccountManagerService;
import com.hello.sandbox.core.system.am.BActivityManagerService;
import com.hello.sandbox.core.system.am.BJobManagerService;
import com.hello.sandbox.core.system.location.BLocationManagerService;
import com.hello.sandbox.core.system.notification.BNotificationManagerService;
import com.hello.sandbox.core.system.os.BStorageManagerService;
import com.hello.sandbox.core.system.pm.BPackageInstallerService;
import com.hello.sandbox.core.system.pm.BPackageManagerService;
import com.hello.sandbox.core.system.user.BUserHandle;
import com.hello.sandbox.core.system.user.BUserManagerService;
import com.hello.sandbox.entity.pm.InstallOption;
import com.hello.sandbox.utils.FileUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Created by Milk on 4/22/21. * ∧＿∧ (`･ω･∥ 丶　つ０ しーＪ 此处无Bug */
public class BlackBoxSystem {
  private static BlackBoxSystem sBlackBoxSystem;
  private final List<ISystemService> mServices = new ArrayList<>();
  private static final AtomicBoolean isStartup = new AtomicBoolean(false);

  public static BlackBoxSystem getSystem() {
    if (sBlackBoxSystem == null) {
      synchronized (BlackBoxSystem.class) {
        if (sBlackBoxSystem == null) {
          sBlackBoxSystem = new BlackBoxSystem();
        }
      }
    }
    return sBlackBoxSystem;
  }

  private static final Object STARTUP_LOCK = new Object();
  private static boolean sStartupRunning;

  /**
   * Brings the core up: all system services registered, package/user settings loaded.
   *
   * <p>Callers block until the first call finishes. The previous version set the flag
   * <i>before</i> doing the work, so a second thread - such as a warm-up thread racing the first
   * SDK call - was handed a half-initialised core and a null service instead of waiting for it.
   * Re-entrant calls from inside the work still return immediately, as before.
   */
  public void startup() {
    if (isStartup.get()) return;
    synchronized (STARTUP_LOCK) {
      if (isStartup.get() || sStartupRunning) return;
      sStartupRunning = true;
      try {
        doStartup();
        isStartup.set(true);
      } finally {
        sStartupRunning = false;
      }
    }
  }

  /** The actual start-up work; always call through {@link #startup()}. */
  private void doStartup() {
    BEnvironment.load();

    mServices.add(BPackageManagerService.get());
    mServices.add(BUserManagerService.get());
    mServices.add(BActivityManagerService.get());
    mServices.add(BJobManagerService.get());
    mServices.add(BStorageManagerService.get());
    mServices.add(BPackageInstallerService.get());
    mServices.add(BProcessManagerService.get());
    mServices.add(BAccountManagerService.get());
    mServices.add(BLocationManagerService.get());
    mServices.add(BNotificationManagerService.get());

    for (ISystemService service : mServices) {
      service.systemReady();
    }

    List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
    for (String preInstallPackage : preInstallPackages) {
      try {
        if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
          PackageInfo packageInfo =
              SandBoxCore.getPackageManager().getPackageInfo(preInstallPackage, 0);
          BPackageManagerService.get()
              .installPackageAsUser(
                  packageInfo.applicationInfo.sourceDir,
                  InstallOption.installBySystem(),
                  BUserHandle.USER_ALL);
        }
      } catch (PackageManager.NameNotFoundException ignored) {
      }
    }
    initJarEnv();
  }

  private void initJarEnv() {
    try {
      InputStream junit = SandBoxCore.getContext().getAssets().open("junit.jar");
      FileUtils.copyFile(junit, JUNIT_JAR);

      InputStream empty = SandBoxCore.getContext().getAssets().open("empty.jar");
      FileUtils.copyFile(empty, EMPTY_JAR);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
