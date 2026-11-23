package net.kdt.pojavlaunch.authyou;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.runtime.AuthYouLaunchRequest;

/**
 * AuthYou C(libauthyou_launcher.so)에 게임 실행을 요청한다.
 *
 * <p>AuthYou C가 SIGSEGV·SIGABRT 핸들러를 설치한 뒤 AuthYou Go(libauthyou.so)를 로드하고,
 * AuthYou Go가 인증 세션을 연 다음 Amethyst C로 HotSpot을 실행한다. 두 라이브러리는
 * 호스트 앱의 jniLibs에 함께 들어 있어야 한다.
 */
@Keep
public final class AuthYouLauncher {
    /** 게임 시작 전 단계가 실패했을 때 일반 종료 코드로 기록하는 값. */
    public static final int START_FAILURE_EXIT_CODE = 91;

    private static final String TAG = "AuthYou";
    private static final String LAUNCHER_LIBRARY = "authyou_launcher";

    private static AuthYouLogFile logFileListener;

    private AuthYouLauncher() {}

    /** 게임 실행 결과 또는 시작 실패 코드 91을 반환한다. 치명 시그널이면 반환하지 않고 프로세스가 끝난다. */
    public static int launchGame(
            Context applicationContext,
            String[] jvmArguments,
            File baseDirectory,
            AuthYouLaunchRequest request
    ) {
        try {
            System.loadLibrary(LAUNCHER_LIBRARY);
        } catch (UnsatisfiedLinkError failure) {
            logStartFailure("AuthYou C 라이브러리(lib" + LAUNCHER_LIBRARY + ".so)를 열지 못했습니다", failure);
            return START_FAILURE_EXIT_CODE;
        }

        try {
            return nativeLaunchGame(
                    applicationContext,
                    jvmArguments,
                    request.serverId,
                    request.userId,
                    request.host,
                    baseDirectory.getAbsolutePath(),
                    request.fileSetId
            );
        } catch (UnsatisfiedLinkError failure) {
            logStartFailure("AuthYou C의 게임 실행 함수를 찾지 못했습니다", failure);
            return START_FAILURE_EXIT_CODE;
        }
    }

    /** latest.log를 비우고 열어 onEventLogged 리스너로 등록한다. 게임 프로세스에서 한 번만 호출한다. */
    public static void startLogFile(File logFile) {
        if (logFileListener != null) {
            return;
        }
        logFileListener = AuthYouLogFile.open(logFile);
        Logger.addLogListener(logFileListener);
    }

    /** 게임 시작 중 실패는 logcat에만 남긴다. latest.log에는 실행 중 로그만 기록한다. */
    public static void logStartFailure(String message, Throwable failure) {
        Log.e(TAG, message, failure);
    }

    public static void logStartFailure(String message) {
        Log.e(TAG, message);
    }

    private static native int nativeLaunchGame(
            Context applicationContext,
            String[] jvmArguments,
            String serverId,
            String userId,
            String host,
            String baseDirectory,
            String fileSetId
    );
}
