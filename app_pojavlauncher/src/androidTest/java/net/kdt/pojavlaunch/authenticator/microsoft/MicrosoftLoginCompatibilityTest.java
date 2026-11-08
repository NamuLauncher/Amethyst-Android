package net.kdt.pojavlaunch.authenticator.microsoft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.kdt.pojavlaunch.runtime.AccountAuthenticationException;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class MicrosoftLoginCompatibilityTest {
    @Test
    public void legacyWrapperStillDeliversSafeFailureOnTheMainThread() throws Exception {
        MicrosoftBackgroundLogin login = new MicrosoftBackgroundLogin(true, "test-private-token", url -> {
            throw new IOException("test-private-response");
        });
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean onMain = new AtomicBoolean();
        AtomicReference<Throwable> result = new AtomicReference<>();
        login.performLogin(null, account -> {
            ready.set(true);
            completed.countDown();
        }, failure -> {
            onMain.set(Looper.myLooper() == Looper.getMainLooper());
            result.set(failure);
            completed.countDown();
        });

        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertFalse(ready.get());
        assertTrue(onMain.get());
        assertTrue(result.get() instanceof AccountAuthenticationException);
        AccountAuthenticationException failure = (AccountAuthenticationException) result.get();
        assertEquals(AccountAuthenticationException.Reason.NETWORK, failure.reason);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("test-private"));
    }
}
