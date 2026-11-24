//
// Created by maks on 15.01.2025.
//

#ifndef POJAVLAUNCHER_STDIO_IS_H
#define POJAVLAUNCHER_STDIO_IS_H

#include <stdbool.h>
#include <stddef.h>

_Noreturn void nominal_exit(int code, bool is_signal);

// 로그 스레드가 읽은 원본 바이트를 받는다. EOF에서는 data가 NULL이다.
typedef void (*amethyst_output_handler)(const char* data, size_t length);

// 아래 함수는 AuthYou Go(libauthyou.so)가 libpojavexec.so를 링크해 호출하는 공개 함수다.
// 선언을 바꾸면 AuthYou2Server의 client-android/include/authyou_bridge.h도 함께 바꾼다.

// stdout·stderr를 파이프로 연결하고 로그 스레드를 시작한다. 한 실행 경로에서 한 번만 성공한다.
// 성공하면 0, 실패하면 errno 값을 반환한다.
int amethyst_begin_output(amethyst_output_handler handler);

// C 표준 입출력 버퍼를 비우고 FD 1·2를 복구한 뒤 로그 스레드가 남은 출력을 넘기고 끝날 때까지 기다린다.
void amethyst_end_logging(void);

// UTF-8 로그를 실행 파일에 기록한다. 호출자가 필요한 줄바꿈을 포함한다.
void amethyst_write_log(const char *text, size_t length);

// 이미 열린 파일에 잠금 없이 치명 시그널 진단을 쓴다.
void amethyst_write_crash_log(const char *text, size_t length);

// exit()에서도 남은 게임 출력을 처리한 뒤 종료를 통지한다.
void amethyst_finish_output_on_exit(void);

#endif //POJAVLAUNCHER_STDIO_IS_H
