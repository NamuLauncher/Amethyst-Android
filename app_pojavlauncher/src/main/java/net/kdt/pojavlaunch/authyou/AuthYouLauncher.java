package net.kdt.pojavlaunch.authyou;

import android.content.Context;

import androidx.annotation.Keep;

import java.io.File;

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
    private static final String LAUNCHER_LIBRARY = "authyou_launcher";

    private AuthYouLauncher() {}

    /**
     * 네이티브 실행 결과를 반환한다. Java 라이브러리 로드·연결 오류는 호출자에게 전파한다.
     * 치명 시그널이면 반환하지 않고 프로세스가 끝난다.
     */
    public static int launchGame(
            Context applicationContext,
            String[] jvmArguments,
            File baseDirectory,
            AuthYouLaunchRequest request
    ) {
        System.loadLibrary(LAUNCHER_LIBRARY);
        return nativeLaunchGame(
                applicationContext,
                jvmArguments,
                request.serverId,
                request.userId,
                request.host,
                baseDirectory.getAbsolutePath(),
                request.fileSetId
        );
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
