package com.zyn.bs.crypto;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts the assets shipped in {@code assets/} at runtime.
 *
 * <p>Each asset is AES-256-GCM: {@code "BSAE" | version | nonce[12] |
 * ciphertext+tag[16]}, with the asset name bound in as GCM associated data.
 * That means a repackaged APK whose asset was swapped or edited fails
 * authentication and the loader refuses to draw it, instead of showing
 * tampered artwork.</p>
 *
 * <p><b>Honest scope:</b> this is tamper-evidence plus casual-obfuscation, not
 * secrecy from a determined reverse engineer — the key has to ship inside the
 * APK to be usable offline, so anyone willing to disassemble can recover it.
 * It stops "replace the file in the zip" edits, which is what it is for.</p>
 */
public final class AssetCrypto {

    private static final String TAG = "AssetCrypto";

    private static final byte[] MAGIC = {'B', 'S', 'A', 'E'};
    private static final int VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    /**
     * 256-bit key, split into 8-byte chunks and XOR-masked so the raw key never
     * appears as a contiguous literal in the dex.
     */
    private static final String[] KEY_PARTS = {
            "VBZafpA0goU=",
            "sAdyoKRYnJY=",
            "i8r3zm4O96Q=",
            "iLZtwxTE7Jw=",
    };
    private static final int[] KEY_MASKS = {0x5A, 0xC3, 0x7E, 0x91};

    private AssetCrypto() {
    }

    /** Thrown when an asset is missing, corrupt, or was tampered with. */
    public static class AssetException extends IOException {
        public AssetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static byte[] key() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        for (int i = 0; i < KEY_PARTS.length; i++) {
            byte[] masked = Base64.decode(KEY_PARTS[i], Base64.DEFAULT);
            int mask = KEY_MASKS[i % KEY_MASKS.length];
            for (byte b : masked) {
                out.write(b ^ mask);
            }
        }
        return out.toByteArray();
    }

    /** Reads and authenticates {@code name} from the assets folder. */
    public static byte[] read(Context context, String name) throws IOException {
        AssetManager assets = context.getAssets();
        byte[] blob;
        try (InputStream in = assets.open(name)) {
            blob = readAll(in);
        } catch (IOException e) {
            throw new AssetException("asset missing: " + name, e);
        }

        if (blob.length < MAGIC.length + 1 + NONCE_LEN) {
            throw new AssetException("asset truncated: " + name, null);
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) {
                throw new AssetException("bad asset header: " + name, null);
            }
        }
        if ((blob[MAGIC.length] & 0xFF) != VERSION) {
            throw new AssetException("unsupported asset version: " + name, null);
        }

        int nonceStart = MAGIC.length + 1;
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(blob, nonceStart, nonce, 0, NONCE_LEN);

        int bodyStart = nonceStart + NONCE_LEN;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(name));
            return cipher.doFinal(blob, bodyStart, blob.length - bodyStart);
        } catch (Exception e) {
            // AEADBadTagException lands here: wrong key or modified ciphertext.
            Log.w(TAG, "asset failed authentication: " + name, e);
            throw new AssetException("asset failed authentication: " + name, e);
        }
    }

    /**
     * Decrypts and downsamples an encrypted image asset to roughly
     * {@code reqWidth x reqHeight}. Returns {@code null} if the asset cannot be
     * authenticated or decoded, so callers can fall back gracefully.
     */
    public static Bitmap decodeBitmap(Context context, String name, int reqWidth, int reqHeight) {
        try {
            byte[] data = read(context, name);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (IOException e) {
            Log.w(TAG, "could not load encrypted asset: " + name, e);
            return null;
        }
    }

    private static byte[] aad(String name) {
        byte[] suffix = name.getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[MAGIC.length + 1 + suffix.length];
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.length);
        aad[MAGIC.length] = (byte) VERSION;
        System.arraycopy(suffix, 0, aad, MAGIC.length + 1, suffix.length);
        return aad;
    }

    private static int sampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sample = 1;
        if (reqWidth <= 0 || reqHeight <= 0) {
            return sample;
        }
        while ((width / (sample * 2)) >= reqWidth && (height / (sample * 2)) >= reqHeight) {
            sample *= 2;
        }
        return sample;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
