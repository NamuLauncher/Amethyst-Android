# 런타임 포크 개발 절차

이 공개 포크는 범용 Android 게임 실행 라이브러리를 제공한다. 서버 API·FISH 서비스 정책·제품 UI는 호스트 앱에서 관리한다. upstream 이력·저작권·라이선스를 유지하며 기존 런처 코드를 일괄 병합하거나 정리하지 않는다.

## 브랜치 기준

- `main`은 배포 기준이며 릴리스·긴급 수정만 반영한다.
- `dev`는 다음 버전의 개발 통합 브랜치이며 기본 작업 기준이다.
- 최초 `main`과 `dev`는 `runtime-library`의 `dde652685a7d91df61bed275a53514cda9e5a86c`와 동일한 파일 트리·서브모듈 커밋으로 생성했다. 기존 `runtime-library`와 upstream 추적 브랜치는 보존한다.
- 일반 작업은 최신 `dev`에서 분기하고 PR을 거쳐 `dev`에 squash merge한다.
- 릴리스는 `dev → main` PR을 일반 merge한 뒤 릴리스 커밋에 버전 태그를 붙인다. 긴급 수정은 `main`에서 시작하고 `dev`에도 반영하며, 이력 동기화용 `main → dev` PR은 일반 merge한다.

브랜치 생성·기본 브랜치 변경·빌드 성공은 배포 완료가 아니다. 서명·게시와 호스트의 고정 커밋 갱신은 별도 단계다. 보호 규칙 설정이 완료됐다고 추정하지 않는다.

## 검증과 워크플로

`Runtime library` 워크플로는 `dev`·`main` 대상 PR과 `dev`·`main`·`runtime-library` push에서 라이브러리 테스트와 AAR 빌드를 실행한다. 기본 PR 이벤트에는 후속 커밋의 synchronize도 포함되므로 수정된 head를 다시 검증한다.

기존 `Android CI`는 upstream 독립 런처 APK 경로를 사용하는 이력으로 보존하고 수동 실행만 남긴다. 이 포크의 현재 `app_pojavlauncher`는 라이브러리이므로 일반 작업 브랜치 push에서 APK 워크플로를 실행하지 않는다. 수동 트리거는 과거 APK 워크플로를 현재 라이브러리 트리에서 사용할 수 있다는 보장이 아니다.

GitHub Actions를 사용할 수 없는 동안 필요한 검증은 로컬에서 실행하고 PR에 명령·검증한 커밋·결과·미검증 사항을 기록한다. 실행되지 않은 CI를 성공으로 바꾸거나 보호 규칙을 우회하지 않는다. Java 17, Android SDK 35, NDK 27.3.13750724, CMake 3.22.1과 LWJGL용 Java 8을 준비한다.

```sh
./gradlew :MioLibPatcher:test :app_pojavlauncher:testDebugUnitTest :app_pojavlauncher:assembleDebug --stacktrace
```

변경에 따라 JNI·Android 계측·호스트 앱 연동 검증을 추가한다. 라이브러리 빌드와 실제 게임 준비·실행·종료 검증, LGPL 재결합·설치 검증을 구분한다. 관련 검사가 통과하면 새 변경이나 실패 원인 없이 반복하거나 범위를 넓히지 않는다. 환경 파일·서명 키·계정 토큰은 커밋하지 않는다.

## PR과 독립 리뷰

모든 PR은 description에 문제와 변경 후 동작, 검증 결과와 한계, 호스트 의존 관계를 적고 별도 리뷰 에이전트의 검토를 받는다. 문서·워크플로 PR도 동일하다.

- 전체 판단은 PR comment 또는 review 본문에, 코드 지적은 해당 줄의 review comment에 남긴다.
- 구현자는 기존 스레드에 reply로 수용·거부·별도 작업 판정과 근거를 적는다. 수정한 커밋과 검증 결과도 연결한다.
- 수정 후 재검토는 변경분과 관련 회귀에 집중한다. 해결 확인 또는 근거 있는 거부로 논의가 종결되면 thread resolve를 사용한다.
- 최신 head에 조치할 미결 항목이 없고 필요한 검증이 통과한 뒤 머지한다. 첫 리뷰에 지적이 없다면 억지로 반복하지 않는다.

실제 계약 위반·데이터 손실·보안 문제는 수정한다. 지원하지 않는 흐름이나 사소한 엣지케이스를 위해 큰 계층·잠금 체계·리팩터를 요구하는 과도한 리뷰는 범위·발생 조건·비용 대비 효과를 근거로 거부한다. 리뷰 종료를 위해 필요한 기능을 제외하거나 리뷰를 계속하기 위해 새 요구를 추가하지 않는다.

호스트는 머지한 공개 포크 커밋을 명시적으로 고정하고 해당 커밋으로 검증한다. 소스·고지·재결합 방법을 유지하며 AAR 의존성 선언만으로 배포 요건을 충족했다고 간주하지 않는다.

## 호스트 로깅 설정 인수

호스트는 `AmethystRuntime.startPreparation` 또는 `prepareGame`의 `jvmArguments`로 `-Dlog4j.configurationFile=<경로>`를 전달할 수 있다. 게임 실행 인수를 조립할 때 호스트가 지정한 이 속성은 런타임·버전에서 선택한 기본 log4j 설정을 대체한다. 호스트 배열 안에 같은 속성이 여러 번 있으면 마지막 항목 하나를 남기며, 공백이 있는 경로도 인수 하나로 유지한다. `-Dlog4j.configurationFile` 또는 `-Dlog4j.configurationFile=`처럼 빈 값을 명시한 경우도 같은 규칙을 따른다.

호스트가 해당 속성을 지정하지 않으면 기존 보안 XML 우선 선택을 유지한다. 다른 JVM 인수의 값·순서나 `-Dlog4j2.formatMsgNoLookups=true` 보정은 이 처리에서 바꾸지 않는다. 이 규칙의 대상은 런타임·버전 인수와 호스트 배열이며, 별도의 upstream 사용자 JVM 문자열 전체를 정리하는 기능은 아니다. 설정 파일의 내용·설치·게임 버전 호환성은 호스트가 관리하며, 인수 결합 테스트만으로 실제 게임 로깅 검증을 대신하지 않는다.

## 호스트 서버 접속 인수

호스트는 기존 `AmethystRuntime.startPreparation` 또는 `prepareGame`의 `gameArguments`에 `--server`, 호스트 이름, `--port`, 포트를 배열 요소로 전달할 수 있다. 포트를 생략한 기존 `--server` 요청도 유지하며 기본 포트는 25565다. 실제 실행 시 부모와 병합된 게임 메타데이터의 `arguments.game`에 `--quickPlayMultiplayer`가 있으면 이 한 쌍을 `--quickPlayMultiplayer`, `host:port`로 변환한다. IPv6는 `[address]:port`로 전달한다. 해당 기능이 없으면 기존 server/port 인수를 유지한다. 로더 이름이나 릴리스·스냅샷 버전 문자열을 추측하지 않는다.

기능 확인은 메타데이터의 직접 문자열 또는 feature 객체의 `value` 문자열/목록에서 정확한 인수 이름을 찾는다. 기존 게임 인수 조립기가 건너뛰는 feature 객체도 확인하지만, 모든 feature 규칙을 처리하는 범용 해석기는 추가하지 않는다. 이미 지정한 `--quickPlayMultiplayer`와 그 외 호스트 인수의 값·상대 순서는 유지한다. 호스트 접속 옵션의 중복, 값 누락, 서버 없는 port 지정, legacy와 modern의 동시 지정은 오류다. 포트는 1–65535이며 호스트는 URI host 구문에 맞는 DNS 이름 또는 IP 주소다.

바로 접속 ON/OFF, 주소 선택·파싱, DNS SRV와 접속 실패 정책은 호스트에서 결정한다. 라이브러리는 접속 인수를 받지 않았을 때 새 주소를 만들어 넣지 않는다. 기존 준비 API·프로필 저장 형식은 유지하며 인수 테스트나 AAR 빌드로 실제 게임 접속 성공을 주장하지 않는다.


## 저장 계정 관리

`AmethystRuntime.accounts(context)`는 저장한 계정의 `AccountSummary` 목록을 반환한다. `id`는 기존 JSON 파일의 저장 키이며 Minecraft UUID인 `profileId`와 다르다. 호스트는 이를 불투명 키로 취급하고 `selectAccount(context, id)` 또는 `deleteAccount(context, id)`에 그대로 전달한다. 요약에는 이름·프로필 ID·Microsoft/demo 구분·현재 선택 여부만 포함하며 토큰·계정 JSON·인증 유효성은 노출하지 않는다. Microsoft/demo/로컬 계정을 표시할지는 호스트가 결정한다.

목록은 저장 키 순서의 변경 불가능한 snapshot이다. 폴더가 아직 없으면 빈 목록이며 조회를 위해 폴더를 만들지 않는다. 일반 `.json` 파일만 읽고 디렉터리·심볼릭 링크·관련 없는 파일은 제외한다. 계정 JSON 형식이나 읽기에 오류가 있으면 예외를 반환하며 빈 목록으로 성공 처리하지 않는다. JSON 파서의 원문 미리보기·원인 예외는 호스트에 전달하지 않는다.

선택은 존재하고 읽을 수 있는 저장 계정에 대해서만 성공한다. 선택 자체는 silent refresh나 네트워크 인증을 시작하지 않는다. 삭제는 지정한 로컬 계정 파일만 제거하며 현재 계정을 지우면 선택을 해제한다. 다른 계정을 자동 선택하지 않는다. 파일 삭제가 실패하면 현재 선택을 유지한다. 이미 알려진 저장 키로 손상된 파일을 제거할 수는 있지만 경로·상위 폴더·링크를 통해 다른 파일을 삭제할 수는 없다. 기존 username 기반 저장 파일과 선택 preference를 유지하며 UUID 파일명으로 이전하지 않는다.

이 API는 파일 IO를 수행하므로 호스트가 작업 스레드에서 호출하고, 준비·인증·게임 실행 중 계정 변경을 UI 작업 가드로 막는다. 런타임에 세션 lease나 호스트 서비스 정책을 추가하지 않는다. 기존 로그인·Device Code API는 그대로이며 OAuth 결과·silent refresh 개선 및 호스트 계정 화면 연결은 별도 변경이다. JVM 파일 계약과 Android의 공개 API·실제 preference 연결을 검증하며 실제 Microsoft 로그인 성공을 대신하지 않는다.
