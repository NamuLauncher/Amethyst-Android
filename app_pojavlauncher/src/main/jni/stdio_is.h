//
// Created by maks on 15.01.2025.
//

#ifndef POJAVLAUNCHER_STDIO_IS_H
#define POJAVLAUNCHER_STDIO_IS_H

#include <stdbool.h>
#include <stddef.h>

_Noreturn void nominal_exit(int code, bool is_signal);

// 로그 스레드가 read() 한 번으로 읽은 원본 바이트를 받는 처리 함수. data는 NUL로 끝나지 않는다.
typedef void (*amethyst_output_handler)(const char* data, size_t length);

// 아래 함수는 AuthYou C가 libpojavexec.so에서 dlsym()으로 찾는 공개 함수다.

// stdout·stderr를 파이프로 연결하고 로그 스레드를 시작한다. 한 실행 경로에서 한 번만 성공한다.
// 성공하면 0, 실패하면 errno 값을 반환한다.
int amethyst_begin_output(amethyst_output_handler handler);

// C 표준 입출력 버퍼를 비우고 FD 1·2를 복구한 뒤 로그 스레드가 남은 출력을 넘기고 끝날 때까지 기다린다.
void amethyst_end_logging(void);

bool amethyst_log_has_listener(void);
bool amethyst_log_passes_filter(const char* data, size_t length);

// 등록된 리스너의 onEventLogged(text)를 호출한다. ART에 등록되지 않은 스레드에서도 호출할 수 있다.
void amethyst_log_event(const char* text, size_t length);

// exit() 처리 중 로그 파이프가 열려 있으면 출력 버퍼를 비우고 줄바꿈 하나를 쓴 뒤 1초 기다린다.
// 로그 스레드에서 호출하거나 파이프가 닫혀 있으면 아무것도 하지 않는다.
void amethyst_flush_output_for_exit(void);

#endif //POJAVLAUNCHER_STDIO_IS_H
