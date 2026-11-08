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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

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
    public void actualBodyReadsCountUnknownLengthAndIgnoreErrorBodies() throws Exception {
        server.enqueue(new MockResponse()
                .setChunkedBody("test body", 3));
        AtomicLong received = new AtomicLong();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InterruptibleDownload.download(
                server.url("/test.jar")
                        .url(),
                output,
                new byte[4],
                "TestRuntime",
                null,
                received::addAndGet
        );
        assertEquals(9, received.get());
        assertArrayEquals("test body".getBytes(StandardCharsets.UTF_8), output.toByteArray());

        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("unread error body"));
        assertThrows(IOException.class, () -> InterruptibleDownload.download(
                server.url("/missing.jar")
                        .url(),
                output,
                new byte[4],
                "TestRuntime",
                null,
                received::addAndGet
        ));
        assertEquals(9, received.get());
    }

    @Test
    public void failedPartialAttemptAndRetryAccumulateEveryActualReadOnce() throws Exception {
        String body = "test body spanning several chunks";
        server.enqueue(new MockResponse()
                .setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        File target = directory.newFile("test.jar");
        Files.write(target.toPath(), "previous".getBytes(StandardCharsets.UTF_8));
        AtomicLong received = new AtomicLong();

        assertThrows(IOException.class, () -> transferMeasured(target, received));
        long partial = received.get();
        assertTrue(partial > 0);
        assertTrue(partial < body.length());
        assertEquals("previous", read(target));
        assertNoTemporaryFiles();

        server.enqueue(new MockResponse()
                .setBody(body));
        transferMeasured(target, received);
        assertEquals(partial + body.length(), received.get());
        assertEquals(body, read(target));
        assertEquals(2, server.getRequestCount());
        assertNoTemporaryFiles();
    }

    @Test
    public void bytesAlreadyReadRemainCountedWhenLocalWriteFails() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("test data"));
        AtomicLong received = new AtomicLong();
        AtomicLong processed = new AtomicLong();
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("test disk failure");
            }
        };
        assertThrows(IOException.class, () -> InterruptibleDownload.download(
                server.url("/test.jar")
                        .url(),
                failingOutput,
                new byte[4],
                "TestRuntime",
                (completed, total) -> processed.set(completed),
                received::addAndGet
        ));

        assertEquals(4, received.get());
        assertEquals(0, processed.get());
    }

    @Test
    public void cancellationAfterReadDoesNotUndoReceivedBytesOrWriteTheChunk() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("test data"));
        AtomicLong received = new AtomicLong();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertThrows(InterruptedIOException.class, () -> InterruptibleDownload.download(
                    server.url("/test.jar")
                        .url(),
                    output,
                    new byte[4],
                    "TestRuntime",
                    null,
                    bytes -> {
                        received.addAndGet(bytes);
                        Thread.currentThread().interrupt();
                    }
            ));
            assertEquals(4, received.get());
            assertEquals(0, output.size());
        } finally {
            Thread.interrupted();
        }
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

    private void transferMeasured(File target, AtomicLong received) throws IOException {
        InterruptibleDownload.downloadFile(
                server.url("/test.jar").toString(),
                target,
                new byte[4],
                "TestRuntime",
                null,
                received::addAndGet
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
