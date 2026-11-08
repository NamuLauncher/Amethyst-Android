package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class OAuthAccountResultTest {
    private static final Uri AUTHORIZATION = Uri.parse(
            "ms-xal-00000000402b5328://auth/?code=test-private-code"
    );
    private final Context context = InstrumentationRegistry.getInstrumentation()
            .getTargetContext();

    @Test
    public void duplicateRedirectStartsOnceAndSuccessContainsNoCredentials() throws Exception {
        onMain(() -> {
            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel model = new AccountLoginViewModel(starter);
            Context wrapped = new ContextWrapper(context);

            model.authorize(wrapped, AUTHORIZATION);
            model.authorize(wrapped, AUTHORIZATION);
            model.authorize(wrapped, Uri.parse("ms-xal-00000000402b5328://auth/"));

            assertEquals(1, starter.starts);
            assertSame(context.getApplicationContext(), starter.context);
            assertEquals(AccountLoginViewModel.Phase.AUTHENTICATING, model.current().phase);
            assertNoResult(model);
            starter.listener.onReady();
            starter.listener.onFailure(new IOException("test-private-response"));

            assertEquals(Activity.RESULT_OK, RuntimeAccountActivity.resultCode(model.current()));
            assertNull(RuntimeAccountActivity.resultIntent(model.current())
                    .getExtras());
        });
    }

    @Test
    public void acceptedBackWaitsForWorkerCancellationAndRejectsFurtherRedirects() throws Exception {
        onMain(() -> {
            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel model = new AccountLoginViewModel(starter);
            model.authorize(context, AUTHORIZATION);

            model.cancel();
            model.cancel();
            model.authorize(context, AUTHORIZATION);

            assertEquals(1, starter.starts);
            assertEquals(1, starter.cancellations);
            assertEquals(AccountLoginViewModel.Phase.CANCELLING, model.current().phase);
            assertNoResult(model);
            starter.listener.onCancelled();
            starter.listener.onReady();

            assertEquals(Activity.RESULT_CANCELED, RuntimeAccountActivity.resultCode(model.current()));
            assertNull(RuntimeAccountActivity.resultIntent(model.current())
                    .getExtras());
        });
    }

    @Test
    public void rejectedCancellationKeepsTheActualCommitResultAndOnlyReturnsAnAllowedReason() throws Exception {
        onMain(() -> {
            for (boolean success : new boolean[]{true, false}) {
                FakeStarter starter = new FakeStarter();
                starter.acceptCancellation = false;
                AccountLoginViewModel model = new AccountLoginViewModel(starter);
                model.authorize(context, AUTHORIZATION);
                model.cancel();

                assertEquals(AccountLoginViewModel.Phase.COMMITTING, model.current().phase);
                assertNoResult(model);
                if (success) {
                    starter.listener.onReady();
                    assertEquals(Activity.RESULT_OK, RuntimeAccountActivity.resultCode(model.current()));
                    assertNull(RuntimeAccountActivity.resultIntent(model.current())
                            .getExtras());
                } else {
                    starter.listener.onFailure(new AccountAuthenticationException(
                            AccountAuthenticationException.Reason.STORAGE
                    ));
                    assertFailure(model, AccountAuthenticationException.Reason.STORAGE);
                }
            }

            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel model = new AccountLoginViewModel(starter);
            model.authorize(context, AUTHORIZATION);
            starter.listener.onFailure(new IOException("test-private-response"));
            assertFailure(model, AccountAuthenticationException.Reason.NETWORK);
        });
    }

    @Test
    public void rotationRetainsOneRequestAndDeliversTerminalToTheNewOwner() throws Exception {
        onMain(() -> {
            ViewModelStore store = new ViewModelStore();
            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel.Factory factory = new AccountLoginViewModel.Factory(starter);
            Owner first = new Owner(store);
            AccountLoginViewModel original = new ViewModelProvider(first, factory)
                    .get(AccountLoginViewModel.class);
            List<AccountLoginViewModel.State> firstStates = new ArrayList<>();
            original.states()
                    .observe(first, firstStates::add);
            first.lifecycle.setCurrentState(Lifecycle.State.STARTED);
            original.authorize(context, AUTHORIZATION);
            first.lifecycle.setCurrentState(Lifecycle.State.DESTROYED);

            Owner second = new Owner(store);
            AccountLoginViewModel restored = new ViewModelProvider(second, factory)
                    .get(AccountLoginViewModel.class);
            restored.restoreInterruptedLogin(true);
            List<AccountLoginViewModel.State> secondStates = new ArrayList<>();
            restored.states()
                    .observe(second, secondStates::add);
            second.lifecycle.setCurrentState(Lifecycle.State.STARTED);
            assertSame(original, restored);
            assertEquals(1, starter.starts);
            assertEquals(AccountLoginViewModel.Phase.AUTHENTICATING, restored.current().phase);
            assertEquals(0, starter.cancellations);

            starter.listener.onReady();

            assertEquals(AccountLoginViewModel.Phase.AUTHENTICATING, firstStates.get(firstStates.size() - 1).phase);
            assertEquals(AccountLoginViewModel.Phase.FINISHED, secondStates.get(secondStates.size() - 1).phase);
            assertEquals(Activity.RESULT_OK, RuntimeAccountActivity.resultCode(restored.current()));
            second.lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
            store.clear();
        });
    }

    @Test
    public void clearedOwnerCancelsAndProcessRestoreDoesNotRepeatAuthentication() throws Exception {
        onMain(() -> {
            ViewModelStore store = new ViewModelStore();
            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel model = new ViewModelProvider(
                    new Owner(store),
                    new AccountLoginViewModel.Factory(starter)
            )
                    .get(AccountLoginViewModel.class);
            model.authorize(context, AUTHORIZATION);

            store.clear();
            starter.listener.onReady();

            assertEquals(1, starter.cancellations);
            assertEquals(AccountLoginViewModel.Phase.AUTHENTICATING, model.current().phase);
            FakeStarter freshStarter = new FakeStarter();
            AccountLoginViewModel fresh = new AccountLoginViewModel(freshStarter);
            fresh.restoreInterruptedLogin(true);
            fresh.authorize(context, AUTHORIZATION);
            assertEquals(0, freshStarter.starts);
            assertEquals(Activity.RESULT_CANCELED, RuntimeAccountActivity.resultCode(fresh.current()));
        });
    }

    @Test
    public void browsingCancelAndInvalidRedirectFinishWithoutStartingAuthentication() throws Exception {
        onMain(() -> {
            FakeStarter starter = new FakeStarter();
            AccountLoginViewModel browsing = new AccountLoginViewModel(starter);
            browsing.cancel();
            assertEquals(Activity.RESULT_CANCELED, RuntimeAccountActivity.resultCode(browsing.current()));

            AccountLoginViewModel denied = new AccountLoginViewModel(starter);
            denied.authorize(context, Uri.parse(
                    "ms-xal-00000000402b5328://auth/?error=access_denied&error_description=test-private-response"
            ));
            assertEquals(Activity.RESULT_CANCELED, RuntimeAccountActivity.resultCode(denied.current()));
            assertNull(RuntimeAccountActivity.resultIntent(denied.current())
                    .getExtras());
            for (Uri uri : new Uri[]{
                    null,
                    Uri.parse("ms-xal-00000000402b5328://auth/"),
                    Uri.parse("ms-xal-00000000402b5328:opaque"),
                    Uri.parse("https://example.invalid/?code=test-private-code"),
                    Uri.parse("ms-xal-00000000402b5328://auth/?code=one&code=two")
            }) {
                AccountLoginViewModel invalid = new AccountLoginViewModel(starter);
                invalid.authorize(context, uri);
                assertFailure(invalid, AccountAuthenticationException.Reason.INVALID_RESPONSE);
            }
            assertEquals(0, starter.starts);
        });
    }

    private static void assertNoResult(AccountLoginViewModel model) {
        try {
            RuntimeAccountActivity.resultCode(model.current());
            fail("실제 인증 작업 종료 전에 Activity 결과를 만들었습니다");
        } catch (IllegalArgumentException expected) {
            assertTrue(model.current().phase != AccountLoginViewModel.Phase.FINISHED);
        }
    }

    private static void assertFailure(
            AccountLoginViewModel model,
            AccountAuthenticationException.Reason expected
    ) {
        assertEquals(Activity.RESULT_FIRST_USER, RuntimeAccountActivity.resultCode(model.current()));
        Intent result = RuntimeAccountActivity.resultIntent(model.current());
        assertEquals(expected.name(), result.getStringExtra(RuntimeAccountActivity.EXTRA_AUTH_REASON));
        Bundle extras = result.getExtras();
        assertEquals(1, extras.size());
        assertFalse(extras.toString()
                .contains("test-private"));
    }

    private static void onMain(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(task);
        task.get(5, TimeUnit.SECONDS);
    }

    private static final class FakeStarter implements AccountLoginViewModel.Starter,
            AmethystRuntime.AuthenticationRequest {
        int starts;
        int cancellations;
        boolean acceptCancellation = true;
        Context context;
        AmethystRuntime.AuthenticationListener listener;

        @Override
        public AmethystRuntime.AuthenticationRequest start(
                Context context,
                String code,
                AmethystRuntime.AuthenticationListener listener
        ) {
            starts++;
            this.context = context;
            this.listener = listener;
            assertEquals("test-private-code", code);
            return this;
        }

        @Override
        public boolean cancel() {
            cancellations++;
            return acceptCancellation;
        }
    }

    private static final class Owner implements LifecycleOwner, ViewModelStoreOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        final ViewModelStore store;

        Owner(ViewModelStore store) {
            this.store = store;
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }

        @NonNull
        @Override
        public ViewModelStore getViewModelStore() {
            return store;
        }
    }
}
