package com.oracle.dalvik;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import net.kdt.pojavlaunch.runtime.AuthYouLaunchOptions;

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
            return launchNative(
                    callbacks,
                    applicationContext,
                    arguments,
                    options.serverId,
                    profileId,
                    options.host,
                    baseDirectory,
                    options.fileSetId
            );
        } catch (UnsatisfiedLinkError failure) {
            Log.e("Amethyst", "Load AuthYou bridge or find its JNI entry point failed", failure);
            return START_FAILED;
        }
    }

    private static native int launchNative(
            long callbacks,
            Context applicationContext,
            String[] args,
            String serverId,
            String userId,
            String host,
            String baseDirectory,
            String fileSetId
    );
}
