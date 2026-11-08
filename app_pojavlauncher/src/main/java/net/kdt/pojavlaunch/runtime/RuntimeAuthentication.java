package net.kdt.pojavlaunch.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

final class RuntimeAuthentication {
    interface Authenticator {
        MinecraftAccount authenticate(boolean refresh, String credential) throws Exception;
    }

    private final Context context;
    private final Executor workers;
    private final Executor callbacks;
    private final Authenticator authenticator;
    private final LongSupplier clock;

    RuntimeAuthentication(
            Context context,
            Executor workers,
            Executor callbacks,
            Authenticator authenticator,
            LongSupplier clock
    ) {
        this.context = context;
        this.workers = workers;
        this.callbacks = callbacks;
        this.authenticator = authenticator;
        this.clock = clock;
    }

    static RuntimeAuthentication create(Context context) {
        Handler main = new Handler(Looper.getMainLooper());
        return new RuntimeAuthentication(
                context.getApplicationContext(),
                PojavApplication.sExecutorService,
                action -> main.post(action),
                (refresh, credential) -> new MicrosoftBackgroundLogin(refresh, credential)
                        .authenticate(null),
                System::currentTimeMillis
        );
    }

    AmethystRuntime.AuthenticationRequest refresh(AmethystRuntime.AuthenticationListener listener) {
        AuthenticationTask task = new AuthenticationTask(callbacks, listener);
        task.start(workers, () -> {
            Tools.initEarlyConstants(context);
            String id = PojavProfile.getCurrentProfileName(context);
            MinecraftAccount previous = RuntimeAccountStorage.read(context, id);
            if (!previous.isMicrosoft) {
                throw new AccountAuthenticationException(AccountAuthenticationException.Reason.REAUTHENTICATION_REQUIRED);
            }
            if (previous.isDemo()) {
                throw new AccountAuthenticationException(AccountAuthenticationException.Reason.NO_GAME);
            }
            long now = clock.getAsLong();
            if (hasToken(previous.accessToken) && previous.expiresAt > now
                    && previous.expiresAt - now > 60_000) {
                return;
            }
            if (!hasToken(previous.msaRefreshToken)) {
                throw new AccountAuthenticationException(AccountAuthenticationException.Reason.REAUTHENTICATION_REQUIRED);
            }
            MinecraftAccount updated = authenticator.authenticate(true, previous.msaRefreshToken);
            task.commit(() -> RuntimeAccountStorage.refresh(context, id, previous, updated));
        });
        return task;
    }

    AmethystRuntime.AuthenticationRequest login(String code, AmethystRuntime.AuthenticationListener listener) {
        AuthenticationTask task = new AuthenticationTask(callbacks, listener);
        task.start(workers, () -> {
            if (code == null || code.trim().isEmpty()) {
                throw new AccountAuthenticationException(AccountAuthenticationException.Reason.INVALID_RESPONSE);
            }
            MinecraftAccount account = authenticator.authenticate(false, code);
            task.commit(() -> RuntimeAccountStorage.login(context, account));
        });
        return task;
    }

    private static boolean hasToken(String token) {
        return token != null && !token.isEmpty() && !token.equals("0");
    }
}
