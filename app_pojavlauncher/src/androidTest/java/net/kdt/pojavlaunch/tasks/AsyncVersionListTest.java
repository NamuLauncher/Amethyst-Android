package net.kdt.pojavlaunch.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.kdt.pojavlaunch.JMinecraftVersionList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

@RunWith(AndroidJUnit4.class)
public class AsyncVersionListTest {
    private static final String INVALID_DOWNLOAD_URL = "invalid-test-url";

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void missingCacheDoesNotHideDownloadFailure() {
        File cacheFile = new File(directory.getRoot(), "version_list.json");

        IOException failure = assertThrows(IOException.class, () ->
                new AsyncVersionList().loadVersionList(cacheFile, INVALID_DOWNLOAD_URL, false));

        assertTrue(failure.getCause() instanceof MalformedURLException);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0] instanceof FileNotFoundException);
    }

    @Test
    public void validStaleCacheIsUsedAfterDownloadFailure() throws IOException {
        File cacheFile = directory.newFile("version_list.json");
        String cachedJson = "{\"versions\":[{\"id\":\"TestVersion\"}]}";
        Files.write(cacheFile.toPath(), cachedJson.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(cacheFile.toPath(), FileTime.fromMillis(1000));

        JMinecraftVersionList versions = new AsyncVersionList()
                .loadVersionList(cacheFile, INVALID_DOWNLOAD_URL, false);

        assertEquals(1, versions.versions.length);
        assertEquals("TestVersion", versions.versions[0].id);
    }
}
