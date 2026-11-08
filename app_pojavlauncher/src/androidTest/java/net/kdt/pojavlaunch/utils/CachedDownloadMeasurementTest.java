package net.kdt.pojavlaunch.utils;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CachedDownloadMeasurementTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void existingVerifiedOrUnhashedFileDoesNotEnterTheMeasuredDownload() throws Exception {
        File target = directory.newFile("test-library.jar");
        byte[] body = "cached test library".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), body);
        StringBuilder digest = new StringBuilder();
        byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest(body);
        for (byte value : hash) {
            digest.append(String.format("%02x", value & 0xff));
        }
        AtomicLong received = new AtomicLong();

        for (String expectedHash : new String[] { digest.toString(), null }) {
            DownloadUtils.ensureSha1(target, expectedHash, () -> {
                DownloadUtils.downloadFileMonitored(
                        "http://127.0.0.1:1/unexpected-test-request",
                        target,
                        new byte[4],
                        (completed, total) -> {},
                        received::addAndGet
                );
                fail("캐시 적중은 네트워크 다운로드를 시작하면 안 됩니다");
                return null;
            });
            assertEquals(0, received.get());
            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
        }
    }
}
