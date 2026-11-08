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

이 API는 파일 IO를 수행하므로 호스트가 작업 스레드에서 호출하고, 준비·인증·게임 실행 중 계정 변경을 UI 작업 가드로 막는다. 런타임에 세션 lease나 호스트 서비스 정책을 추가하지 않는다. 기존 로그인·Device Code API는 유지한다. OAuth 결과·silent refresh는 아래 계약으로 제공하며 호스트 계정 화면은 별도로 연결한다. JVM 파일 계약과 Android의 공개 API·실제 preference 연결을 검증하며 실제 Microsoft 로그인 성공을 대신하지 않는다.

## 게임 JVM 경과시간

`AmethystRuntime.readGameRun(directory)`의 `GameRunSnapshot.jvmElapsedMillis`는 JVM 진입 경계부터 관측한 누적 밀리초다. 준비·다운로드·인증 시간은 포함하지 않는다. `JREUtils`가 `VMLauncher.launchJVM()` 직전에 호출하는 `RuntimeGameSession.onJvmStarted()`에서 0으로 시작하며, 첫 프레임은 시작점을 바꾸지 않는다. 이 관측은 게임 로딩 완료나 서버 접속 성공을 뜻하지 않는다.

게임 프로세스의 `SystemClock.elapsedRealtime()` 차이를 사용하므로 기기의 날짜·시간 변경은 누적값에 영향을 주지 않는다. 기존 `startedAt`·`updatedAt`·`jvmStartedAt`·`firstFrameAt`은 계속 wall-clock 시각이다. 그 차이로 경과시간을 대체해서는 안 된다. JVM 진입 전·구형 파일의 값은 null이며 관측한 0밀리초와 구분한다.

게임 프로세스의 단일 예약 작업이 30초 간격으로 기존 원자적 `state.properties`를 갱신한다. 호스트 Activity가 멈추거나 호스트 프로세스가 재생성돼도 게임 프로세스가 살아 있으면 기록은 계속된다. 기존 실행 변경 broadcast를 사용하며 서비스·백그라운드 작업 큐·호스트의 인스턴스/DB 정책을 추가하지 않는다. 호스트는 작업 스레드에서 상태 파일을 다시 읽고 같은 실행 ID의 누적값을 저장할 수 있다.

최초 정상/비정상 종료·실패·사용자 정지에서 최종값을 고정하고 예약 작업을 종료한다. Java 실패의 진단 파일과 최종 상태 파일을 쓰는 시간은 최종 경과시간에 더하지 않는다. 이후 중복 종료·정지·첫 프레임·JVM 시작·체크포인트는 종료값을 늘리지 않는다. 주기 저장이 실패해도 게임을 중단하지 않으며 다음 주기에 원래 시작점부터 계산한 누적값을 다시 저장한다.

파일 형식은 `format=1`을 유지하고 optional `jvmElapsedMillis`만 추가한다. 이전 파일은 null로 읽으며 음수·정수가 아닌 값·JVM 시작 기록이 없는 명시 값은 오류다. 이전 reader는 알 수 없는 항목을 무시하므로 기존 종료 상태·로그 조회 계약을 유지한다.

SIGKILL·전원 종료 등으로 종료 콜백이 실행되지 않으면 마지막 성공 체크포인트까지만 남는다. 보통 마지막 주기 이후의 시간이 빠질 수 있고 저장 실패가 이어지면 손실 구간이 더 길 수 있다. 최종 저장 실패도 마지막 성공한 파일과 메모리의 관측 결과를 구분하며 가짜 정상 종료를 게시하지 않는다. 프로세스 부재를 발견한 시각까지 시간을 추측해 늘리지 않는다. 실제 Minecraft/JNI 실행 시간·플레이 시간 합산·일별 분할·metric 성공 판정은 이 API의 검증이나 정책에 포함하지 않는다.

JVM 테스트는 단조 시계와 wall clock의 분리, 소수 초 누적, 중복/최종 상태, 저장 실패 후 복구, 구형/잘못된 파일을 확인한다. Android 계측은 기존 별도 게임 프로세스 fixture에서 기본 30초 주기를 실제로 관측하며 호스트 receiver가 없는 동안의 파일 갱신과 정지 후 읽기를 확인한다. 이 fixture는 JVM 자체를 실행하지 않는다.


## OAuth 결과와 계정 갱신

호스트는 `AmethystRuntime.accountIntent(context)`를 Activity Result launcher로 연다. 기존 `openAccount(Activity)`도 같은 upstream OAuth 화면을 연다. 결과는 성공 `Activity.RESULT_OK`, 취소 `Activity.RESULT_CANCELED`, 실패 `Activity.RESULT_FIRST_USER`다. 실패 Intent에는 `AmethystRuntime.EXTRA_AUTH_REASON`으로 `AccountAuthenticationException.Reason.name()` 하나만 넣는다. 호스트는 알 수 없는 값과 없는 extra를 안전한 실패로 처리하고 성공 뒤에는 저장 계정 요약을 다시 조회한다. code·token·응답 본문·Throwable은 결과에 넣지 않는다.

OAuth 화면은 같은 redirect를 중복 교환하지 않는다. 뒤로가기는 browsing 중에는 즉시 취소하고, 교환 중에는 취소를 요청한 뒤 실제 결과를 기다린다. 저장이 시작되어 취소할 수 없으면 저장 결과를 기다린다고 안내한다. 실패 뒤에는 같은 code를 자동 재요청하지 않으며 사용자가 호스트에서 다시 로그인해야 한다. Activity 회전은 ViewModel의 요청과 안전한 상태를 이어받는다. 프로세스가 종료됐다면 인증 시작 여부만 복원하여 취소하고, code를 saved state에 저장하거나 교환을 재시작하지 않는다.

`startAccountRefresh(Context, AuthenticationListener)`는 application Context를 사용한다. 유효한 Minecraft 토큰의 수명이 60초보다 많이 남으면 네트워크 없이 완료한다. 갱신이 필요하면 현재 계정 하나를 한 번 갱신한다. 갱신 정보 누락·인증 거절은 재로그인 필요로, 네트워크·429·서버 장애·계정 제한·게임 미보유·프로필 미설정·잘못된 응답·로컬 저장 실패는 각각의 안전한 reason으로 전달한다. 기존 `refreshAccount(Activity, Completion)`는 새 경로의 호환 adapter다. 새 API의 모든 terminal callback은 메인 스레드에서 전달한다.

Minecraft 토큰의 `expires_in` 양의 정수 수명을 사용하고 overflow와 잘못된 명시 값을 거부한다. 필드가 아예 없을 때만 기존 24시간 값을 유지한다. Microsoft refresh 응답에 새 refresh token이 없으면 기존 값을 유지하며, 새 값이 있으면 함께 저장한다. HTTP 응답 원문은 예외·로그에 넣지 않는다. 응답 크기는 64 KiB로 제한하고 redirect를 자동으로 따라가지 않는다. 기존 profile/entitlement의 제한된 5회 전송 재시도는 유지하되 인증 거절·계정 제한·429는 즉시 반환한다. 호스트는 이 재시도와 별개로 OAuth/게임 실행을 무한 반복하지 않아야 한다.

### 취소와 실제 완료

`AuthenticationRequest.cancel()`은 저장 시작 전에는 true, 저장 중이나 이미 끝난 요청에는 false다. true는 취소 요청의 수락이며 완료가 아니다. 네트워크가 interrupt를 즉시 처리하지 못하면 기존 connect 15초/read 30초 제한 안에서 종료를 기다린다. 호스트는 취소 결과에 관계없이 terminal callback을 기다린 뒤 단일 UI 작업 가드를 해제한다. 취소된 호스트 coroutine은 준비 작업과 마찬가지로 `NonCancellable` 종료 대기를 사용하고, 뒤늦은 성공을 받아 게임 실행을 재개하지 않는다.

요청별 짧은 동기화로 취소와 저장 시작만 직렬화한다. 취소가 먼저 수락되면 이후 계정 저장·선택은 없다. 저장이 먼저 시작되면 취소를 거부하고 파일·선택 처리가 끝난 뒤 성공/실패를 전달한다. 실제 파일 IO 중에는 동기화 잠금을 잡지 않으며 backend session lease나 계정 전역 lock을 추가하지 않는다. `Future.cancel`의 done 상태를 실제 worker 완료로 사용하지 않는다.

일반 뒤로가기와 회전은 위 계약으로 처리한다. 외부에서 Activity/task를 강제로 제거하면 ViewModel 정리 시 취소를 요청하지만, Activity Result만으로 이미 시작한 worker commit의 종료를 호스트가 반드시 기다린다고 보장하지는 않는다. 수락된 취소 뒤 저장 금지 규칙은 이 경우에도 유지한다. 강제 종료 전체를 추적하는 process registry는 도입하지 않았다.

### 저장 슬롯과 기존 API 호환

새 갱신 경로는 원래 선택한 opaque id 파일을 temp+atomic replace로 교체한다. profile UUID가 다른 후보·demo·잘못된 후보는 저장 전에 거부한다. Minecraft 이름이 바뀌어도 같은 슬롯의 이름·토큰만 갱신하고 `selectedVersion`과 선택 id를 유지하므로 옛 토큰 파일을 선택하거나 이름 변경 때 중복 파일을 만들지 않는다.

OAuth는 동일한 profile UUID의 기존 슬롯을 재사용한다. 중복 슬롯이 이미 있으면 현재 선택을 우선하고 그렇지 않으면 정렬된 첫 항목을 사용한다. 신규 계정은 이름을 키로 쓰되 그 이름이 다른 계정이나 파일에 사용 중이면 새 UUID 키를 사용하여 기존 파일을 덮어쓰지 않는다. 전체 파일명 migration이나 기존 중복 파일 병합·삭제는 하지 않는다. 계정 파일 저장 뒤 선택 preference의 동기 commit을 확인한 후 완료를 전달한다. 두 저장소가 전원 차단까지 하나의 transaction이라고 보장하지 않으며 저장 실패 뒤 호스트는 snapshot을 다시 읽어야 한다.

`MicrosoftBackgroundLogin.authenticate`는 라이브러리 내부 조합용 동기 교환으로 저장 전 후보만 반환한다. 새 runtime 경로는 계정 얼굴 캐시를 다운로드하지 않는다. 기존 `performLogin`은 username 기반 저장·얼굴 캐시와 저장 뒤 main callback 계약을 유지하며 Device Code API도 유지한다. 기존 void cancel은 best-effort이며 저장 중 rollback을 약속하지 않는다. upstream 독립 런처의 계정 spinner는 filename==username을 가정하므로 새 opaque 슬롯의 이름·선택·얼굴 표시까지 완전히 호환한다고 주장하지 않는다. runtime 게임 실행과 새 계정 API는 id와 JSON 내부 이름을 분리한다.

### 검증

JVM은 HTTP 교환의 mock 응답, 오류 분류·비밀값 제거·수명 계산, 요청 취소/commit 경합·실제 종료 대기를 확인한다. Android는 격리된 실제 계정 파일·preference와 fake 인증 후보로 유효 토큰 재사용·갱신·이름 변경·실패/취소 보존·OAuth 슬롯 재사용/충돌을 검증한다. LiveData/Lifecycle/ViewModelStore와 fake starter로 OAuth 모델·결과 Intent·회전 재사용을 확인하며 기존 비동기 wrapper의 main 실패 callback도 확인한다. 실제 WebView Activity에서의 Microsoft 로그인이나 실제 토큰 사용 가능성을 이 테스트로 대신하지 않는다. 호스트의 coroutine/UI 연결과 실제 로그인 검증은 후속 작업이다.
