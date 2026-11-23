#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <environ/environ.h>

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
static pthread_t logger;
static _Atomic bool logger_running = false;
static amethyst_output_handler output_handler;
static bool output_started = false;
static bool output_ended = false;

static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;

#define LOG_READ_BUFFER_SIZE 2049

// 기존 Amethyst 로그 필터. buf는 NUL로 끝나야 한다.
static bool passes_log_filter(const char* buf) {
    return strstr(buf, "Session ID is") == NULL;
}

static bool recordBuffer(char* buf, ssize_t len) {
    if(!passes_log_filter(buf)) return false;
    if(latestlog_fd != -1) {
        write(latestlog_fd, buf, len);
        fdatasync(latestlog_fd);
    }
    return true;
}

static void call_log_listener(JNIEnv* env, const char* text) {
    jobject listener = logListener;
    if(listener == NULL) {
        return;
    }

    jstring writeString = (*env)->NewStringUTF(env, text);
    if(writeString == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }

    (*env)->CallVoidMethod(env, listener, logger_onEventLogged, writeString);
    if((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, writeString);
}

// 기본 실행 경로의 처리 함수. 필터를 적용해 로그 파일에 기록하고 리스너에 전달한다.
static void record_game_output(const char* data, size_t length) {
    char buf[LOG_READ_BUFFER_SIZE];
    if(length > sizeof(buf) - 1) {
        length = sizeof(buf) - 1;
    }
    memcpy(buf, data, length);
    buf[length] = 0x00;

    bool shouldRecordString = recordBuffer(buf, (ssize_t) length); //record with newline int latestlog
    if(length > 0 && buf[length-1]=='\n') {
        buf[length-1]=0x00; //truncate
    }
    if(shouldRecordString) {
        amethyst_log_event(buf, strlen(buf)); //send to app without newline
    }
}

static void *logger_thread() {
    JNIEnv *env;
    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    (*dvm)->AttachCurrentThread(dvm, &env, NULL);
    ssize_t  rsize;
    char buf[LOG_READ_BUFFER_SIZE - 1];
    // 쓰기 끝이 모두 닫히면 남은 바이트를 넘긴 뒤 EOF(0)를 받는다.
    for(;;) {
        rsize = read(pfd[0], buf, sizeof(buf));
        if(rsize < 0 && errno == EINTR) {
            continue;
        }
        if(rsize <= 0) {
            break;
        }
        output_handler(buf, (size_t) rsize);
    }
    close(pfd[0]);
    pfd[0] = -1;
    (*dvm)->DetachCurrentThread(dvm);
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

    if(pipe(pfd) != 0) {
        int error = errno;
        pfd[0] = -1;
        pfd[1] = -1;
        return error;
    }

    // endLogging()에서 FD 1·2를 원래 대상으로 되돌리기 위해 보관한다.
    original_output_fds[0] = dup(1);
    original_output_fds[1] = dup(2);
    if(original_output_fds[0] == -1 || original_output_fds[1] == -1) {
        int error = errno;
        close_if_open(&original_output_fds[0]);
        close_if_open(&original_output_fds[1]);
        close_if_open(&pfd[0]);
        close_if_open(&pfd[1]);
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
        return result;
    }

    logger_running = true;
    output_started = true;
    return 0;
}

void amethyst_end_logging(void) {
    if(!output_started || output_ended) {
        return;
    }
    output_ended = true;

    // 로그 스레드가 읽는 동안 C 표준 입출력 버퍼에 남은 바이트를 파이프에 쓴다.
    fflush(stdout);
    fflush(stderr);

    dup2(original_output_fds[0], 1);
    dup2(original_output_fds[1], 2);
    close_if_open(&original_output_fds[0]);
    close_if_open(&original_output_fds[1]);

    // 마지막 쓰기 끝을 닫으면 로그 스레드가 남은 출력을 처리하고 EOF로 끝난다.
    close_if_open(&pfd[1]);
    pthread_join(logger, NULL);
    logger_running = false;
}

void amethyst_flush_output_for_exit(void) {
    int fd = pfd[1];
    if(fd == -1 || !logger_running || pthread_equal(pthread_self(), logger)) {
        return;
    }

    fflush(stdout);
    fflush(stderr);

    // 줄바꿈 없이 끝난 마지막 줄을 출력 처리 함수가 줄로 완성할 수 있게 한다.
    ssize_t written;
    do {
        written = write(fd, "\n", 1);
    } while(written < 0 && errno == EINTR);

    sleep(1);
}

bool amethyst_log_has_listener(void) {
    return logListener != NULL;
}

bool amethyst_log_passes_filter(const char* data, size_t length) {
    char stack_buffer[LOG_READ_BUFFER_SIZE];
    char* buf = stack_buffer;
    if(length >= sizeof(stack_buffer)) {
        buf = malloc(length + 1);
        if(buf == NULL) {
            return false;
        }
    }

    if(length > 0) {
        memcpy(buf, data, length);
    }
    buf[length] = 0x00;
    bool passes = passes_log_filter(buf);

    if(buf != stack_buffer) {
        free(buf);
    }
    return passes;
}

void amethyst_log_event(const char* text, size_t length) {
    if(logListener == NULL) {
        return;
    }

    JavaVM* dvm = pojav_environ->dalvikJavaVMPtr;
    JNIEnv* env;
    bool attached = false;
    jint result = (*dvm)->GetEnv(dvm, (void**) &env, JNI_VERSION_1_6);
    if(result == JNI_EDETACHED) {
        if((*dvm)->AttachCurrentThread(dvm, &env, NULL) != JNI_OK) {
            return;
        }
        // AuthYou Go의 스레드처럼 ART에 등록되지 않은 스레드는 호출 뒤 바로 해제한다.
        attached = true;
    } else if(result != JNI_OK) {
        return;
    }

    char* buf = malloc(length + 1);
    if(buf != NULL) {
        if(length > 0) {
            memcpy(buf, text, length);
        }
        buf[length] = 0x00;
        call_log_listener(env, buf);
        free(buf);
    }

    if(attached) {
        (*dvm)->DetachCurrentThread(dvm);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if(latestlog_fd != -1) {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
    if(latestlog_fd == -1) {
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }

    // 같은 프로세스에서 다시 호출되면 로그 파일만 바꾸고 출력 파이프는 그대로 쓴다.
    if(output_started && output_handler == record_game_output) {
        return;
    }

    /* create the pipe, redirect stdout and stderr and spawn the logging thread */
    int result = amethyst_begin_output(record_game_output);
    if(result != 0) {
        close(latestlog_fd);
        latestlog_fd = -1;
        (*env)->ThrowNew(env, ioeClass, strerror(result));
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_endLogging(__attribute((unused)) JNIEnv *env, __attribute((unused)) jclass clazz) {
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

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    // AuthYou 실행 경로는 Logger.begin()을 호출하지 않으므로 리스너를 등록할 때 메서드를 찾는다.
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}
