package net.kdt.pojavlaunch.runtime;

import java.io.IOException;

/** 게임 준비를 시작하기 전에 기기의 네트워크 연결이 없음을 감지했다. */
public final class NetworkUnavailableException extends IOException {
    public NetworkUnavailableException() {
        super("No network connection");
    }
}
