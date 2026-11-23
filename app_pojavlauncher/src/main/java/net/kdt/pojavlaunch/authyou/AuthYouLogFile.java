package net.kdt.pojavlaunch.authyou;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.kdt.pojavlaunch.Logger;

/**
 * AuthYou 실행 경로의 onEventLogged 리스너. 받은 텍스트를 latest.log에 한 줄씩 기록한다.
 *
 * <p>AuthYou Go의 정보·오류 로그, 필터를 통과한 게임 출력, appendToLog로 남긴 기록이 모두
 * 이 리스너를 거친다. 치명 시그널은 AuthYou C가 _exit()로 프로세스를 끝내므로 버퍼 없이
 * 매번 파일에 바로 쓴다. 파일을 열거나 쓰지 못해도 리스너는 남겨 AuthYou Go가 시작할 수 있게 한다.
 */
final class AuthYouLogFile implements Logger.eventLogListener {
    private static final String TAG = "AuthYou";

    private FileOutputStream output;

    private AuthYouLogFile(FileOutputStream output) {
        this.output = output;
    }

    /** 기존 내용을 비우고 연다. 열지 못하면 기록하지 않는 리스너를 반환한다. */
    static AuthYouLogFile open(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("로그 폴더를 만들지 못했습니다: " + parent);
            }
            return new AuthYouLogFile(new FileOutputStream(file, false));
        } catch (IOException failure) {
            Log.e(TAG, "AuthYou 로그 파일을 열지 못했습니다: " + file, failure);
            return new AuthYouLogFile(null);
        }
    }

    @Override
    public synchronized void onEventLogged(String text) {
        if (output == null) {
            return;
        }

        try {
            output.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            Log.e(TAG, "AuthYou 로그 파일에 쓰지 못해 기록을 중단합니다", failure);
            close();
        }
    }

    private void close() {
        try {
            output.close();
        } catch (IOException ignored) {
            // 이미 기록을 중단하는 중이므로 닫기 실패는 무시한다.
        }
        output = null;
    }
}
