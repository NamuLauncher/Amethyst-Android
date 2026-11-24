#define _GNU_SOURCE

#include <jni.h>
#include <sys/types.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <android/log.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

// 게임 출력 파이프. 기본 Amethyst 실행 경로와 AuthYou 실행 경로가 같은 구현을 쓰고
// 로그 스레드가 읽은 바이트를 넘길 처리 함수만 다르다.
static int pfd[2] = {-1, -1};
static int original_output_fds[2] = {-1, -1};
static int drain_fd = -1;
static pthread_t logger;
static _Atomic bool logger_running = false;
static amethyst_output_handler output_handler;
static _Atomic bool output_started = false;
static _Atomic bool output_ended = false;

static _Atomic int log_fd = -1;
static pthread_mutex_t log_lock = PTHREAD_MUTEX_INITIALIZER;

#define LOG_READ_BUFFER_SIZE 2048
#define MAX_GAME_LOG_LINE_BYTES (64 * 1024)

static void write_log(const char *text, size_t length, int priority) {
    int fd = atomic_load(&log_fd);
    while (fd >= 0 && length > 0) {
        ssize_t written = write(fd, text, length);
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written <= 0) {
            break;
        }
        text += written;
        length -= (size_t)written;
    }

    // logcat 한 건의 크기 제한 안에서 남은 오류나 출력을 기록한다.
    while (length > 0) {
        size_t count = length > LOG_READ_BUFFER_SIZE ? LOG_READ_BUFFER_SIZE : length;
        __android_log_print(priority, "AmethystGame", "%.*s", (int)count, text);
        text += count;
        length -= count;
    }
}

void amethyst_write_log(const char *text, size_t length) {
    if (length == 0) {
        return;
    }

    pthread_mutex_lock(&log_lock);
    write_log(text, length, ANDROID_LOG_INFO);
    pthread_mutex_unlock(&log_lock);
}

void amethyst_write_crash_log(const char *text, size_t length) {
    // 크래시 난 스레드가 log_lock을 잡고 있어도 기록할 수 있어야 한다.
    write_log(text, length, ANDROID_LOG_ERROR);
}

static void record_game_line(const char *line, size_t length, bool oversized) {
    if (oversized) {
        static const char message[] = "[Amethyst] Game log line exceeded 64 KiB\n";
        amethyst_write_log(message, sizeof(message) - 1);
    } else {
        amethyst_write_log(line, length);
    }
}

static void *logger_thread(void *unused) {
    (void)unused;
    char chunk[LOG_READ_BUFFER_SIZE];
    char line[MAX_GAME_LOG_LINE_BYTES + 1];
    size_t used = 0;
    bool oversized = false;

    struct pollfd fds[] = {
        {.fd = pfd[0], .events = POLLIN},
        {.fd = drain_fd, .events = POLLIN},
    };
    int remaining = -1;
    while (remaining != 0) {
        if (remaining < 0) {
            int ready = poll(fds, 2, -1);
            if (ready < 0 && errno == EINTR) {
                continue;
            }
            if (ready < 0) {
                break;
            }
            if (fds[1].revents & POLLIN) {
                // 자식 프로세스가 상속한 stdout을 열고 있어도 종료 시점에 남은 출력만 비운다.
                if (ioctl(pfd[0], FIONREAD, &remaining) < 0 || remaining == 0) {
                    break;
                }
            }
        }

        size_t limit = sizeof(chunk);
        if (remaining > 0 && (size_t)remaining < limit) {
            limit = (size_t)remaining;
        }
        ssize_t count = read(pfd[0], chunk, limit);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count <= 0) {
            break;
        }
        if (remaining > 0) {
            remaining -= (int)count;
        }

        for (ssize_t index = 0; index < count; index++) {
            char byte = chunk[index];
            if (used < MAX_GAME_LOG_LINE_BYTES || (used == MAX_GAME_LOG_LINE_BYTES && byte == '\n')) {
                line[used++] = byte;
            } else {
                oversized = true;
            }
            if (byte == '\n') {
                record_game_line(line, used, oversized);
                used = 0;
                oversized = false;
            }
        }
        if (output_handler != NULL) {
            output_handler(chunk, (size_t)count);
        }
    }

    if (used > 0) {
        line[used++] = '\n';
        record_game_line(line, used, oversized);
    }
    if (output_handler != NULL) {
        output_handler(NULL, 0);
    }
    close(pfd[0]);
    pfd[0] = -1;
    return NULL;
}

static void close_if_open(int* fd) {
    if(*fd != -1) {
        close(*fd);
        *fd = -1;
    }
}

int amethyst_begin_output(amethyst_output_handler handler) {
    if(output_started) {
        return EALREADY;
    }

    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    if(pipe2(pfd, O_CLOEXEC) != 0) {
        int error = errno;
        pfd[0] = -1;
        pfd[1] = -1;
        return error;
    }

    drain_fd = eventfd(0, EFD_CLOEXEC);
    if (drain_fd < 0) {
        int error = errno;
        close_if_open(&pfd[0]);
        close_if_open(&pfd[1]);
        return error;
    }

    // endLogging()에서 FD 1·2를 원래 대상으로 되돌리기 위해 보관한다.
    original_output_fds[0] = fcntl(STDOUT_FILENO, F_DUPFD_CLOEXEC, 0);
    original_output_fds[1] = fcntl(STDERR_FILENO, F_DUPFD_CLOEXEC, 0);
    if(original_output_fds[0] == -1 || original_output_fds[1] == -1) {
        int error = errno;
        close_if_open(&original_output_fds[0]);
        close_if_open(&original_output_fds[1]);
        close_if_open(&pfd[0]);
        close_if_open(&pfd[1]);
        close_if_open(&drain_fd);
        return error;
    }

    /* redirect stdout and stderr */
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    output_handler = handler;
    int result = pthread_create(&logger, 0, logger_thread, 0);
    if(result != 0) {
        dup2(original_output_fds[0], 1);
        dup2(original_output_fds[1], 2);
        close_if_open(&original_output_fds[0]);
        close_if_open(&original_output_fds[1]);
        close_if_open(&pfd[0]);
        close_if_open(&pfd[1]);
        close_if_open(&drain_fd);
        return result;
    }

    logger_running = true;
    output_started = true;
    return 0;
}

void amethyst_end_logging(void) {
    if (!output_started || atomic_exchange(&output_ended, true)) {
        return;
    }

    // 로그 스레드가 읽는 동안 C 표준 입출력 버퍼에 남은 바이트를 파이프에 쓴다.
    fflush(stdout);
    fflush(stderr);

    dup2(original_output_fds[0], 1);
    dup2(original_output_fds[1], 2);
    close_if_open(&original_output_fds[0]);
    close_if_open(&original_output_fds[1]);

    close_if_open(&pfd[1]);
    uint64_t wake = 1;
    while (write(drain_fd, &wake, sizeof(wake)) < 0 && errno == EINTR) {
    }
    pthread_join(logger, NULL);
    close_if_open(&drain_fd);
    logger_running = false;
}

void amethyst_finish_output_on_exit(void) {
    if (logger_running && !pthread_equal(pthread_self(), logger)) {
        amethyst_end_logging();
    }
}

JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_Logger_openLogFile(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    if (atomic_load(&log_fd) >= 0) {
        return JNI_TRUE;
    }
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    if (file == NULL) {
        return JNI_FALSE;
    }
    // AuthYou 크래시 가드가 종료 직전까지 쓸 수 있게 FD는 프로세스 수명 동안 보관한다.
    int fd = open(file, O_WRONLY | O_CREAT | O_APPEND | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    int error = errno;
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "AmethystGame", "Open runtime log failed: %s", strerror(error));
        return JNI_FALSE;
    }
    atomic_store(&log_fd, fd);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_beginOutput(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    int result = amethyst_begin_output(NULL);
    if (result != 0 && result != EALREADY) {
        __android_log_print(ANDROID_LOG_ERROR, "AmethystGame", "Redirect game output failed: %s", strerror(result));
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_endLogging(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    amethyst_end_logging();
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);
    if(errorCode == JNI_EDETACHED) {
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);
    }
    if(errorCode != JNI_OK) {
        // Step on a landmine and die, since we can't invoke the Dalvik exit without attaching to
        // Dalvik.
        // I mean, if Zygote can do that, why can't I?
        killpg(getpgrp(), SIGTERM);
    }
    // 정상 종료도 호스트에 전달하고 기존 오류 화면 여부는 Java 경계에서 결정한다.
    (*env)->CallStaticVoidMethod(
            env,
            exitTrap_exitClass,
            exitTrap_staticMethod,
            exitTrap_ctx,
            code,
            is_signal
    );
    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_appendUtf8(JNIEnv *env, jclass clazz, jbyteArray text) {
    (void)clazz;
    jsize length = (*env)->GetArrayLength(env, text);
    jbyte *bytes = (*env)->GetByteArrayElements(env, text, NULL);
    if (bytes != NULL) {
        amethyst_write_log((const char *)bytes, (size_t)length);
        (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}
