package com.oracle.dalvik;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import net.kdt.pojavlaunch.runtime.AuthYouLaunchOptions;

import java.nio.charset.StandardCharsets;

@Keep
public final class AuthYouLauncher {
    private static final int START_FAILED = 91;

    private AuthYouLauncher() {}

    public static int launch(
            Context applicationContext,
            String[] arguments,
            AuthYouLaunchOptions options,
            String profileId,
            String baseDirectory
    ) {
        long callbacks = VMLauncher.authYouHost();
        if (callbacks == 0) {
            return START_FAILED;
        }

        try {
            System.loadLibrary("authyou_bridge");
            byte[][] nativeArguments = new byte[arguments.length][];
            for (int i = 0; i < arguments.length; i++) {
                nativeArguments[i] = utf8(arguments[i]);
            }
            return launchNativeUtf8(
                    callbacks,
                    applicationContext,
                    nativeArguments,
                    utf8(options.serverId),
                    utf8(profileId),
                    utf8(options.host),
                    utf8(baseDirectory),
                    utf8(options.fileSetId)
            );
        } catch (UnsatisfiedLinkError failure) {
            Log.e("Amethyst", "Load AuthYou bridge or find its JNI entry point failed", failure);
            return START_FAILED;
        }
    }

    private static byte[] utf8(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static native int launchNativeUtf8(
            long callbacks,
            Context applicationContext,
            byte[][] args,
            byte[] serverId,
            byte[] userId,
            byte[] host,
            byte[] baseDirectory,
            byte[] fileSetId
    );
}
