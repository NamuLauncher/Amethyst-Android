package net.kdt.pojavlaunch.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;

/** 한 게임 프로세스가 기록하고 다른 프로세스가 읽는 실행별 파일 저장소. */
final class GameRunStore {
    private final File directory;
    private final Path stateFile;

    GameRunStore(File directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .getAbsoluteFile();
        stateFile = new File(this.directory, "state.properties")
                .toPath();
    }

    static GameRunStore create(String runId, File directory, long now) throws IOException {
        GameRunSnapshot.validateRunId(runId);
        GameRunStore store = new GameRunStore(directory);
        Files.createDirectories(store.directory.toPath());
        if (Files.exists(store.stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("이미 기록한 실행 디렉터리는 다시 사용할 수 없습니다");
        }
        store.write(new GameRunSnapshot(
                runId,
                GameRunSnapshot.Stage.STARTING,
                now,
                now,
                0,
                null,
                null,
                null,
                false,
                null,
                null
        ));
        return store;
    }

    File logFile() {
        return new File(directory, "runtime.log");
    }

    File errorFilePattern() {
        return new File(directory, "hs_err_pid%p.log");
    }

    File failureFile() {
        return new File(directory, "failure.log");
    }

    GameRunSnapshot read() throws IOException {
        if (Files.notExists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            values.load(input);
            if (!"1".equals(required(values, "format"))) {
                throw new IllegalArgumentException("지원하지 않는 실행 상태 형식입니다");
            }
            return new GameRunSnapshot(
                    required(values, "runId"),
                    GameRunSnapshot.Stage.valueOf(required(values, "stage")),
                    Long.parseLong(required(values, "startedAt")),
                    Long.parseLong(required(values, "updatedAt")),
                    Integer.parseInt(required(values, "processId")),
                    optionalLong(values, "jvmStartedAt"),
                    optionalLong(values, "firstFrameAt"),
                    optionalInteger(values, "exitCode"),
                    booleanValue(values, "signal"),
                    values.getProperty("failureType"),
                    optionalLong(values, "jvmElapsedMillis"),
                    optionalLong(values, "processAliveMillis")
            );
        } catch (IllegalArgumentException failure) {
            throw new IOException("실행 상태 파일의 형식이 올바르지 않습니다", failure);
        }
    }

    void write(GameRunSnapshot snapshot) throws IOException {
        // 프로세스 간 읽기에는 SharedPreferences 캐시 대신 완성된 파일 교체를 사용한다.
        Properties values = new Properties();
        values.setProperty("format", "1");
        values.setProperty("runId", snapshot.runId);
        values.setProperty("stage", snapshot.stage.name());
        values.setProperty("startedAt", Long.toString(snapshot.startedAt));
        values.setProperty("updatedAt", Long.toString(snapshot.updatedAt));
        values.setProperty("processId", Integer.toString(snapshot.processId));
        values.setProperty("signal", Boolean.toString(snapshot.signal));
        if (snapshot.jvmStartedAt != null) {
            values.setProperty("jvmStartedAt", snapshot.jvmStartedAt.toString());
        }
        if (snapshot.firstFrameAt != null) {
            values.setProperty("firstFrameAt", snapshot.firstFrameAt.toString());
        }
        if (snapshot.jvmElapsedMillis != null) {
            values.setProperty("jvmElapsedMillis", snapshot.jvmElapsedMillis.toString());
        }
        if (snapshot.processAliveMillis != null) {
            values.setProperty("processAliveMillis", snapshot.processAliveMillis.toString());
        }
        if (snapshot.exitCode != null) {
            values.setProperty("exitCode", snapshot.exitCode.toString());
        }
        if (snapshot.failureType != null) {
            values.setProperty("failureType", snapshot.failureType);
        }

        Path temporary = Files.createTempFile(directory.toPath(), "state-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, null);
            }
            Files.move(
                    temporary,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("실행 상태의 필수 항목이 없습니다: " + key);
        }
        return value;
    }

    private static Long optionalLong(Properties values, String key) {
        String value = values.getProperty(key);
        return value == null ? null : Long.valueOf(value);
    }

    private static Integer optionalInteger(Properties values, String key) {
        String value = values.getProperty(key);
        return value == null ? null : Integer.valueOf(value);
    }

    private static boolean booleanValue(Properties values, String key) {
        String value = required(values, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("실행 상태의 불리언 값이 올바르지 않습니다: " + key);
        }
        return Boolean.parseBoolean(value);
    }
}
