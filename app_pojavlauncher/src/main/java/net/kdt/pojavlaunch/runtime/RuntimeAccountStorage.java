package net.kdt.pojavlaunch.runtime;

import android.content.Context;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.runtime.AccountAuthenticationException.Reason;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** 취소 불가 commit 단계에서만 계정 파일과 선택을 반영한다. */
final class RuntimeAccountStorage {
    private static final String EMPTY_PROFILE_ID = "00000000-0000-0000-0000-000000000000";

    private RuntimeAccountStorage() {}

    static MinecraftAccount read(Context context, String id) throws AccountAuthenticationException {
        if (id == null || id.isEmpty()) {
            throw new AccountAuthenticationException(Reason.REAUTHENTICATION_REQUIRED);
        }
        try {
            Path directory = directory(context);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("저장 계정 폴더를 읽을 수 없습니다");
            }
            AccountStore store = new AccountStore(directory.toFile());
            Path file;
            try {
                file = store.accountFile(id);
            } catch (IOException failure) {
                if (Files.notExists(directory.resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS)) {
                    throw new AccountAuthenticationException(Reason.REAUTHENTICATION_REQUIRED);
                }
                throw failure;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                    StandardCharsets.UTF_8
            ))) {
                MinecraftAccount account = Tools.GLOBAL_GSON.fromJson(reader, MinecraftAccount.class);
                if (account == null) {
                    throw new IOException("저장 계정 정보가 없습니다");
                }
                normalize(account);
                return account;
            }
        } catch (AccountAuthenticationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            // 기존 load의 파서 원문 로그를 사용하지 않고 안전한 실패 사유만 전달한다.
            throw new AccountAuthenticationException(Reason.STORAGE);
        }
    }

    static void refresh(
            Context context,
            String id,
            MinecraftAccount previous,
            MinecraftAccount updated
    ) throws AccountAuthenticationException {
        UUID profile = validateCandidate(updated);
        if (previous == null || !profile.equals(profileId(previous.profileId))) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        updated.selectedVersion = previous.selectedVersion == null ? "1.7.10" : previous.selectedVersion;
        try {
            Path directory = directory(context);
            Path target = new AccountStore(directory.toFile())
                    .accountFile(id);
            write(
                    directory,
                    target,
                    updated,
                    true
            );
        } catch (IOException | RuntimeException failure) {
            throw new AccountAuthenticationException(Reason.STORAGE);
        }
    }

    static String login(Context context, MinecraftAccount candidate) throws AccountAuthenticationException {
        UUID profile = validateCandidate(candidate);
        try {
            Path directory = directory(context);
            ensureDirectory(directory);
            AccountStore store = new AccountStore(directory.toFile());
            String selectedId = PojavProfile.getCurrentProfileName(context);
            List<AccountSummary> accounts = store.list(selectedId);
            String id = matchingSlot(accounts, profile, selectedId);
            boolean replace = id != null;
            if (replace) {
                MinecraftAccount previous = read(context, id);
                candidate.selectedVersion = previous.selectedVersion;
            } else {
                id = candidate.username;
                Path preferred = directory.resolve(id + ".json");
                if (!Files.notExists(preferred, LinkOption.NOFOLLOW_LINKS)) {
                    do {
                        id = UUID.randomUUID()
                                .toString();
                    } while (!Files.notExists(directory.resolve(id + ".json"), LinkOption.NOFOLLOW_LINKS));
                }
            }
            Path target;
            if (replace) {
                target = store.accountFile(id);
            } else {
                target = directory.resolve(id + ".json");
            }
            write(
                    directory,
                    target,
                    candidate,
                    replace
            );
            PojavProfile.saveCurrentProfile(context, id);
            return id;
        } catch (AccountAuthenticationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new AccountAuthenticationException(Reason.STORAGE);
        }
    }

    private static Path directory(Context context) {
        Tools.initEarlyConstants(context);
        return new File(Tools.DIR_ACCOUNT_NEW).toPath();
    }

    private static void ensureDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("저장 계정 폴더를 사용할 수 없습니다");
        }
    }

    private static UUID validateCandidate(MinecraftAccount candidate) throws AccountAuthenticationException {
        if (candidate == null || !candidate.isMicrosoft) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        if (candidate.username != null && candidate.username.startsWith("Demo.")) {
            throw new AccountAuthenticationException(Reason.NO_GAME);
        }
        UUID profile = profileId(candidate.profileId);
        if (candidate.username == null || !candidate.username.matches("[A-Za-z0-9_]{3,16}")
                || profile == null || EMPTY_PROFILE_ID.equals(profile.toString())
                || !hasToken(candidate.accessToken) || !hasToken(candidate.msaRefreshToken)) {
            throw new AccountAuthenticationException(Reason.INVALID_RESPONSE);
        }
        candidate.profileId = profile.toString();
        return profile;
    }

    private static boolean hasToken(String token) {
        return token != null && !token.isEmpty() && !token.equals("0");
    }

    private static UUID profileId(String value) {
        if (value == null) {
            return null;
        }
        if (value.matches("[0-9a-fA-F]{32}")) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
        } else if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return null;
        }
        return UUID.fromString(value);
    }

    private static String matchingSlot(List<AccountSummary> accounts, UUID profile, String selectedId) {
        String first = null;
        for (AccountSummary account : accounts) {
            if (!profile.equals(profileId(account.profileId))) {
                continue;
            }
            if (account.id.equals(selectedId)) {
                return account.id;
            }
            if (first == null) {
                first = account.id;
            }
        }
        return first;
    }

    private static void write(
            Path directory,
            Path target,
            MinecraftAccount account,
            boolean replace
    ) throws IOException {
        ensureDirectory(directory);
        Path temporary = Files.createTempFile(directory, ".account-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                Tools.GLOBAL_GSON.toJson(account, writer);
            }
            if (replace) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("기존 계정 파일을 교체할 수 없습니다");
                }
            } else if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("새 계정 저장 위치가 이미 사용 중입니다");
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void normalize(MinecraftAccount account) {
        if (account.accessToken == null) {
            account.accessToken = "0";
        }
        if (account.clientToken == null) {
            account.clientToken = "0";
        }
        if (account.profileId == null) {
            account.profileId = EMPTY_PROFILE_ID;
        }
        if (account.username == null) {
            account.username = "0";
        }
        if (account.selectedVersion == null) {
            account.selectedVersion = "1.7.10";
        }
        if (account.msaRefreshToken == null) {
            account.msaRefreshToken = "0";
        }
    }
}
