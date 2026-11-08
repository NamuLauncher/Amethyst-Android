package net.kdt.pojavlaunch.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 기존 계정 파일 형식을 유지하면서 표시 필드만 읽는다. */
final class AccountStore {
    private final Path directory;

    AccountStore(File directory) {
        this.directory = directory.toPath();
    }

    List<AccountSummary> list(String selectedId) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Collections.emptyList();
        }
        requireDirectory();
        List<AccountSummary> accounts = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.json")) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = entry.getFileName()
                        .toString();
                String id = name.substring(0, name.length() - 5);
                if (isValidId(id)) {
                    accounts.add(read(entry, id, selectedId));
                }
            }
        }
        accounts.sort(Comparator.comparing(account -> account.id));
        return Collections.unmodifiableList(accounts);
    }

    AccountSummary get(String id, String selectedId) throws IOException {
        return read(accountFile(id), id, selectedId);
    }

    void delete(String id) throws IOException {
        // 이미 표시한 계정 파일이 손상됐더라도 동일한 저장 항목을 삭제할 수 있다.
        Files.delete(accountFile(id));
    }

    Path accountFile(String id) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("올바른 저장 계정 ID가 필요합니다");
        }
        requireDirectory();
        Path file = directory.resolve(id + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("저장한 계정을 찾을 수 없습니다");
        }
        return file;
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("저장 계정 폴더를 읽을 수 없습니다");
        }
    }

    private static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.equals(".") || id.equals("..")) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            if (character == '/' || character == '\\' || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static AccountSummary read(Path file, String id, String selectedId) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject account = JsonParser.parseReader(reader)
                    .getAsJsonObject();
            String username = string(account, "username", null);
            if (username == null || username.trim().isEmpty()) {
                throw new IllegalStateException();
            }
            String profileId = string(account, "profileId", "00000000-0000-0000-0000-000000000000");
            boolean microsoft = false;
            JsonElement value = account.get("isMicrosoft");
            if (value != null && !value.isJsonNull()) {
                if (!value.isJsonPrimitive()) {
                    throw new IllegalStateException();
                }
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (!primitive.isBoolean()) {
                    throw new IllegalStateException();
                }
                microsoft = primitive.getAsBoolean();
            }
            return new AccountSummary(
                    id,
                    username,
                    profileId,
                    microsoft,
                    id.equals(selectedId)
            );
        } catch (JsonParseException | IllegalStateException failure) {
            // JSON 파서의 원문 미리보기와 토큰을 오류 원인·로그에 남기지 않는다.
            throw new IOException("저장 계정 정보의 형식이 올바르지 않습니다");
        }
    }

    private static String string(JsonObject account, String key, String fallback) {
        JsonElement value = account.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive()) {
            throw new IllegalStateException();
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw new IllegalStateException();
        }
        return primitive.getAsString();
    }
}
