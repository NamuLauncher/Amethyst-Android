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
