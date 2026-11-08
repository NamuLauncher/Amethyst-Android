package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;

import net.kdt.pojavlaunch.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InterruptibleDownload.download(
                url,
                os,
                new byte[65536],
                USER_AGENT,
                null
        );
    }

    public static String downloadString(String url) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            download(url, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void downloadFile(String url, File output) throws IOException {
        InterruptibleDownload.downloadFile(
                url,
                output,
                new byte[65536],
                USER_AGENT,
                null
        );
    }

    public static void downloadFileMonitored(
            String url,
            File output,
            @Nullable byte[] buffer,
            Tools.DownloaderFeedback monitor
    ) throws IOException {
        downloadFileMonitored(
                url,
                output,
                buffer,
                monitor,
                null
        );
    }

    /** 파일 응답 본문을 읽을 때마다 양수 바이트 증분을 전달한다. */
    public static void downloadFileMonitored(
            String url,
            File output,
            @Nullable byte[] buffer,
            Tools.DownloaderFeedback monitor,
            @Nullable LongConsumer receivedBytes
    ) throws IOException {
        byte[] transferBuffer = buffer == null ? new byte[65536] : buffer;
        InterruptibleDownload.downloadFile(
                url,
                output,
                transferBuffer,
                USER_AGENT,
                monitor::updateProgress,
                receivedBytes
        );
    }

    public static void throwIfInterrupted() throws InterruptedIOException {
        InterruptibleDownload.checkInterrupted();
    }

    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException | CancellationException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        throwIfInterrupted();
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        throwIfInterrupted();
        T result = null;
        while (attempts < 5 && !fileOkay){
            throwIfInterrupted();
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
            throwIfInterrupted();
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        return InterruptibleDownload.contentLength(url, USER_AGENT);
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

