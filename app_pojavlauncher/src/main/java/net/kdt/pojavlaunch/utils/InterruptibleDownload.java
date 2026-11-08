package net.kdt.pojavlaunch.utils;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;

/** interrupt를 읽기 경계에서 확인하고 완전히 받은 파일만 게시한다. */
final class InterruptibleDownload {
    private static final int TIMEOUT_MS = 10000;

    private InterruptibleDownload() {}

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download cancelled");
        }
    }

    static void download(
            URL url,
            OutputStream output,
            byte[] buffer,
            String userAgent,
            BiConsumer<Integer, Integer> progress
    ) throws IOException {
        download(
                url,
                output,
                buffer,
                userAgent,
                progress,
                null
        );
    }

    static void download(
            URL url,
            OutputStream output,
            byte[] buffer,
            String userAgent,
            BiConsumer<Integer, Integer> progress,
            LongConsumer receivedBytes
    ) throws IOException {
        checkInterrupted();
        HttpURLConnection connection = openConnection(url, userAgent);
        try {
            int response = connection.getResponseCode();
            checkInterrupted();
            if (response == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new FileNotFoundException("Server returned HTTP 404: " + url);
            }
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + response + ": " + url);
            }
            long length = connection.getContentLengthLong();
            long completed = 0;
            try (InputStream input = connection.getInputStream()) {
                while (true) {
                    checkInterrupted();
                    int count = input.read(buffer);
                    // 읽은 뒤 쓰기나 취소가 실패해도 이미 수신한 본문은 계측에 포함한다.
                    if (count > 0 && receivedBytes != null) {
                        receivedBytes.accept(count);
                    }
                    checkInterrupted();
                    if (count < 0) {
                        break;
                    }
                    output.write(buffer, 0, count);
                    completed += count;
                    if (progress != null) {
                        progress.accept(
                                (int) Math.min(completed, Integer.MAX_VALUE),
                                (int) Math.min(length, Integer.MAX_VALUE)
                        );
                    }
                }
            }
            if (length >= 0 && completed != length) {
                throw new EOFException("Incomplete HTTP response");
            }
        } finally {
            connection.disconnect();
        }
    }

    static void downloadFile(
            String url,
            File destination,
            byte[] buffer,
            String userAgent,
            BiConsumer<Integer, Integer> progress
    ) throws IOException {
        downloadFile(
                url,
                destination,
                buffer,
                userAgent,
                progress,
                null
        );
    }

    static void downloadFile(
            String url,
            File destination,
            byte[] buffer,
            String userAgent,
            BiConsumer<Integer, Integer> progress,
            LongConsumer receivedBytes
    ) throws IOException {
        checkInterrupted();
        FileUtils.ensureParentDirectory(destination);
        Path temporary = Files.createTempFile(destination.getParentFile().toPath(), "download-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                download(
                        new URL(url),
                        output,
                        buffer,
                        userAgent,
                        progress,
                        receivedBytes
                );
            }
            checkInterrupted();
            Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static long contentLength(String url, String userAgent) throws IOException {
        checkInterrupted();
        HttpURLConnection connection = openConnection(new URL(url), userAgent);
        connection.setRequestMethod("HEAD");
        try {
            int response = connection.getResponseCode();
            checkInterrupted();
            if (response >= 200 && response <= 299) {
                return connection.getContentLengthLong();
            }
            return -1;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(URL url, String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        return connection;
    }
}
