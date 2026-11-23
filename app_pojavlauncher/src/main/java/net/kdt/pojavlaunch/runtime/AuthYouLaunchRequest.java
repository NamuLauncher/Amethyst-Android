package net.kdt.pojavlaunch.runtime;

import android.content.Intent;

import java.util.Objects;

/**
 * AuthYou 인증 세션을 열고 게임을 실행할 때 호스트가 넘기는 세션 인자.
 *
 * <p>호스트는 게임 실행 전에 게임 폴더의 {@code ay/config.json}을 준비해야 한다.
 * 게임 폴더(파일 검증 루트)는 Amethyst가 작업 디렉터리로 설정한 폴더를 그대로 쓴다.
 */
public final class AuthYouLaunchRequest {
    private static final String EXTRA_SERVER_ID = "net.kdt.pojavlaunch.runtime.AUTHYOU_SERVER_ID";
    private static final String EXTRA_USER_ID = "net.kdt.pojavlaunch.runtime.AUTHYOU_USER_ID";
    private static final String EXTRA_HOST = "net.kdt.pojavlaunch.runtime.AUTHYOU_HOST";
    private static final String EXTRA_FILE_SET_ID = "net.kdt.pojavlaunch.runtime.AUTHYOU_FILE_SET_ID";

    public final String serverId;
    public final String userId;
    public final String host;
    /** 선택 값. 지정하지 않으면 null이다. */
    public final String fileSetId;

    public AuthYouLaunchRequest(String serverId, String userId, String host, String fileSetId) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.host = Objects.requireNonNull(host, "host");
        this.fileSetId = fileSetId;
    }

    void writeTo(Intent intent) {
        intent.putExtra(EXTRA_SERVER_ID, serverId);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_HOST, host);
        if (fileSetId != null) {
            intent.putExtra(EXTRA_FILE_SET_ID, fileSetId);
        }
    }

    /** AuthYou 인자가 없으면 기본 Amethyst 실행 경로이므로 null을 반환한다. */
    static AuthYouLaunchRequest readFrom(Intent intent) {
        String serverId = intent.getStringExtra(EXTRA_SERVER_ID);
        String userId = intent.getStringExtra(EXTRA_USER_ID);
        String host = intent.getStringExtra(EXTRA_HOST);
        if (serverId == null && userId == null && host == null) {
            return null;
        }
        if (serverId == null || userId == null || host == null) {
            throw new IllegalArgumentException("AuthYou 실행 인자가 불완전합니다");
        }

        return new AuthYouLaunchRequest(
                serverId,
                userId,
                host,
                intent.getStringExtra(EXTRA_FILE_SET_ID)
        );
    }
}
