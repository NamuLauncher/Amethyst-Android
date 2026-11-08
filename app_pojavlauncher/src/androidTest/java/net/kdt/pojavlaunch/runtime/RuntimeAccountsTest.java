package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;

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
import java.util.stream.Stream;

@RunWith(AndroidJUnit4.class)
public final class RuntimeAccountsTest {
    private AccountContext context;
    private File directory;

    @Before
    public void createIsolatedAccountStore() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        context = new AccountContext(target);
        assertTrue(AmethystRuntime.accounts(context)
                .isEmpty());
        directory = new File(context.getFilesDir().getParentFile(), "accounts");
        assertTrue(directory.mkdir());
        write("First", "FirstPlayer", true);
        write("Second", "SecondPlayer", true);
        write("Local", "LocalPlayer", false);
    }

    @After
    public void removeOnlyTestAccounts() throws Exception {
        if (context != null) {
            context.cleanUp();
            Tools.initEarlyConstants(context.getBaseContext());
        }
    }

    @Test
    public void publicSelectionAndDeletionUseExistingProfilePreferences() throws Exception {
        AmethystRuntime.selectAccount(context, "First");
        assertEquals("First", PojavProfile.getCurrentProfileName(context));
        assertEquals("First", selectedId());

        AmethystRuntime.selectAccount(context, "Second");
        assertEquals("Second", selectedId());
        AmethystRuntime.deleteAccount(context, "First");
        assertEquals("Second", selectedId());
        assertFalse(new File(directory, "First.json").exists());

        AmethystRuntime.deleteAccount(context, "Second");
        assertEquals("", PojavProfile.getCurrentProfileName(context));
        assertEquals("", selectedId());
        assertEquals(1, AmethystRuntime.accounts(context)
                .size());
        assertFalse(AmethystRuntime.accounts(context)
                .get(0).microsoft);

        AmethystRuntime.selectAccount(context, "Local");
        assertEquals("Local", selectedId());
    }

    @Test
    public void failedSelectionAndDeletionKeepTheCurrentAccount() throws Exception {
        AmethystRuntime.selectAccount(context, "First");
        try {
            AmethystRuntime.selectAccount(context, "Missing");
            fail("존재하지 않는 계정을 선택했습니다");
        } catch (IOException expected) {
            assertEquals("First", selectedId());
        }
        assertTrue(new File(directory, "NotAFile.json").mkdir());
        try {
            AmethystRuntime.deleteAccount(context, "NotAFile");
            fail("계정이 아닌 디렉터리를 삭제했습니다");
        } catch (IOException expected) {
            assertEquals("First", selectedId());
        }
        assertTrue(new File(directory, "First.json").isFile());
    }

    private String selectedId() throws IOException {
        String selected = "";
        for (AccountSummary account : AmethystRuntime.accounts(context)) {
            if (account.selected) {
                assertEquals("", selected);
                selected = account.id;
            }
        }
        return selected;
    }

    private void write(String id, String username, boolean microsoft) throws IOException {
        String json = "{\"username\":\"" + username + "\",\"isMicrosoft\":" + microsoft
                + ",\"accessToken\":\"test-private-access\",\"msaRefreshToken\":\"test-private-refresh\"}";
        Files.write(new File(directory, id + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class AccountContext extends ContextWrapper {
        private final String prefix = "account-test-" + UUID.randomUUID();
        private final File root;
        private final Set<String> preferences = new HashSet<>();

        AccountContext(Context base) throws IOException {
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
