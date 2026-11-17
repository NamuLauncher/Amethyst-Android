# 호스트의 컨트롤 파일 지정

호스트 앱이 게임 프로필마다 다른 컨트롤 배치를 선택할 수 있도록 `AmethystRuntime.startPreparation()`에 `controlFile` 인수를 받는 오버로드를 추가한다. `controlFile`은 `controlmap` 폴더 내부의 JSON 파일명이다. 경로 이동을 막기 위해 경로 구분자와 NUL을 거부하고 `.json` 확장자를 확인한다. 서비스별 인스턴스 ID의 형식은 제한하지 않는다.

파일 준비와 서비스별 이름 정책은 호스트가 담당한다. 런타임은 받은 파일명을 `MinecraftProfile.controlFile`에 저장하며, 기존 게임 화면의 프로필별 로딩과 편집 저장 동작을 사용한다. null이면 기존 전역 기본 프리셋 선택을 유지한다. 기존 인수 목록의 API도 그대로 사용할 수 있다.

2026-11-17 namu 호스트의 최소 인게임 UI 요청에 따라 기본 에셋 `default.json`에는 ESC와 메뉴만 남겼다. 메뉴는 오른쪽 위, ESC는 그 왼쪽에 있으며 게임 플레이와 게임 메뉴에서 모두 표시한다. 기존 기기의 프리셋 파일을 덮어쓰는 정책은 추가하지 않았다.

JSON 형식·키코드·표시 설정과 공백 오류를 확인했다. 호스트 Gradle 빌드는 Android SDK 경로 누락으로 실행되지 못했으며 실제 인게임 렌더링·입력은 미검증이다.

캐시된 Android·의존성 JAR과 기존 클래스에 대해 변경된 `AmethystRuntime.java`를 JDK 17 `javac -proc:none -source 17 -target 17`로 컴파일해 통과했다. 전체 Android 빌드 성공을 의미하지 않는다.
