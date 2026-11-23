#ifndef AUTHYOU_LAUNCH_H
#define AUTHYOU_LAUNCH_H

#include <stddef.h>
#include <stdint.h>

enum { AUTHYOU_START_FAILED = 91 };

typedef void (*AuthYouOutput)(uintptr_t handle, const char *bytes, size_t size);

typedef struct {
    int (*has_listener)(void);
    void (*log)(const char *bytes, size_t size);
    int (*begin_logging)(AuthYouOutput output, uintptr_t handle);
    void (*end_logging)(void);
    int (*launch_jvm)(int argc, char **argv);
} AuthYouHost;

typedef struct {
    const char *server_id;
    const char *user_id;
    const char *host;
    const char *base_directory;
    const char *file_set_id;
} AuthYouSessionConfig;

#endif
