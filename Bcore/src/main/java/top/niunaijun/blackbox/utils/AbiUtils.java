package top.niunaijun.blackbox.utils;

import java.io.File;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Decides whether an APK can run inside this box.
 *
 * <p>Upstream was hardcoded to ARM64-only:</p>
 *
 * <pre>return abiUtils.isEmptyAbi() || abiUtils.is64Bit();</pre>
 *
 * <p>which rejects every 32-bit APK outright. That is wrong for a 32-bit host:
 * the guest process inherits the <em>host's</em> ABI, so a 32-bit box can only
 * load 32-bit guest libs and a 64-bit box can only load 64-bit ones. With the
 * old check a 32-bit game failed installation with "The box does not support
 * 32-bit Application" even though the box itself was 32-bit — the game was
 * never cloned at all.</p>
 *
 * <p>The rule here is therefore: no native libs → always fine; otherwise the
 * APK must ship libs for the ABI this process actually runs as.</p>
 */
public class AbiUtils {

    private static final String ABI_ARM64 = "arm64-v8a";
    private static final String ABI_ARM32 = "armeabi-v7a";
    private static final String ABI_X86_64 = "x86_64";
    private static final String ABI_X86 = "x86";

    private final Set<String> mLibs = new HashSet<>();
    private static final Map<File, AbiUtils> sAbiUtilsMap = new HashMap<>();

    public static boolean isSupport(File apkFile) {
        AbiUtils abiUtils = sAbiUtilsMap.get(apkFile);
        if (abiUtils == null) {
            abiUtils = new AbiUtils(apkFile);
            sAbiUtilsMap.put(apkFile, abiUtils);
        }
        if (abiUtils.isEmptyAbi()) {
            // Pure-Java APK: runnable in either process size.
            return true;
        }
        // The guest runs in a process with this box's ABI, so only libs of that
        // size can ever be mapped.
        return BlackBoxCore.is64Bit() ? abiUtils.is64Bit() : abiUtils.is32Bit();
    }

    public AbiUtils(File apkFile) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkFile);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String name = zipEntry.getName();
                if (!name.startsWith("lib/")) {
                    continue;
                }
                if (name.startsWith("lib/" + ABI_ARM64 + "/")) {
                    mLibs.add(ABI_ARM64);
                } else if (name.startsWith("lib/" + ABI_ARM32 + "/")) {
                    mLibs.add(ABI_ARM32);
                } else if (name.startsWith("lib/" + ABI_X86_64 + "/")) {
                    mLibs.add(ABI_X86_64);
                } else if (name.startsWith("lib/" + ABI_X86 + "/")) {
                    mLibs.add(ABI_X86);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            CloseUtils.close(zipFile);
        }
    }

    public boolean is64Bit() {
        return mLibs.contains(ABI_ARM64) || mLibs.contains(ABI_X86_64);
    }

    public boolean is32Bit() {
        return mLibs.contains(ABI_ARM32) || mLibs.contains(ABI_X86);
    }

    public boolean isEmptyAbi() {
        return mLibs.isEmpty();
    }
}
