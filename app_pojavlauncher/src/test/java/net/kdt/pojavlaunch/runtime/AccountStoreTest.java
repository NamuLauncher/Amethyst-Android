package net.kdt.pojavlaunch.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AccountStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void listsStoredKeysAndMetadataWithoutChangingSelectionOrCredentials() throws Exception {
        File directory = temporary.newFolder("accounts");
        String online = "{\"username\":\"TestPlayer\",\"profileId\":\"test-profile\","
                + "\"isMicrosoft\":true,\"accessToken\":\"test-private-access\","
                + "\"msaRefreshToken\":\"test-private-refresh\"}";
        write(directory, "Online.json", online);
        write(directory, "Local.json", "{\"username\":\"LocalPlayer\"}");
        write(directory, "Demo.json", "{\"username\":\"Demo.TestPlayer\",\"isMicrosoft\":true}");
        write(directory, "unrelated", "not an account");
        assertTrue(new File(directory, "Folder.json").mkdir());
        AccountStore store = new AccountStore(directory);

        List<AccountSummary> accounts = store.list("Online");

        assertEquals(3, accounts.size());
        assertEquals("Demo", accounts.get(0).id);
        assertTrue(accounts.get(0).demo);
        assertFalse(accounts.get(1).microsoft);
        assertFalse(accounts.get(1).selected);
        AccountSummary account = accounts.get(2);
        assertEquals("Online", account.id);
        assertEquals("TestPlayer", account.username);
        assertEquals("test-profile", account.profileId);
        assertTrue(account.microsoft);
        assertTrue(account.selected);
        assertFalse(account.demo);
        byte[] saved = Files.readAllBytes(new File(directory, "Online.json").toPath());
        assertEquals(online, new String(saved, StandardCharsets.UTF_8));
        assertThrows(UnsupportedOperationException.class, accounts::clear);
        assertFalse(store.get("Online", "Missing").selected);
    }

    @Test
    public void missingDirectoryIsEmptyButAnUnreadableShapeIsAnError() throws Exception {
        File missing = new File(temporary.getRoot(), "missing");
        assertTrue(new AccountStore(missing)
                .list("")
                .isEmpty());
        assertFalse(missing.exists());
        File ordinaryFile = temporary.newFile("not-a-directory");
        assertThrows(IOException.class, () -> new AccountStore(ordinaryFile).list(""));
        assertThrows(IOException.class, () -> new AccountStore(missing).get("TestPlayer", ""));
    }

    @Test
    public void invalidIdsAndLinksCannotReadOrDeleteOutsideAccounts() throws Exception {
        File directory = temporary.newFolder("accounts");
        File outside = temporary.newFile("Outside.json");
        Files.write(outside.toPath(), "{\"username\":\"OutsidePlayer\"}".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(directory, "Linked.json").toPath(), outside.toPath());
        AccountStore store = new AccountStore(directory);

        assertTrue(store.list("Linked")
                .isEmpty());
        for (String id : new String[]{null, "", ".", "..", "../Outside", "folder/name", "folder\\name", "line\nname"}) {
            assertThrows(IllegalArgumentException.class, () -> store.get(id, ""));
            assertThrows(IllegalArgumentException.class, () -> store.delete(id));
        }
        assertThrows(IOException.class, () -> store.get("Linked", ""));
        assertThrows(IOException.class, () -> store.delete("Linked"));
        assertTrue(outside.isFile());
    }

    @Test
    public void malformedAccountFailureDoesNotExposeJsonOrTurnIntoEmptySuccess() throws Exception {
        File directory = temporary.newFolder("accounts");
        AccountStore store = new AccountStore(directory);
        for (String json : new String[]{
                "{\"accessToken\":\"test-private-value\",\"username\":",
                "{\"username\":{\"accessToken\":\"test-private-value\"}}",
                "{\"username\":\"TestPlayer\",\"isMicrosoft\":\"test-private-value\"}",
                "null",
                "[]"
        }) {
            write(directory, "TestPlayer.json", json);
            IOException failure = assertThrows(IOException.class, () -> store.list("TestPlayer"));
            assertFalse(failure.toString().contains("test-private-value"));
            assertNull(failure.getCause());
        }
    }

    @Test
    public void deletingOneStoredFilePreservesOthersAndCanRemoveACorruptKnownEntry() throws Exception {
        File directory = temporary.newFolder("accounts");
        write(directory, "First.json", "{\"username\":\"FirstPlayer\"}");
        write(directory, "Second.json", "{\"username\":\"SecondPlayer\"}");
        AccountStore store = new AccountStore(directory);
        assertEquals(2, store.list("First")
                .size());
        write(directory, "First.json", "broken account");

        store.delete("First");

        assertFalse(new File(directory, "First.json").exists());
        assertEquals("Second", store.list("First")
                .get(0).id);
        assertFalse(store.list("First")
                .get(0).selected);
        assertThrows(IOException.class, () -> store.delete("First"));
        assertTrue(new File(directory, "Second.json").isFile());
    }

    private static void write(File directory, String name, String value) throws IOException {
        Files.write(new File(directory, name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
