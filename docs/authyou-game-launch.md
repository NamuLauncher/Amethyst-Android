# AuthYou 호스트 게임 실행

호스트는 `AmethystRuntime.launchGame()`에 `AuthYouLaunchOptions`를 넘긴다. 게임 프로세스의 `AuthYouLauncher`가 AuthYou C를 로드하고 JNI를 통해 직접 실행을 요청한다. Amethyst C는 로그와 JVM 실행 콜백을 제공한다. C 브리지가 로드되지 않거나 JNI 진입점을 찾지 못하면 시작 실패 코드 `91`을 기록한다. 호스트가 옵션을 생략하면 기본 JVM 실행 경로를 사용한다.

AuthYou 경로에서는 앱 리스너를 먼저 등록하고, 인증 세션을 연 뒤에 게임 출력 파이프를 시작한다. AuthYou가 받은 게임 출력은 앱 리스너와 연결 인증에 전달된다. 정상 반환에서는 마지막 출력 처리가 끝난 후 인증 세션을 닫는다. AuthYou 경로의 크래시 보고서는 게임 디렉터리에 저장한다.

`GAME_LAUNCH_STARTED`는 실행 요청 시점을 나타낸다. 이전에 저장된 `JVM_STARTED`는 읽을 때 같은 상태로 변환한다.
