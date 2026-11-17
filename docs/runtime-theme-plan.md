# 런타임 라이트·다크 테마 적용 계획

[Amethyst #14](https://github.com/NamuLauncher/Amethyst-Android/issues/14)는 빠른 설정을 포함한 런타임 UI가 기기 테마에 따라 배경과 전경을 함께 바꾸도록 요청한다. 호스트의 Compose UI 변경과 통합 검증은 [namu-android #39](https://github.com/NamuLauncher/namu-android/issues/39)에서 추적한다. 제보 기기의 테마 상태와 실제 재현 조건은 미확인이다.

## 구현 결과

- 게임·설치·컨트롤 편집·계정 Activity에 런타임 DayNight 테마를 명시했다. `values`와 `values-night`에서 표면, 글자, 비활성, 선택, 오류 색상을 함께 정의한다. 앱의 콘텐츠 색상은 Android의 [다크 테마 지침](https://developer.android.com/develop/ui/views/theming/darktheme)을 따라 상태 목록과 리소스로 적용한다.
- 게임·설치·컨트롤 편집 화면은 `uiMode` 변경을 직접 처리한다. 빠른 설정, 편집 패널, 로그, 메뉴, 열린 런타임 대화상자의 색상을 기존 뷰에 다시 적용하므로 입력, 임시 설정값과 게임 렌더링 뷰가 유지된다. 새로 그리는 메뉴 행은 현재 팔레트를 적용한다. 강제 영어 설정의 Activity 구성은 언어만 덮어쓴다.
- 컨트롤 편집기의 미저장 변경, 설치 진행 대화상자, 재사용되는 메뉴 행의 색상이 유지되는 경로를 독립 리뷰에서 보완했다. 실기기와 호스트에서 실제 게임을 기동해 렌더링까지 확인하는 검증은 남아 있다.

## 구현 검증

- `:app_pojavlauncher:assembleDebug`와 `:app_pojavlauncher:assembleDebugAndroidTest`가 통과했다. API 35 에뮬레이터에서 `RuntimeThemeTest`와 `ApplicationContextTest`의 계측 3개가 통과했다. 시스템 설정을 바꿔 라이트 → 다크 → 라이트 전환을 확인했으며, 같은 빠른 설정 뷰와 열린 대화상자가 색상을 갱신하고 입력값과 취소 전 임시 설정값을 보존했다. 강제 영어 설정에서도 전환이 적용됐다.
- namu-android #39 작업본의 임시 복사본을 런타임과 함께 빌드해 `:app:processDebugResources`를 통과했다. 호스트 병합 manifest에서 게임·설치·편집·계정 Activity의 런타임 테마와 `:game` 프로세스 선언을 확인했다.
- 한국어로 설정된 Galaxy 실기기(API 37)에서 다크 모드로 전환할 때 Activity 리소스 언어가 한국어로 돌아오는 것을 확인했다. Activity 생성 직후와 구성 변경 직후에 기존 강제 영어 처리를 다시 적용했다. 수정 후 같은 기기에서 계측 3개가 통과했고, 초기 라이트 모드와 양방향 전환에서 영어·색상·편집값을 확인했다.

## 코드에서 확인한 내용

- `values/styles.xml`과 `values-land/styles.xml`의 `AppTheme`은 고정 다크 AppCompat 테마다. 라이브러리 manifest에서 일부 Activity는 애플리케이션 테마를 상속하므로 호스트의 manifest 병합 결과에 따라 런타임에 적용되는 테마가 달라질 수 있다.
- `dialog_side_dialog.xml`의 배경은 `background_control_editor.xml`을 통해 고정 `background_app`을 사용한다. `SideDialogView.inflateLayout()`은 같은 배경을 테마 인자 없이 다시 설정하고, `dialog_quick_setting.xml`의 항목명과 수치는 위젯의 기본 글자색을 사용한다. 따라서 최종 테마에 따라 고정 배경과 글자색이 어긋날 수 있다.
- 게임의 `MainActivity`와 JVM 설치 화면의 `JavaGUILauncherActivity`는 `uiMode` 변경을 직접 처리하도록 선언한다. `MainActivity.onConfigurationChanged()`는 화면 크기와 컨트롤 배치를 갱신한다. 이미 열린 뷰의 색상을 다시 적용하는 경로는 없다.

## 구현 순서

1. **테마 진입점을 정리한다.** 런타임 Activity에 시스템 모드를 따르는 AppCompat DayNight 테마를 적용하고, 창 설정은 공통 테마에 모은다. 가로 리소스는 글자 크기와 레이아웃 차이만 정의한다. 오류·종료 창도 확인한다. 병합 manifest에서 호스트 애플리케이션 테마와 개별 런타임 Activity의 테마를 확인한다. 게임은 `:game`에서 실행되므로 해당 프로세스의 `Configuration.uiMode`에서도 같은 정책이 적용되어야 한다.
2. **배경과 전경을 함께 바꾼다.** 위젯의 테마 기본값을 우선 사용하고, 앱이 직접 그리는 표면·텍스트·아이콘·구분선·오류 표시에는 의미별 속성과 상태별 색상 목록을 사용한다. 공통 측면 패널의 중복 배경 설정을 제거해 테마가 적용된 Context에서 배경과 내용을 함께 inflate한다. 빠른 설정의 항목명·수치·스위치·슬라이더·확인/취소에서 시작해 인게임 메뉴와 로그, 컨트롤 편집·색상 선택·입력칸, 공통 확인·오류·종료 창, 호스트가 호출하는 계정 화면을 확인한다. 기존 공용 리소스를 쓰는 화면도 전환 영향을 확인한다. 게임 렌더링 배경, 사용자 지정 컨트롤 색상, 색상 선택기의 미리보기는 각자의 기능에 맞는 색상을 유지한다.
3. **실행 중 모드 변경을 처리한다.** `UI_MODE_NIGHT_MASK`가 바뀌면 최신 테마가 적용된 Context에서 이미 표시 중인 Android UI의 배경·텍스트·틴트·drawable을 다시 적용한다. 다시 inflate해야 하는 경우 해당 UI 영역으로 제한하고 편집 중인 값과 리스너를 복원한다. 게임·설치 Activity와 렌더링 Surface의 수명을 유지한다. 빠른 설정에서 임시로 바꾼 값, 확인 시 저장, 취소 시 롤백이 테마 전환 뒤에도 동일하게 동작하도록 검증한다.

## 완료 확인

- 라이트와 다크에서 각각 새로 실행해 빠른 설정의 해상도 비율·마우스 속도·길게 누르기 반응 시간, 스위치·슬라이더·확인/취소를 확인한다. 메뉴·공통 창·입력칸·로그의 배경, 글자와 힌트, 아이콘을 일반·선택/체크·비활성·오류 상태에서 확인한다.
- 실제 게임과 빠른 설정 패널을 포그라운드에 둔 채 에뮬레이터의 `adb shell cmd uimode night yes/no` 등으로 라이트 → 다크 → 라이트 전환을 검증한다. 열린 UI의 색상, 편집 중인 값과 저장/취소 동작, 같은 실행 ID·PID에서 렌더링이 이어지는지 확인한다. 시스템 설정 화면으로 이동하면 `MainActivity.onPause()`에서 빠른 설정을 취소하므로, 이 경로에서는 게임에 돌아와 패널을 다시 열고 새 모드가 적용되는지 확인한다. 앱 재실행, 가로 화면, 설치 화면의 구성 변경 경로도 확인한다.
- 기존 Runtime library CI로 테스트와 AAR 빌드를 확인한다. 호스트가 변경된 런타임 커밋을 고정한 통합 빌드에서 Compose 화면과 런타임 Activity가 양쪽 기기 모드 및 실행 중 전환에 일관되게 반응하는지 확인한다. 빌드·자동 검사와 에뮬레이터 또는 실기기에서 확인한 결과를 각각 기록한다.
