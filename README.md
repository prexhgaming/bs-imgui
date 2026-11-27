# bs-imgui

An Android sandbox **loader** built on [bcore / BlackBox](https://github.com/FBlackBox/BlackBox),
with its own UI and its own native payload.

The app virtualizes a target game inside a sandbox process and launches it, so the game runs
with the loader's hooks applied without touching the installed APK.

## Layout

```
.
├── app/                              # the loader app module (com.zyn.bs)
│   ├── libs/Fixed-Update4.1.aar      # bcore (BlackBox), unmodified
│   ├── proguard-rules.pro            # keeps bcore, obfuscates our own code
│   └── src/main/
│       ├── assets/a7f3c9.bin         # AES-256-GCM encrypted wallpaper
│       ├── java/com/zyn/bs/
│       │   ├── LauncherActivity.java # UI host + launch orchestration
│       │   ├── LaunchController.java # bcore install → launch flow
│       │   ├── GameConfig.java       # ← set the target package name here
│       │   ├── GameResolver.java     # package name → label + icon
│       │   ├── BoxApplication.java   # bcore init (attachBaseContext + doCreate)
│       │   ├── NativeBridge.java     # JNI hook point for libzyn-bs.so
│       │   ├── crypto/AssetCrypto.java
│       │   └── ui/                   # LauncherView, PulseDots, CircleIcon
│       ├── jni/                      # native payload (ndk-build)
│       └── res/                      # strings, colors, style, icon
├── .github/workflows/android-build.yml
└── build.gradle / settings.gradle
```

## Configure the target game

Edit **`app/src/main/java/com/zyn/bs/GameConfig.java`**:

```java
public static final String PACKAGE_NAME = "com.netease.newspike";
```

The title and icon are looked up from the installed package at runtime — nothing is hardcoded
in the UI. If the game isn't installed, the app shows **INSTALL GAME** and opens the Play Store
listing for that package.

## Build

Gradle drives `ndk-build` itself, so a single command builds the Java app *and* the native payload:

```bash
gradle assembleDebug assembleRelease
```

Requires JDK 17, Android SDK 34, and NDK `27.0.12077973` (r26+). The release APK is signed from
`signing.properties` + the `.jks` at the project root; without them it is built unsigned.

## Encrypted assets

The wallpaper ships as an AES-256-GCM blob (`assets/a7f3c9.bin`) instead of a readable image,
and is decrypted in memory on first use. The key is stored split-and-masked in `AssetCrypto`, and
the asset name is authenticated as GCM associated data — so renaming or editing the blob makes it
fail to decrypt rather than silently load altered content.

Regenerate:

```bash
python3 .polydev/encrypt_assets.py <plain-image> app/src/main/assets/<name>.bin <name>.bin
```

The script prints the `KEY_PARTS` / `KEY_MASKS` to paste into `AssetCrypto.java`.

## CI

`.github/workflows/android-build.yml` decodes the signing secrets, installs the NDK, builds with
Gradle, verifies the APK with `apksigner`, and reports to Telegram — the build log on failure, the
signed APK on success. Logs and APKs are kept as a build artifact.
