package net.kdt.pojavlaunch.runtime;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

/** 호스트 앱과 Android 게임 실행 구현 사이의 공개 경계. */
public final class AmethystRuntime {
    public static final String ACTION_GAME_RUN_CHANGED = "net.kdt.pojavlaunch.runtime.GAME_RUN_CHANGED";
    public static final String EXTRA_RUN_ID = "net.kdt.pojavlaunch.runtime.RUN_ID";
    public interface Completion {
        void onReady();
        void onFailure(Throwable failure);
    }

    public interface PreparationListener extends Completion {
        void onProgress(GamePreparationProgress progress);

        default void onCancelled() {
            onFailure(new CancellationException("게임 준비가 취소됐습니다"));
        }
    }

    public interface PreparationRequest {
        /** 수락 후에도 실제 작업 종료를 알리는 onCancelled까지 기다려야 한다. */
        boolean cancel();
    }

    private AmethystRuntime() {}

    public static void initialize(Context context) {
        if (LauncherPreferences.DEFAULT_PREF == null) {
            LauncherPreferences.DEFAULT_PREF = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(context);
        }
        LauncherPreferences.loadPreferences(context);
        new File(Tools.DIR_ACCOUNT_NEW).mkdirs();
    }

    public static String accountName(Context context) {
        initialize(context);
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(context, null);
        return account == null || !account.isMicrosoft || account.isDemo() ? null : account.username;
    }

    public static String offlineAccountName(Context context) {
        initialize(context);
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(context, null);
        return account != null && account.isLocal() ? account.username : null;
    }

    public static void createOfflineAccount(Context context, String name) throws java.io.IOException {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Invalid offline player name");
        }
        initialize(context);
        MinecraftAccount account = new MinecraftAccount();
        account.username = name;
        account.profileId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                .getBytes(StandardCharsets.UTF_8)).toString();
        account.save();
        PojavProfile.setCurrentProfile(context, name);
    }

    public static void openAccount(Activity activity) {
        activity.startActivity(new Intent(activity, RuntimeAccountActivity.class));
    }

    public interface LoginRequest {
        void cancel();
    }

    public interface DeviceCodeListener extends Completion {
        void onCode(String userCode, String verificationUri, int expiresInSeconds);
        void onAuthenticating();
    }

    /** UI는 호스트가 소유하며 콜백은 메인 스레드에서 전달한다. */
    public static LoginRequest startDeviceCodeLogin(
            Context context,
            DeviceCodeListener listener
    ) {
        initialize(context);
        RuntimeDeviceCodeLogin request = new RuntimeDeviceCodeLogin(
                context.getApplicationContext(),
                listener
        );
        request.start();
        return request;
    }

    public static void refreshAccount(Activity activity, Completion completion) {
        initialize(activity);
        MinecraftAccount account = PojavProfile.getCurrentProfileContent(activity, null);
        if (account == null || !account.isMicrosoft || account.isDemo()) {
            completion.onFailure(new IllegalStateException("Minecraft 로그인이 필요합니다"));
            return;
        }
        if (account.expiresAt > System.currentTimeMillis() + 60000) {
            completion.onReady();
            return;
        }
        new MicrosoftBackgroundLogin(true, account.msaRefreshToken).performLogin(null,
                updated -> completion.onReady(), completion::onFailure);
    }

    /** 앱 내부 게임 폴더에서 현재 ABI의 네이티브 경로를 반환한다. */
    public static File gameNativeDirectory(File gameDirectory) {
        return Tools.getGameNativeDirectory(gameDirectory);
    }

    public static void prepareGame(
            Activity activity,
            File directory,
            String version,
            String renderer,
            int memoryMb,
            String[] gameArguments,
            String[] jvmArguments,
            PreparationListener listener
    ) {
        startPreparation(
                activity,
                directory,
                version,
                renderer,
                memoryMb,
                gameArguments,
                jvmArguments,
                listener
        );
    }

    /** 콜백은 메인 스레드에서 전달하며 취소 완료 전에는 다음 준비를 시작하지 않는다. */
    public static PreparationRequest startPreparation(
            Activity activity,
            File directory,
            String version,
            String renderer,
            int memoryMb,
            String[] gameArguments,
            String[] jvmArguments,
            PreparationListener listener
    ) {
        Handler main = new Handler(Looper.getMainLooper());
        PreparationTask request = new PreparationTask(action -> main.post(action), listener);
        String[] requestedGameArguments = gameArguments == null ? null : gameArguments.clone();
        String[] requestedJvmArguments = jvmArguments == null ? null : jvmArguments.clone();
        request.start(PojavApplication.sExecutorService, () -> {
            request.progress(new GamePreparationProgress(
                    GamePreparationProgress.Stage.COMPONENTS,
                    GamePreparationProgress.Unit.UNKNOWN,
                    0,
                    -1
            ));
            initialize(activity);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create game directory");
            }
            activity.getSharedPreferences("amethyst_runtime", Context.MODE_PRIVATE)
                    .edit()
                    .putString("gameDirectory", directory.getAbsolutePath())
                    .commit();
            LauncherPreferences.loadPreferences(activity);
            LauncherProfiles.load();
            String profileId = UUID.nameUUIDFromBytes(directory.getAbsolutePath()
                    .getBytes(StandardCharsets.UTF_8))
                    .toString();
            MinecraftProfile profile = new MinecraftProfile();
            profile.name = directory.getName();
            profile.gameDir = directory.getAbsolutePath();
            profile.lastVersionId = version;
            profile.pojavRendererName = renderer;
            profile.extraGameArguments = requestedGameArguments;
            profile.extraJvmArguments = requestedJvmArguments;
            LauncherProfiles.mainProfileJson.profiles.put(profileId, profile);
            LauncherProfiles.write();
            LauncherPreferences.DEFAULT_PREF
                    .edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileId)
                    .putInt("allocation", memoryMb)
                    .commit();
            LauncherPreferences.loadPreferences(activity);

            AsyncAssetManager.prepareForHost(activity, request::progress);
            request.progress(new GamePreparationProgress(
                    GamePreparationProgress.Stage.VERSION_LIST,
                    GamePreparationProgress.Unit.UNKNOWN,
                    0,
                    -1
            ));
            ExtraCore.setValue(
                    ExtraConstants.RELEASE_TABLE,
                    new AsyncVersionList().getVersionListForHost()
            );
            request.checkCancelled();
            new MinecraftDownloader().prepareForHost(activity, version, request::progress);
        });
        return request;
    }

    public static java.util.Map<String, String> renderers(Context context) {
        initialize(context);
        Tools.RenderersList available = Tools.getCompatibleRenderers(context);
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < available.rendererIds.size(); index++) {
            result.put(available.rendererIds.get(index), available.rendererDisplayNames[index]);
        }
        return result;
    }

    public static boolean isGameRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.processName.equals(context.getPackageName() + ":game")) return true;
        }
        return false;
    }

    public static void launchGame(Activity activity) {
        activity.startActivity(new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /** 실행마다 다른 ID와 비어 있는 앱 내부 폴더를 지정한다. 기존 실행으로 복귀할 때는 인자 없는 오버로드를 쓴다. */
    public static void launchGame(Activity activity, String runId, File runDirectory) throws IOException {
        if (isGameRunning(activity)) {
            throw new IllegalStateException("게임이 이미 실행 중입니다");
        }
        File directory = privateRunDirectory(activity, runDirectory);
        if (directory.exists()) {
            String[] files = directory.list();
            if (files == null || files.length != 0) {
                throw new IOException("실행 기록 폴더는 비어 있어야 합니다");
            }
        }
        GameRunStore runStore = GameRunStore.create(runId, directory, System.currentTimeMillis());
        Intent intent = new Intent(activity, MainActivity.class)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(RuntimeGameSession.EXTRA_RUN_DIRECTORY, directory.getAbsolutePath());
        try {
            activity.startActivity(intent);
        } catch (RuntimeException failure) {
            new GameRunRecorder(runStore).failed(failure.getClass().getName(), System.currentTimeMillis());
            throw failure;
        }
    }

    /** 파일 부재는 null, 읽기 또는 형식 오류는 IOException으로 전달한다. */
    public static GameRunSnapshot readGameRun(File runDirectory) throws IOException {
        return new GameRunStore(runDirectory).read();
    }

    public static File gameLogFile(File runDirectory) {
        return new GameRunStore(runDirectory).logFile();
    }

    /** Java 실패의 타입과 호출 프레임만 기록하며 예외 메시지·JVM 인자를 포함하지 않는다. */
    public static File gameFailureFile(File runDirectory) {
        return new GameRunStore(runDirectory).failureFile();
    }

    /** hs_err가 없는 종료는 빈 목록이며 폴더 조회 실패를 빈 목록으로 숨기지 않는다. */
    public static List<File> gameCrashReports(File runDirectory) throws IOException {
        List<File> reports = new ArrayList<>();
        if (Files.notExists(runDirectory.toPath())) {
            return reports;
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.list(runDirectory.toPath())) {
            paths.filter(path -> path.getFileName().toString().matches("hs_err_pid[0-9]+\\.log"))
                    .sorted()
                    .forEach(path -> reports.add(path.toFile()));
        }
        return reports;
    }

    /** 명령 전송 여부만 반환한다. 호스트는 STOPPED 기록과 실제 프로세스 종료를 확인해야 한다. */
    public static boolean stopGame(Context context, String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("실행 ID가 올바르지 않습니다");
        }
        if (!isGameRunning(context)) {
            return false;
        }
        context.sendBroadcast(new Intent(RuntimeGameSession.ACTION_STOP_GAME)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_RUN_ID, runId));
        return true;
    }

    static File privateRunDirectory(Context context, File directory) throws IOException {
        File resolved = directory.getCanonicalFile();
        File privateRoot = context.getFilesDir().getCanonicalFile();
        if (resolved.equals(privateRoot) || !resolved.toPath().startsWith(privateRoot.toPath())) {
            throw new IOException("실행 기록은 앱 내부 하위 폴더에 저장해야 합니다");
        }
        return resolved;
    }
}
