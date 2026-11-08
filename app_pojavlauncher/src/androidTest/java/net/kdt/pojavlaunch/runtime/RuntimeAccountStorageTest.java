package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public final class RuntimeAccountStorageTest {
    private static final String FIRST_PROFILE = "12345678-1234-4234-8234-123456789abc";
    private static final String SECOND_PROFILE = "23456789-2345-4345-8345-23456789abcd";
    private static final String THIRD_PROFILE = "3456789a-3456-4456-8456-3456789abcde";
    private static final String SECRET = "test-private-storage-value";
    private StorageContext context;
    private File directory;

    @Before
    public void createIsolatedStorage() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context = new StorageContext(target);
        Tools.initEarlyConstants(context);
        directory = new File(Tools.DIR_ACCOUNT_NEW);
        assertTrue(directory.mkdir());
    }

    @After
    public void removeOnlyTestFilesAndPreferences() throws Exception {
        if (context != null) {
            context.cleanUp();
            Tools.initEarlyConstants(context.getBaseContext());
        }
    }

    @Test
    public void readNormalizesLegacyNullsAndReportsMissingOrCorruptSlotsWithoutSecrets() throws Exception {
        String nullFields = "{\"accessToken\":null,\"clientToken\":null,\"profileId\":null,"
                + "\"username\":null,\"selectedVersion\":null,\"msaRefreshToken\":null}";
        writeRaw("LegacySlot", nullFields);

        MinecraftAccount account = RuntimeAccountStorage.read(context, "LegacySlot");

        assertEquals("0", account.accessToken);
        assertEquals("0", account.clientToken);
        assertEquals("00000000-0000-0000-0000-000000000000", account.profileId);
        assertEquals("0", account.username);
        assertEquals("1.7.10", account.selectedVersion);
        assertEquals("0", account.msaRefreshToken);
        expectFailure(Reason.REAUTHENTICATION_REQUIRED, () -> RuntimeAccountStorage.read(context, ""));
        expectFailure(Reason.REAUTHENTICATION_REQUIRED, () -> RuntimeAccountStorage.read(context, "MissingSlot"));

        writeRaw("CorruptSlot", "{\"accessToken\":\"" + SECRET + "\",\"username\":");
        expectFailure(Reason.STORAGE, () -> RuntimeAccountStorage.read(context, "CorruptSlot"));
        expectFailure(Reason.STORAGE, () -> RuntimeAccountStorage.read(context, "../OutsideSlot"));
    }

    @Test
    public void refreshRenamesThePlayerInsideTheOriginalSlotAndPreservesItsSettings() throws Exception {
        MinecraftAccount previous = candidate("BeforePlayer", FIRST_PROFILE);
        previous.selectedVersion = "test-saved-version";
        write("StableSlot", previous);
        PojavProfile.saveCurrentProfile(context, "StableSlot");
        MinecraftAccount updated = candidate("AfterPlayer", FIRST_PROFILE.replace("-", ""));
        updated.accessToken = "test-updated-access";
        updated.msaRefreshToken = "test-updated-refresh";

        RuntimeAccountStorage.refresh(
                context,
                "StableSlot",
                previous,
                updated
        );

        MinecraftAccount stored = RuntimeAccountStorage.read(context, "StableSlot");
        assertEquals("AfterPlayer", stored.username);
        assertEquals(FIRST_PROFILE, stored.profileId);
        assertEquals("test-updated-access", stored.accessToken);
        assertEquals("test-updated-refresh", stored.msaRefreshToken);
        assertEquals("test-saved-version", stored.selectedVersion);
        assertEquals("StableSlot", PojavProfile.getCurrentProfileName(context));
        assertFalse(new File(directory, "AfterPlayer.json").exists());
        assertEquals(1, requireEntries().length);
    }

    @Test
    public void rejectedIdentityDemoAndWriteFailuresLeaveTheOldAccountAndSelectionUntouched() throws Exception {
        MinecraftAccount previous = candidate("BeforePlayer", FIRST_PROFILE);
        write("StableSlot", previous);
        PojavProfile.saveCurrentProfile(context, "StableSlot");
        byte[] before = Files.readAllBytes(new File(directory, "StableSlot.json").toPath());

        assertRejectedRefresh(
                previous,
                candidate("AfterPlayer", SECOND_PROFILE),
                Reason.INVALID_RESPONSE,
                before
        );
        assertRejectedRefresh(
                previous,
                candidate("Demo.Player", FIRST_PROFILE),
                Reason.NO_GAME,
                before
        );
        assertRejectedRefresh(
                previous,
                candidate("../Outside", FIRST_PROFILE),
                Reason.INVALID_RESPONSE,
                before
        );
        assertRejectedRefresh(
                previous,
                candidate("AfterPlayer", "not-a-uuid"),
                Reason.INVALID_RESPONSE,
                before
        );
        assertRejectedRefresh(
                previous,
                candidate("AfterPlayer", "00000000-0000-0000-0000-000000000000"),
                Reason.INVALID_RESPONSE,
                before
        );
        MinecraftAccount local = candidate("AfterPlayer", FIRST_PROFILE);
        local.isMicrosoft = false;
        assertRejectedRefresh(
                previous,
                local,
                Reason.INVALID_RESPONSE,
                before
        );
        MinecraftAccount missingToken = candidate("AfterPlayer", FIRST_PROFILE);
        missingToken.msaRefreshToken = null;
        assertRejectedRefresh(
                previous,
                missingToken,
                Reason.INVALID_RESPONSE,
                before
        );

        // 계정 파일이 있는 작은 테스트 디렉터리만 읽기 전용으로 바꿔 실제 쓰기 실패를 만든다.
        Os.chmod(directory.getAbsolutePath(), 0500);
        try {
            expectFailure(
                    Reason.STORAGE,
                    () -> RuntimeAccountStorage.refresh(
                            context,
                            "StableSlot",
                            previous,
                            candidate("AfterPlayer", FIRST_PROFILE)
                    )
            );
        } finally {
            Os.chmod(directory.getAbsolutePath(), 0700);
        }
        assertStoredUnchanged(before);
    }

    @Test
    public void loginReusesTheSelectedMatchingSlotThenTheFirstSortedMatchingSlot() throws Exception {
        MinecraftAccount first = candidate("FirstPlayer", FIRST_PROFILE);
        first.selectedVersion = "first-version";
        MinecraftAccount selected = candidate("SecondPlayer", FIRST_PROFILE);
        selected.selectedVersion = "selected-version";
        write("A_slot", first);
        write("Z_slot", selected);
        PojavProfile.saveCurrentProfile(context, "Z_slot");
        byte[] firstBytes = Files.readAllBytes(new File(directory, "A_slot.json").toPath());

        String selectedSlot = RuntimeAccountStorage.login(context, candidate("UpdatedPlayer", FIRST_PROFILE));

        assertEquals("Z_slot", selectedSlot);
        assertEquals("Z_slot", PojavProfile.getCurrentProfileName(context));
        assertEquals("selected-version", RuntimeAccountStorage.read(context, "Z_slot").selectedVersion);
        assertArrayEquals(firstBytes, Files.readAllBytes(new File(directory, "A_slot.json").toPath()));
        PojavProfile.saveCurrentProfile(context, null);

        String firstSlot = RuntimeAccountStorage.login(context, candidate("LatestPlayer", FIRST_PROFILE));

        assertEquals("A_slot", firstSlot);
        assertEquals("A_slot", PojavProfile.getCurrentProfileName(context));
        assertEquals("first-version", RuntimeAccountStorage.read(context, "A_slot").selectedVersion);
        assertEquals(2, requireEntries().length);
    }

    @Test
    public void loginUsesANewOpaqueSlotInsteadOfOverwritingAnotherAccountOrDirectory() throws Exception {
        write("TestPlayer", candidate("TestPlayer", SECOND_PROFILE));
        PojavProfile.saveCurrentProfile(context, "TestPlayer");
        byte[] existing = Files.readAllBytes(new File(directory, "TestPlayer.json").toPath());

        String id = RuntimeAccountStorage.login(context, candidate("TestPlayer", FIRST_PROFILE));

        assertNotEquals("TestPlayer", id);
        assertNotNull(UUID.fromString(id));
        assertEquals(id, PojavProfile.getCurrentProfileName(context));
        assertEquals(FIRST_PROFILE, RuntimeAccountStorage.read(context, id).profileId);
        assertArrayEquals(existing, Files.readAllBytes(new File(directory, "TestPlayer.json").toPath()));

        File occupiedDirectory = new File(directory, "OtherPlayer.json");
        assertTrue(occupiedDirectory.mkdir());
        String otherId = RuntimeAccountStorage.login(context, candidate("OtherPlayer", THIRD_PROFILE));

        assertNotEquals("OtherPlayer", otherId);
        assertTrue(occupiedDirectory.isDirectory());
        assertEquals(otherId, PojavProfile.getCurrentProfileName(context));
        assertEquals(THIRD_PROFILE, RuntimeAccountStorage.read(context, otherId).profileId);
        assertEquals(4, requireEntries().length);
    }

    private void assertRejectedRefresh(
            MinecraftAccount previous,
            MinecraftAccount updated,
            Reason reason,
            byte[] before
    ) throws Exception {
        expectFailure(reason, () -> RuntimeAccountStorage.refresh(
                context,
                "StableSlot",
                previous,
                updated
        ));
        assertStoredUnchanged(before);
    }

    private void assertStoredUnchanged(byte[] before) throws Exception {
        assertArrayEquals(before, Files.readAllBytes(new File(directory, "StableSlot.json").toPath()));
        assertEquals("StableSlot", PojavProfile.getCurrentProfileName(context));
        assertEquals(1, requireEntries().length);
    }

    private static MinecraftAccount candidate(String username, String profileId) {
        MinecraftAccount account = new MinecraftAccount();
        account.username = username;
        account.profileId = profileId;
        account.isMicrosoft = true;
        account.accessToken = SECRET;
        account.msaRefreshToken = "test-private-refresh";
        account.expiresAt = 1_800_000_000_000L;
        return account;
    }

    private void write(String id, MinecraftAccount account) throws IOException {
        writeRaw(id, Tools.GLOBAL_GSON.toJson(account));
    }

    private void writeRaw(String id, String json) throws IOException {
        Files.write(new File(directory, id + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private File[] requireEntries() {
        File[] entries = directory.listFiles();
        assertNotNull(entries);
        return entries;
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void expectFailure(Reason reason, CheckedAction action) throws Exception {
        try {
            action.run();
            fail("인증 저장 실패가 성공으로 처리됐습니다");
        } catch (AccountAuthenticationException failure) {
            assertEquals(reason, failure.reason);
            assertNull(failure.getCause());
            StringWriter text = new StringWriter();
            failure.printStackTrace(new PrintWriter(text));
            assertFalse(text.toString()
                    .contains(SECRET));
        }
    }

    private static final class StorageContext extends ContextWrapper {
        private final String prefix = "account-storage-test-" + UUID.randomUUID();
        private final File root;
        private final Set<String> preferences = new HashSet<>();

        StorageContext(Context base) throws IOException {
            super(base);
            root = new File(base.getCacheDir(), prefix);
            if (!new File(root, "files").mkdirs()) {
                throw new IOException("격리 계정 테스트 폴더를 만들지 못했습니다");
            }
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
