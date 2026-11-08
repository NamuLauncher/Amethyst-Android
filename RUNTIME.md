# Amethyst Android runtime library

This fork converts `app_pojavlauncher` to an Android library. The public host boundary is `net.kdt.pojavlaunch.runtime.AmethystRuntime`. Upstream Java/JNI implementation and LGPL licensing are retained.

A host application uses `PojavApplication`, calls `initialize`, signs in with `openAccount`, refreshes the session, supplies its game directory/version/renderer to `prepareGame`, then calls `launchGame`. The host owns server metadata and file synchronization. The game runs in its own `:game` process.

Build with JDK 17, Android SDK 35, NDK 27.3.13750724 and CMake 3.22.1. Default ABIs are arm64-v8a and x86_64; set `-PruntimeAbis=x86_64` for emulator artifacts. Local bundled AARs are resolved as external flat-directory dependencies and must be provided to the consuming build as well. This is not a self-contained fat AAR.

Library extraction and runtime integration are in progress. A successful build alone does not prove game startup or LGPL relinking compliance. Hosts must provide applicable notices, library source and a verified relinking mechanism/materials when distributing a combined application.

Hosts may explicitly create an offline profile for local singleplayer testing with `createOfflineAccount`. This does not produce a Microsoft session. The host must keep offline singleplayer arguments separate from authenticated multiplayer. `prepareGame` downloads public version dependencies for the selected profile without requiring the upstream launcher home screen.

## Microsoft Device Code sign-in

Call `startDeviceCodeLogin(Context, DeviceCodeListener)` to start authentication without opening an Activity. The listener receives the user code, verification URI, expiry, Minecraft authentication stage and completion/failure on the main thread. The library polls for authorization, exchanges tokens through Xbox Live/XSTS and Minecraft services, saves the account and selects it on success. Hosts own all Device Code UI, browser opening, clipboard actions and error presentation. Keep the returned `LoginRequest` and call `cancel()` when dismissing the UI or destroying its owner. Browser switching alone should not cancel authentication. Hosts continue to use `accountName` and `refreshAccount`; FISH/server policies are not involved. `openAccount` retains the upstream WebView flow for existing callers.

This uses the existing upstream Xbox OAuth client ID and scope with `login.live.com/oauth20_connect.srf` and `oauth20_token.srf`. Device grants and refresh grants use the same client/scope. No client secret is embedded. The existing client was checked against Microsoft's live device endpoint: code issuance succeeded. End-to-end account authorization/refresh still requires a user with Minecraft Java Edition.

The worker respects the server interval, adds five seconds after `slow_down`, backs off on connection timeout, and stops on expiry, denial or cancellation. Cancelling the request stops polling and suppresses subsequent callbacks. No Device Code Activity or UI is provided by this library. Codes/tokens and token request bodies are not logged. The displayed code is transient and is never stored in the account file. Unit tests use a local HTTP server and a virtual clock, without real accounts.

Protocol references: [Microsoft device authorization](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code), [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628#section-3.5).

## Host JVM arguments

`prepareGame` accepts separate `gameArguments` and `jvmArguments` arrays. Each array element is one argument; do not add shell quoting around paths with spaces. JVM arguments are copied into the saved profile so they reach the separate game process, and appended after generated defaults but before the main class and game arguments. Pass null or an empty array when no extra JVM options are needed.

The host must place the game directory in app-internal storage, for example `Context.getFilesDir()/instances/<id>`. `gameNativeDirectory(File gameDirectory)` returns `<game>/native/<current ABI>`; files are loaded directly there without staging, copying or cleanup. JVM and Android linker search paths use that same game-specific directory. Its final path component is the Android ABI name. Skiko filename selection, library-path and system-theme JVM properties are host policy. Shared/external-storage game folders are outside this contract.

## Game preparation progress

`prepareGame` accepts a `PreparationListener` with `onProgress(GamePreparationProgress)`, `onReady()` and `onFailure(Throwable)`. All callbacks run on the main thread. Progress belongs to one request; it stops after the first terminal callback. Hosts own status text and progress UI.

Progress has a `stage`, `unit`, `completed`, `total` and `cancellable`. Stages cover bundled components, the version list, metadata, Java runtime preparation, game files and native extraction. Units are `ITEMS`, `BYTES` or `UNKNOWN`; `total = -1` means no measurable total is available. These are per-stage counters, not an overall completion percentage. Java runtime preparation and metadata currently report indeterminate progress. Game-file counters include existing verified files as well as downloads; they are not network usage counters. Unknown-size game downloads fall back to item counts. A completed preparation can immediately launch the game without waiting for an artificial 100% event.

Upstream launcher progress reporting remains supported. Host progress is wired directly through the preparation tasks and does not subscribe to global UI progress events. The original `void prepareGame(...)` entry point remains binary-compatible and delegates to the new request API.

## Cancellable preparation

`startPreparation(...)` takes the same arguments and returns `AmethystRuntime.PreparationRequest`. `cancel()` returns whether the current phase accepted the request. A return value of true means cancellation was requested, **not** that preparation has already stopped. Keep the host's operation guard active until `onCancelled()`, `onFailure(...)` or `onReady()` arrives. All terminal callbacks run on the main thread after the request worker and every download worker have stopped accessing files. `PreparationListener.onCancelled()` defaults to `onFailure(CancellationException)` for existing listeners; hosts may override it to display cancellation separately.

Version-list, metadata and game-file phases accept cancellation. Bundled component installation, Java runtime installation and the final JAR copy/native extraction do not support interruption and report `cancellable = false`; requests during those phases return false. A stale progress event may race a phase change, so the host must use the return value of `cancel()` and continue waiting when the request is rejected. Cancelling before the queued worker starts prevents preparation entirely. Once cancellation is accepted, no subsequent phase or ready callback runs, and queued progress is suppressed. Cancellation after the worker has finished returns false even if its terminal callback is still queued.

HTTP transfers check interruption before and after reads and before publishing a completed file. A blocked network call may take its current connect/read timeout (10 seconds) to return; cancellation is not an immediate-completion guarantee or a fixed overall deadline for DNS, hashing and filesystem work. Downloads use a temporary sibling file and replace the destination only after a complete response, so failed/cancelled partial downloads are not mistaken for valid files on the next attempt. An already completed file may remain after cancellation. Download failure also interrupts and joins remaining workers before reporting failure. This API does not provide product-level locks or support simultaneous preparation requests; the host must continue enforcing one active preparation/game.

JVM tests cover accepted cancellation before/during work, non-cancellable phases, late cancellation, callback delivery, interrupted/failed worker-group cleanup and mock-HTTP partial-file handling. These checks do not by themselves prove Android component/Java installation or real game startup.

## 실행 식별자·로그·종료 결과

`launchGame(Activity, String runId, File runDirectory)`는 호스트가 지정한 새 실행을 기록한다. ID는 영문·숫자·밑줄·하이픈 1–128자이며 실행마다 다른 값을 사용한다. 폴더는 `Context.getFilesDir()` 아래의 비어 있는 하위 폴더여야 한다. 기존 실행 기록이나 로그를 재사용하지 않는다. 이미 실행 중인 게임으로 돌아갈 때는 기존 `launchGame(Activity)`를 사용한다. 기존 준비·실행 메서드의 시그니처는 유지한다.

게임 프로세스는 다음 파일을 기록한다. 보관·제거·화면 표시·업로드 정책은 호스트의 책임이다.

- `state.properties`: format 1의 실행 ID, 단계, 프로세스 ID, 관측 시각, 종료 코드/signal 또는 실패 타입. 같은 폴더의 임시 파일을 원자적으로 교체하므로 다른 프로세스가 SharedPreferences 캐시에 의존하지 않고 읽을 수 있다.
- `runtime.log`: 해당 실행의 stdout/stderr 로그. 같은 프로세스에서 화면이 재생성되어도 로그를 다시 자르지 않는다.
- `hs_err_pid%p.log`: 호스트 JVM 옵션 뒤에 전달하는 `-XX:ErrorFile`의 출력 위치. JVM이 해당 진단 파일을 생성하는 경우에만 존재한다.
- `failure.log`: Java 실패의 예외 타입과 호출 프레임. 민감값이 포함될 수 있는 예외 메시지는 쓰지 않으며 원인 체인은 최대 8단계까지 남긴다.

`readGameRun(File)`은 불변 `GameRunSnapshot`을 반환한다. 상태 파일이 없으면 null이고 파일 읽기·형식 오류는 IOException이다. `gameLogFile(File)`·`gameFailureFile(File)`로 진단 파일 위치를 얻고, `gameCrashReports(File)`로 생성된 hs_err 후보를 조회한다. hs_err가 없으면 빈 목록이며 폴더 조회 실패를 빈 목록으로 바꾸지 않는다. 실제 파일 읽기·수집 실패는 호스트가 별도로 표시해야 한다.

관측 단계는 `STARTING`, `JVM_STARTED`, `FIRST_FRAME`, `EXITED`, `FAILED`, `STOPPED`다. `JVM_STARTED`는 네이티브 JVM 호출 직전이고 `FIRST_FRAME`은 TextureView의 실제 첫 프레임 콜백이다. **어느 단계도 Minecraft 로딩 완료나 서버 접속 성공을 뜻하지 않는다.** SurfaceView에는 동등한 프레임 콜백이 없어 FIRST_FRAME을 합성하지 않는다. 호스트는 관측되지 않은 상태를 미확정으로 표시한다.

정상 종료 코드 0·비0·signal은 `EXITED`의 `exitCode`와 `signal`로 구분한다. Java 준비/실행 실패는 `FAILED`와 예외 클래스 이름을 남기고 사용자 정지는 `STOPPED`로 기록한다. 코드가 없는 상황에 0이나 임의 코드를 만들지 않는다. 첫 종료 결과 뒤의 늦은 콜백·프로세스 정리 호출은 그 결과를 덮지 않는다. 상태 파일 게시가 실패하면 메모리의 첫 종료 결과는 유지하지만 다른 프로세스에는 이전 완성본만 남을 수 있다. 호스트는 실제 프로세스가 사라졌는데 종료 기록이 없을 때 결과 미확정으로 다뤄야 한다.

시스템이 종료된 게임 Activity를 다시 만들더라도 다른 PID가 과거 실행 ID를 이어 쓰거나 과거 로그를 덮지 않도록 거부한다. 호스트는 새 실행 ID로 다시 시작한다. 실행 폴더/상태가 잘못되어 호스트 실행을 연결하지 못하면 upstream 오류 화면으로 전환하지 않고 게임 프로세스를 종료한다. 이전 기록 또는 읽기 오류는 호스트에서 확인한다.

상태 변경 시 `ACTION_GAME_RUN_CHANGED`를 호스트 패키지에만 broadcast하고 `EXTRA_RUN_ID`를 포함한다. 호스트는 `ContextCompat.RECEIVER_NOT_EXPORTED` 수신기를 등록한 뒤 자기 실행 ID인지 확인하고 파일을 다시 읽는다. broadcast는 화면 복귀/재생성 시 상태 파일 조회를 대신하지 않는다.

`stopGame(Context, String runId)`는 현재 게임 프로세스에 정지를 요청한다. 실행에 연결된 수신기만 같은 ID의 요청을 처리하며 다른 실행 ID는 무시한다. 반환값 true는 명령 전송 여부이지 종료 완료가 아니다. 호스트는 프로세스가 연결된 상태에서 요청하고 STOPPED 기록과 실제 프로세스 종료를 확인할 때까지 작업 가드를 유지한다. 이 경계는 자기 앱의 `:game` 프로세스만 종료하며 시스템의 Java 프로세스를 검색/종료하지 않는다.

라이브러리가 전체 JVM 인자·사용자 JVM 옵션·환경값을 그대로 출력하던 경로는 제거했다. 상태/Java 실패 파일에도 해당 값을 넣지 않는다. 게임·모드 또는 JVM 자체가 생성하는 runtime.log/hs_err 내용까지 모두 정제됐다는 뜻은 아니다. 호스트는 진단 화면·Sentry·support bundle에 사용하기 전에 필요한 비밀값 제거를 적용해야 한다. 이 PR은 Sentry·업로드·실행 성공 metric이나 보관 정책을 라이브러리에 추가하지 않는다.

JVM 검증은 실제 파일의 독립 reader, 단계·중복/역행·첫 종료 보존, 형식 오류·파일 부재·게시 실패를 확인한다. Android 계측은 별도 `:game` 테스트 Service의 PID·파일·앱 내부 알림·Java 종료 진입점·정지 요청을 확인하며 실제 Minecraft나 JNI JVM을 실행하지 않는다. AAR 빌드와 이 계측만으로 실제 게임 로딩/종료, 전체 포팅 또는 LGPL 재결합 배포를 완료했다고 판단하지 않는다.
