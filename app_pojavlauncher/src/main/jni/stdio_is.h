//
// Created by maks on 15.01.2025.
//

#ifndef POJAVLAUNCHER_STDIO_IS_H
#define POJAVLAUNCHER_STDIO_IS_H

#include <stdbool.h>
#include "authyou_launch.h"

int game_log_has_listener(void);
void game_log_to_app(const char *bytes, size_t size);
int game_log_begin(AuthYouOutput output, uintptr_t handle);
void game_log_end(void);
void game_log_flush_on_exit(void);

_Noreturn void nominal_exit(int code, bool is_signal);

#endif //POJAVLAUNCHER_STDIO_IS_H
