package net.kdt.pojavlaunch.utils;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class InterruptibleDownloadTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    private MockWebServer server;

    @Before
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void stopServer() throws IOException {
        server.shutdown();
    }

    @Test
    public void successfulDownloadPublishesOnlyTheCompleteFile() throws Exception {
        server.enqueue(new MockResponse().setBody("complete test data"));
        File target = new File(directory.getRoot(), "test.jar");

        transfer(target);

        assertEquals("complete test data", read(target));
        assertEquals("TestRuntime", server.takeRequest().getHeader("User-Agent"));
        assertNoTemporaryFiles();
    }

    @Test
    public void incompleteResponseDoesNotReplaceAnExistingFile() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("incomplete test data")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        File target = directory.newFile("test.jar");
        Files.write(target.toPath(), "previous data".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> transfer(target));

        assertEquals("previous data", read(target));
        assertNoTemporaryFiles();
    }

    @Test
    public void interruptStopsWritesAndDoesNotPublishThePartialDownload() throws Exception {
        server.enqueue(new MockResponse().setBody("test data that spans several buffers"));
        File target = new File(directory.getRoot(), "test.jar");
        CountDownLatch wroteChunk = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                InterruptibleDownload.downloadFile(
                        server.url("/test.jar").toString(),
                        target,
                        new byte[4],
                        "TestRuntime",
                        (done, total) -> {
                            wroteChunk.countDown();
                            boolean interrupted = false;
                            while (release.getCount() > 0) {
                                try {
                                    release.await();
                                } catch (InterruptedException ignored) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                );
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            worker.start();
            assertTrue(wroteChunk.await(2, TimeUnit.SECONDS));
            assertFalse(target.exists());
            worker.interrupt();
            release.countDown();
            worker.join(2000);

            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedIOException);
            assertFalse(target.exists());
            assertNoTemporaryFiles();
        } finally {
            release.countDown();
            worker.interrupt();
            worker.join(2000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    public void alreadyInterruptedWorkerDoesNotStartANewRequestOrFile() {
        File target = new File(directory.getRoot(), "test.jar");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> transfer(target));
            assertEquals(0, server.getRequestCount());
            assertFalse(target.exists());
            assertNoTemporaryFiles();
        } finally {
            Thread.interrupted();
        }
    }

    private void transfer(File target) throws IOException {
        InterruptibleDownload.downloadFile(
                server.url("/test.jar").toString(),
                target,
                new byte[4],
                "TestRuntime",
                null
        );
    }

    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void assertNoTemporaryFiles() {
        File[] temporary = directory.getRoot().listFiles((parent, name) -> name.endsWith(".part"));
        assertNotNull(temporary);
        assertEquals(0, temporary.length);
    }
}
