package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonParseException;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 버전 목록을 조회하며 네트워크 실패 시 유효한 캐시를 사용한다. */
public class AsyncVersionList {
    public void getVersionList(@Nullable VersionDoneListener listener, boolean secondPass) {
        sExecutorService.execute(() -> {
            JMinecraftVersionList versionList = null;
            try {
                versionList = loadVersionList(secondPass);
            } catch (Exception failure) {
                Log.e("AsyncVersionList", "Cannot load Minecraft versions", failure);
            }
            if (listener != null) {
                listener.onVersionDone(versionList);
            }
        });
    }

    /** 호스트 요청의 worker에서 실행하여 취소와 작업 종료를 같은 수명으로 관리한다. */
    public JMinecraftVersionList getVersionListForHost() throws IOException {
        return loadVersionList(false);
    }

    private JMinecraftVersionList loadVersionList(boolean secondPass) throws IOException {
        File versionFile = new File(Tools.DIR_CACHE, "version_list.json");
        return loadVersionList(versionFile, LauncherPreferences.PREF_VERSION_REPOS, secondPass);
    }

    JMinecraftVersionList loadVersionList(File versionFile, String mirror, boolean secondPass) throws IOException {
        Exception downloadFailure = null;
        Exception cacheFailure = null;
        int attempts = secondPass ? 1 : 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            DownloadUtils.throwIfInterrupted();
            boolean stale = System.currentTimeMillis() > versionFile.lastModified() + 86400000;
            if (!versionFile.isFile() || stale || attempt > 0) {
                try {
                    return downloadVersionList(mirror, versionFile);
                } catch (IOException | JsonParseException failure) {
                    DownloadUtils.throwIfInterrupted();
                    downloadFailure = failure;
                }
            }

            try (FileReader reader = new FileReader(versionFile)) {
                JMinecraftVersionList cached = Tools.GLOBAL_GSON.fromJson(reader, JMinecraftVersionList.class);
                requireVersions(cached);
                DownloadUtils.throwIfInterrupted();
                return cached;
            } catch (IOException | JsonParseException failure) {
                DownloadUtils.throwIfInterrupted();
                cacheFailure = failure;
            }
        }

        // 캐시 읽기 실패가 다운로드 실패 원인을 가리지 않도록 보조 예외로 남긴다.
        Exception cause = downloadFailure != null ? downloadFailure : cacheFailure;
        IOException failure = new IOException("Cannot load Minecraft versions", cause);
        if (downloadFailure != null && cacheFailure != null) {
            failure.addSuppressed(cacheFailure);
        }
        throw failure;
    }

    private JMinecraftVersionList downloadVersionList(String mirror, File versionFile) throws IOException {
        String json = DownloadUtils.downloadString(mirror);
        JMinecraftVersionList versions = Tools.GLOBAL_GSON.fromJson(json, JMinecraftVersionList.class);
        requireVersions(versions);
        DownloadUtils.throwIfInterrupted();
        FileUtils.ensureParentDirectory(versionFile);
        Path target = versionFile.toPath();
        Path temporary = Files.createTempFile(target.getParent(), "version-list-", ".part");
        try {
            Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
            DownloadUtils.throwIfInterrupted();
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary);
        }
        return versions;
    }

    private void requireVersions(JMinecraftVersionList versions) throws IOException {
        if (versions == null || versions.versions == null) {
            throw new IOException("Invalid Minecraft version list");
        }
    }

    public interface VersionDoneListener {
        void onVersionDone(JMinecraftVersionList versions);
    }
}
