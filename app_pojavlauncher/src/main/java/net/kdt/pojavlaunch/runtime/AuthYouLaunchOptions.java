package net.kdt.pojavlaunch.runtime;

import java.util.Objects;

/** 호스트가 게임 시작에 제공하는 AuthYou3 서버 설정. */
public final class AuthYouLaunchOptions {
    public final String host;
    public final String serverId;
    public final String fileSetId;

    public AuthYouLaunchOptions(String host, String serverId, String fileSetId) {
        this.host = Objects.requireNonNull(host);
        this.serverId = Objects.requireNonNull(serverId);
        this.fileSetId = fileSetId;
    }
}
