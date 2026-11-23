#define _GNU_SOURCE
#include <jni.h>
#include <android/log.h>
#include <stdatomic.h>
#include <limits.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static int pfd[2] = {-1, -1};
static int saved_stdout = -1;
static int saved_stderr = -1;
static pthread_t logger;
static _Atomic bool output_active;
static AuthYouOutput output_callback;
static uintptr_t output_handle;
static jclass logger_class;
static jmethodID logger_on_event;
static _Atomic bool listener_registered;
static pthread_mutex_t log_file_lock = PTHREAD_MUTEX_INITIALIZER;
static int latestlog_fd = -1;

int game_log_has_listener(void) {
    return atomic_load(&listener_registered);
}

void game_log_to_app(const char *bytes, size_t size) {
    if (!game_log_has_listener() || size > INT_MAX) {
        return;
    }

    JavaVM *vm = pojav_environ->dalvikJavaVMPtr;
    JNIEnv *env = NULL;
    jint result = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    bool attached = result == JNI_EDETACHED;
    if (attached) {
        result = (*vm)->AttachCurrentThread(vm, &env, NULL);
    }
    if (result != JNI_OK) {
        return;
    }

    jbyteArray text = (*env)->NewByteArray(env, (jsize)size);
    if (text != NULL) {
        (*env)->SetByteArrayRegion(env, text, 0, (jsize)size, (const jbyte *)bytes);
        if (!(*env)->ExceptionCheck(env)) {
            (*env)->CallStaticVoidMethod(env, logger_class, logger_on_event, text);
        }
        (*env)->DeleteLocalRef(env, text);
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        __android_log_write(ANDROID_LOG_ERROR, "Amethyst", "Deliver game log to app failed");
    }
    if (attached) {
        (*vm)->DetachCurrentThread(vm);
    }
}

static bool record_buffer(const char *bytes, size_t size) {
    static const char filter[] = "Session ID is";
    if (memmem(bytes, size, filter, sizeof(filter) - 1) != NULL) {
        return false;
    }

    pthread_mutex_lock(&log_file_lock);
    if (latestlog_fd != -1) {
        size_t written = 0;
        while (written < size) {
            ssize_t count = write(latestlog_fd, bytes + written, size - written);
            if (count < 0 && errno == EINTR) {
                continue;
            }
            if (count <= 0) {
                break;
            }
            written += (size_t)count;
        }
        fdatasync(latestlog_fd);
    }
    pthread_mutex_unlock(&log_file_lock);
    return true;
}

static void record_game_output(uintptr_t handle, const char *bytes, size_t size) {
    (void)handle;
    if (record_buffer(bytes, size)) {
        if (size > 0 && bytes[size - 1] == '\n') {
            --size;
        }
        game_log_to_app(bytes, size);
    }
}

static void *logger_thread(void *unused) {
    (void)unused;
    char buffer[2049];
    for (;;) {
        ssize_t size = read(pfd[0], buffer, sizeof(buffer));
        if (size < 0 && errno == EINTR) {
            continue;
        }
        if (size <= 0) {
            break;
        }
        output_callback(output_handle, buffer, (size_t)size);
    }
    close(pfd[0]);
    return NULL;
}

static void close_owned_fd(int *fd) {
    if (*fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static int replace_fd(int source, int target) {
    int result;
    do {
        result = dup2(source, target);
    } while (result < 0 && errno == EINTR);
    return result;
}

static void release_output(int target, int *saved) {
    if (*saved < 0 || replace_fd(*saved, target) < 0) {
        close(target);
    }
    close_owned_fd(saved);
}

int game_log_begin(AuthYouOutput output, uintptr_t handle) {
    if (atomic_load(&output_active)) {
        return EBUSY;
    }
    if (pipe2(pfd, O_CLOEXEC) < 0) {
        return errno;
    }
    for (int end = 0; end < 2; ++end) {
        if (pfd[end] <= STDERR_FILENO) {
            int fd = fcntl(pfd[end], F_DUPFD_CLOEXEC, 3);
            if (fd < 0) {
                int error = errno;
                close_owned_fd(&pfd[1]);
                close_owned_fd(&pfd[0]);
                return error;
            }
            close(pfd[end]);
            pfd[end] = fd;
        }
    }

    // 기본 경로는 실행이 반환된 뒤에도 앱의 표준 FD를 유지한다.
    bool preserve_output = output == record_game_output;
    bool stdout_redirected = false;
    bool stderr_redirected = false;
    int result;
    if (preserve_output) {
        saved_stdout = fcntl(STDOUT_FILENO, F_DUPFD_CLOEXEC, 3);
        saved_stderr = fcntl(STDERR_FILENO, F_DUPFD_CLOEXEC, 3);
        if (saved_stdout < 0 || saved_stderr < 0) {
            result = errno;
            goto failed;
        }
    }

    if (replace_fd(pfd[1], STDOUT_FILENO) < 0) {
        result = errno;
        goto failed;
    }
    stdout_redirected = true;
    if (replace_fd(pfd[1], STDERR_FILENO) < 0) {
        result = errno;
        goto failed;
    }
    stderr_redirected = true;

    output_callback = output;
    output_handle = handle;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    result = pthread_create(&logger, NULL, logger_thread, NULL);
    if (result != 0) {
        goto failed;
    }

    atomic_store(&output_active, true);
    return 0;

failed:
    if (stdout_redirected) {
        release_output(STDOUT_FILENO, &saved_stdout);
    }
    if (stderr_redirected) {
        release_output(STDERR_FILENO, &saved_stderr);
    }
    close_owned_fd(&saved_stdout);
    close_owned_fd(&saved_stderr);
    close_owned_fd(&pfd[1]);
    close_owned_fd(&pfd[0]);
    return result;
}

void game_log_end(void) {
    if (!atomic_exchange(&output_active, false)) {
        return;
    }

    fflush(stdout);
    fflush(stderr);
    release_output(STDOUT_FILENO, &saved_stdout);
    release_output(STDERR_FILENO, &saved_stderr);
    close_owned_fd(&pfd[1]);
    pthread_join(logger, NULL);
    pfd[0] = -1;

    pthread_mutex_lock(&log_file_lock);
    close_owned_fd(&latestlog_fd);
    pthread_mutex_unlock(&log_file_lock);
}

void game_log_flush_on_exit(void) {
    if (!atomic_load(&output_active) || pthread_equal(pthread_self(), logger)) {
        return;
    }

    fflush(stdout);
    fflush(stderr);
    ssize_t result;
    do {
        result = write(pfd[1], "\n", 1);
    } while (result < 0 && errno == EINTR);
    sleep(1);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, jclass clazz, jstring log_path) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, log_path, NULL);
    if (path == NULL) {
        return;
    }

    int fd = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
    int result = fd < 0 ? errno : 0;
    (*env)->ReleaseStringUTFChars(env, log_path, path);
    if (fd >= 0) {
        latestlog_fd = fd;
        result = game_log_begin(record_game_output, 0);
        if (result != 0) {
            close_owned_fd(&latestlog_fd);
        }
    }
    if (result != 0) {
        jclass exception = (*env)->FindClass(env, "java/io/IOException");
        if (exception != NULL) {
            (*env)->ThrowNew(env, exception, strerror(result));
        }
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_end(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    game_log_end();
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

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendBytes(
        JNIEnv *env, jclass clazz, jbyteArray text) {
    (void)clazz;
    jsize size = (*env)->GetArrayLength(env, text);
    jbyte *bytes = (*env)->GetByteArrayElements(env, text, NULL);
    if (bytes == NULL) {
        return;
    }

    if (record_buffer((const char *)bytes, (size_t)size) && size > 0) {
        game_log_to_app((const char *)bytes, (size_t)size - 1);
    }
    (*env)->ReleaseByteArrayElements(env, text, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_setLogListener(
        JNIEnv *env, jclass clazz, jboolean enabled) {
    if (enabled && logger_class == NULL) {
        logger_on_event = (*env)->GetStaticMethodID(env, clazz, "onNativeLog", "([B)V");
        if (logger_on_event == NULL) {
            return;
        }
        logger_class = (*env)->NewGlobalRef(env, clazz);
        if (logger_class == NULL) {
            return;
        }
    }
    atomic_store(&listener_registered, enabled);
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}
