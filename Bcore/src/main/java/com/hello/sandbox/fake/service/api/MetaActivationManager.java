package com.hello.sandbox.fake.service.api;

/**
 * Activation entry point for the sandbox core.
 *
 * <p>Upstream this posted a licence key to a remote server
 * ({@code LicenseVerifier}) and only flipped the sandbox on once the server
 * answered {@code "success"}. That HTTP client, the key and the remote check are
 * gone: this is Apache-2.0 source and the sandbox is meant to be usable by
 * anyone who builds it, so activation is now a local, unconditional no-op.</p>
 *
 * <p>It is deliberately still a class with a static entry point — callers keep
 * one obvious "set the SDK up" hook, and there is no key to pass around. The
 * core calls {@link #activateSdk()} itself from
 * {@code SandBoxCore.doAttachBaseContext}, so host apps need no activation code
 * at all.</p>
 */
public class MetaActivationManager {

    /** Flipped on by {@link #activateSdk()}; always true after core attach. */
    private static volatile boolean activated = true;

    /** Sets the SDK up. No key, no network, no gate. Idempotent. */
    public static void activateSdk() {
        activated = true;
    }

    /**
     * Always {@code true}. Kept because feature checks elsewhere still ask, and
     * an unconditional yes is exactly what a licence-free build should answer.
     */
    public static boolean isLicenseActivated() {
        return activated;
    }
}
