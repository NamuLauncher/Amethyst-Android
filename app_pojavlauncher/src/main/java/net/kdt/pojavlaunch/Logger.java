package net.kdt.pojavlaunch;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Keep
public final class Logger {
    private static final String TAG = "AmethystGame";
    private static final CopyOnWriteArrayList<LogLineListener> listeners = new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> readTask;
    private static volatile File logFile;

    static {
        System.loadLibrary("pojavexec");
    }

    private Logger() {}

    public static void appendToLog(String text) {
        appendUtf8((text + '\n').getBytes(StandardCharsets.UTF_8));
    }

    public static synchronized boolean open(File file) {
        if (logFile != null) {
            return logFile.equals(file);
        }
        if (!openLogFile(file.getAbsolutePath())) {
            return false;
        }
        logFile = file;
        startReading();
        return true;
    }

    public static File file() {
        return logFile;
    }

    public static native void beginOutput();

    public static native void endLogging();

    private static native boolean openLogFile(String path);

    private static native void appendUtf8(byte[] text);

    public static synchronized void addLogListener(LogLineListener listener) {
        listeners.addIfAbsent(listener);
        startReading();
    }

    public static synchronized void removeLogListener(LogLineListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty() && readTask != null) {
            readTask.cancel(false);
            readTask = null;
        }
    }

    private static void startReading() {
        File file = logFile;
        if (file == null || listeners.isEmpty() || readTask != null) {
            return;
        }
        FileTail tail = new FileTail(file);
        readTask = reader.scheduleWithFixedDelay(tail::read, 0, 50, TimeUnit.MILLISECONDS);
    }

    public interface LogLineListener {
        void onLine(String text);
    }

    private static final class FileTail {
        private final File file;
        private final byte[] chunk = new byte[8192];
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private long offset;

        FileTail(File file) {
            this.file = file;
            offset = file.length();
        }

        void read() {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                long remaining = input.length() - offset;
                while (remaining > 0) {
                    int count = input.read(chunk, 0, (int)Math.min(remaining, chunk.length));
                    if (count <= 0) {
                        break;
                    }
                    offset += count;
                    remaining -= count;
                    for (int index = 0; index < count; index++) {
                        if (chunk[index] == '\n') {
                            dispatchLine();
                        } else {
                            line.write(chunk[index]);
                        }
                    }
                }
            } catch (IOException failure) {
                Log.w(TAG, "Read runtime log failed", failure);
            }
        }

        private void dispatchLine() {
            String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
            line.reset();
            for (LogLineListener listener : listeners) {
                try {
                    listener.onLine(text);
                } catch (RuntimeException failure) {
                    Log.e(TAG, "Handle runtime log failed", failure);
                }
            }
        }
    }
}
