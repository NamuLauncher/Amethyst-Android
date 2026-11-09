package net.kdt.pojavlaunch;

/** 실제 Application의 Context 연결을 사용하고 게임 준비는 각 테스트가 명시적으로 시작한다. */
public final class RuntimeTestApplication extends PojavApplication {
    @Override
    public void onCreate() {
        // 기존 계측 Application처럼 런타임 압축 해제와 전역 크래시 UI를 시작하지 않는다.
    }
}
