package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public final class RuntimeAuthenticationTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final String PROFILE_ID = "11111111-2222-4333-8444-555555555555";
    private static final String ANOTHER_PROFILE_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String ACCOUNT_ID = "TestSlot";
    private AccountContext context;
    private File directory;
    private MinecraftAccount previous;
    private ExecutorService workers;
    private CountDownLatch releaseAuthentication;

    @Before
    public void createIsolatedStoredAccount() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context = new AccountContext(target);
        Tools.initEarlyConstants(context);
        directory = new File(context.getFilesDir().getParentFile(), "accounts");
        assertTrue(directory.mkdir());
        previous = account("OldPlayer", PROFILE_ID);
        previous.expiresAt = NOW;
        writePrevious();
        PojavProfile.setCurrentProfile(context, ACCOUNT_ID);
        workers = Executors.newSingleThreadExecutor();
    }

    @After
    public void removeOnlyTestFilesAfterWorkersFinish() throws Exception {
        if (releaseAuthentication != null) {
            releaseAuthentication.countDown();
        }
        if (workers != null) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        if (context != null) {
            context.cleanUp();
            Tools.initEarlyConstants(context.getBaseContext());
        }
    }

    @Test
    public void tokenLifetimeBoundaryRefreshesOnceAndRenamesWithinTheSameStoredSlot() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean refreshRequested = new AtomicBoolean();
        AtomicReference<String> suppliedCredential = new AtomicReference<>();
        MinecraftAccount updated = account("NewPlayer", PROFILE_ID);
        updated.accessToken = "test-updated-access";
        updated.msaRefreshToken = "test-updated-refresh";
        RuntimeAuthentication authentication = authentication((refresh, credential) -> {
            calls.incrementAndGet();
            refreshRequested.set(refresh);
            suppliedCredential.set(credential);
            return updated;
        });
        previous.expiresAt = NOW + 60_001;
        writePrevious();
        String validFile = storedJson();
        Result valid = new Result();

        authentication.refresh(valid);
        valid.awaitReady();

        assertEquals(0, calls.get());
        assertUnchanged(validFile);

        previous.expiresAt = NOW + 60_000;
        writePrevious();
        Result expired = new Result();
        authentication.refresh(expired);
        expired.awaitReady();

        assertEquals(1, calls.get());
        assertTrue(refreshRequested.get());
        assertEquals(previous.msaRefreshToken, suppliedCredential.get());
        assertEquals(ACCOUNT_ID, PojavProfile.getCurrentProfileName(context));
        MinecraftAccount saved = MinecraftAccount.parse(storedJson());
        assertEquals("NewPlayer", saved.username);
        assertEquals(PROFILE_ID, saved.profileId);
        assertEquals("test-updated-access", saved.accessToken);
        assertEquals("test-updated-refresh", saved.msaRefreshToken);
        assertEquals(updated.expiresAt, saved.expiresAt);
        assertFalse(new File(directory, "NewPlayer.json").exists());
        assertSingleStoredFile();
    }

    @Test
    public void missingRefreshAndNetworkFailureKeepThePreviousAccountAndSelection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAuthentication authentication = authentication((refresh, credential) -> {
            calls.incrementAndGet();
            throw new AccountAuthenticationException(AccountAuthenticationException.Reason.NETWORK);
        });
        previous.msaRefreshToken = "0";
        writePrevious();
        String missingRefreshFile = storedJson();
        Result missingRefresh = new Result();

        authentication.refresh(missingRefresh);
        missingRefresh.awaitFailure(AccountAuthenticationException.Reason.REAUTHENTICATION_REQUIRED);

        assertEquals(0, calls.get());
        assertUnchanged(missingRefreshFile);

        previous.msaRefreshToken = "test-old-refresh";
        writePrevious();
        String beforeNetwork = storedJson();
        Result network = new Result();
        authentication.refresh(network);
        network.awaitFailure(AccountAuthenticationException.Reason.NETWORK);

        assertEquals(1, calls.get());
        assertUnchanged(beforeNetwork);
    }

    @Test
    public void cancellationBeforeLateAuthenticationResultNeverReplacesStoredCredentials() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        releaseAuthentication = new CountDownLatch(1);
        MinecraftAccount late = account("LatePlayer", PROFILE_ID);
        late.accessToken = "test-late-access";
        String original = storedJson();
        RuntimeAuthentication authentication = authentication((refresh, credential) -> {
            entered.countDown();
            waitUntilReleased(releaseAuthentication, interrupted);
            return late;
        });
        Result result = new Result();

        AmethystRuntime.AuthenticationRequest request = authentication.refresh(result);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(request.cancel());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1L, result.finished.getCount());
        assertUnchanged(original);

        releaseAuthentication.countDown();
        result.awaitCancelled();

        assertUnchanged(original);
        assertFalse(new File(directory, "LatePlayer.json").exists());
        assertFalse(request.cancel());
    }

    @Test
    public void demoLoginAndDifferentIdentityRefreshCannotReplaceTheSelectedAccount() throws Exception {
        String original = storedJson();
        MinecraftAccount demo = account("Demo.Player", "00000000-0000-0000-0000-000000000000");
        RuntimeAuthentication login = authentication((refresh, credential) -> demo);
        Result noGame = new Result();

        login.login("test-authorization-code", noGame);
        noGame.awaitFailure(AccountAuthenticationException.Reason.NO_GAME);

        assertUnchanged(original);

        MinecraftAccount different = account("AnotherPlayer", ANOTHER_PROFILE_ID);
        RuntimeAuthentication refresh = authentication((isRefresh, credential) -> different);
        Result wrongIdentity = new Result();
        refresh.refresh(wrongIdentity);
        wrongIdentity.awaitFailure(AccountAuthenticationException.Reason.INVALID_RESPONSE);

        assertUnchanged(original);
        assertFalse(new File(directory, "AnotherPlayer.json").exists());
    }

    @Test
    public void publicFacadeRejectsMissingAccountAndEmptyCodeOnTheMainCallbackThread() throws Exception {
        String original = storedJson();
        PojavProfile.setCurrentProfile(context, null);
        Result missing = new Result();

        AmethystRuntime.startAccountRefresh(context, missing);
        missing.awaitFailure(AccountAuthenticationException.Reason.REAUTHENTICATION_REQUIRED);

        assertTrue(missing.onMainThread);
        assertEquals("", PojavProfile.getCurrentProfileName(context));
        assertEquals(original, storedJson());

        Result emptyCode = new Result();
        AmethystRuntime.startAccountLogin(context, "  ", emptyCode);
        emptyCode.awaitFailure(AccountAuthenticationException.Reason.INVALID_RESPONSE);

        assertTrue(emptyCode.onMainThread);
        assertEquals("", PojavProfile.getCurrentProfileName(context));
        assertEquals(original, storedJson());
        assertSingleStoredFile();
    }

    private RuntimeAuthentication authentication(RuntimeAuthentication.Authenticator authenticator) {
        return new RuntimeAuthentication(
                context,
                workers,
                Runnable::run,
                authenticator,
                () -> NOW
        );
    }

    private static MinecraftAccount account(String username, String profileId) {
        MinecraftAccount account = new MinecraftAccount();
        account.username = username;
        account.profileId = profileId;
        account.isMicrosoft = true;
        account.accessToken = "test-old-access";
        account.msaRefreshToken = "test-old-refresh";
        account.expiresAt = NOW + 3_600_000;
        account.xuid = "123456789";
        return account;
    }

    private void writePrevious() throws IOException {
        String json = Tools.GLOBAL_GSON.toJson(previous);
        Files.write(new File(directory, ACCOUNT_ID + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private String storedJson() throws IOException {
        byte[] bytes = Files.readAllBytes(new File(directory, ACCOUNT_ID + ".json").toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void assertUnchanged(String original) throws IOException {
        assertEquals(original, storedJson());
        assertEquals(ACCOUNT_ID, PojavProfile.getCurrentProfileName(context));
        assertSingleStoredFile();
    }

    private void assertSingleStoredFile() {
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertEquals(ACCOUNT_ID + ".json", files[0].getName());
    }

    private static void waitUntilReleased(CountDownLatch release, CountDownLatch interrupted) {
        boolean waiting = true;
        while (waiting) {
            try {
                release.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private static final class Result implements AmethystRuntime.AuthenticationListener {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger ready = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger cancelled = new AtomicInteger();
        volatile Throwable failure;
        volatile boolean onMainThread;

        @Override
        public void onReady() {
            ready.incrementAndGet();
            complete();
        }

        @Override
        public void onFailure(Throwable value) {
            failure = value;
            failed.incrementAndGet();
            complete();
        }

        @Override
        public void onCancelled() {
            cancelled.incrementAndGet();
            complete();
        }

        private void complete() {
            onMainThread = Looper.myLooper() == Looper.getMainLooper();
            finished.countDown();
        }

        void awaitReady() throws InterruptedException {
            awaitCompletion();
            assertEquals(1, ready.get());
        }

        void awaitFailure(AccountAuthenticationException.Reason reason) throws InterruptedException {
            awaitCompletion();
            assertEquals(1, failed.get());
            assertTrue(failure instanceof AccountAuthenticationException);
            assertEquals(reason, ((AccountAuthenticationException) failure).reason);
        }

        void awaitCancelled() throws InterruptedException {
            awaitCompletion();
            assertEquals(1, cancelled.get());
        }

        private void awaitCompletion() throws InterruptedException {
            assertTrue("인증 결과를 기다리는 시간이 초과됐습니다", finished.await(5, TimeUnit.SECONDS));
            assertEquals(1, ready.get() + failed.get() + cancelled.get());
        }
    }

    private static final class AccountContext extends ContextWrapper {
        private final String prefix = "authentication-test-" + UUID.randomUUID();
        private final File root;
        private final Set<String> preferences = new HashSet<>();

        AccountContext(Context base) throws IOException {
            super(base);
            root = new File(base.getCacheDir(), prefix);
            if (!new File(root, "files").mkdirs()) {
                throw new IOException("격리 인증 테스트 폴더를 만들지 못했습니다");
            }
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getFilesDir() {
            return new File(root, "files");
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            String isolatedName = prefix + "-" + name;
            preferences.add(isolatedName);
            return getBaseContext()
                    .getSharedPreferences(isolatedName, mode);
        }

        void cleanUp() throws IOException {
            for (String name : preferences) {
                getBaseContext()
                        .deleteSharedPreferences(name);
            }
            try (Stream<Path> paths = Files.walk(root.toPath())) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
    }
}
