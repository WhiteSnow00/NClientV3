#include <string.h>
#include <stdlib.h>
#include <unistd.h>

#include <jni.h>
#include <getopt.h>
#include <signal.h>
#include <setjmp.h>

#include "byedpi/error.h"
#include "main.h"

extern int server_fd;

static int g_proxy_running = 0;
static jmp_buf crash_jmp_buf;
static JavaVM *g_java_vm = NULL;
static jobject g_proxy_bridge = NULL;
static jmethodID g_protect_method = NULL;

struct params default_params = {
        .await_int = 10,
        .cache_ttl = 100800,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
            .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
            .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params();
    params = default_params;
}

static void clear_bridge(JNIEnv *env) {
    if (g_proxy_bridge != NULL) {
        (*env)->DeleteGlobalRef(env, g_proxy_bridge);
        g_proxy_bridge = NULL;
    }
    g_protect_method = NULL;
}

static int cache_bridge(JNIEnv *env, jobject thiz) {
    jclass clazz = NULL;

    clear_bridge(env);

    g_proxy_bridge = (*env)->NewGlobalRef(env, thiz);
    if (g_proxy_bridge == NULL) {
        return -1;
    }

    clazz = (*env)->GetObjectClass(env, thiz);
    if (clazz == NULL) {
        clear_bridge(env);
        return -1;
    }

    g_protect_method = (*env)->GetMethodID(env, clazz, "protectSocket", "(I)Z");
    (*env)->DeleteLocalRef(env, clazz);

    if (g_protect_method == NULL) {
        clear_bridge(env);
        return -1;
    }

    return 0;
}

int android_protect_socket(int fd) {
    JNIEnv *env = NULL;
    jboolean protected_ok = JNI_TRUE;
    int detach = 0;

    if (g_java_vm == NULL || g_proxy_bridge == NULL || g_protect_method == NULL) {
        return 0;
    }

    if ((*g_java_vm)->GetEnv(g_java_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_java_vm)->AttachCurrentThread(g_java_vm, (void **) &env, NULL) != JNI_OK) {
            return -1;
        }
        detach = 1;
    }

    protected_ok = (*env)->CallBooleanMethod(env, g_proxy_bridge, g_protect_method, fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        protected_ok = JNI_FALSE;
    }

    if (detach) {
        (*g_java_vm)->DetachCurrentThread(g_java_vm);
    }

    return protected_ok == JNI_TRUE ? 0 : -1;
}

static void sigsegv_handler(int sig) {
    LOG(LOG_S, "SIGSEGV caught in native code, signal: %d", sig);

    if (sig == 11) {
        longjmp(crash_jmp_buf, 1);
    } else {
        shutdown(server_fd, SHUT_RDWR);
    }

    g_proxy_running = 0;
    reset_params();
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, __attribute__((unused)) void *reserved) {
    g_java_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_maxwai_nclientv3_bypass_ByeDpiProxyBridge_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    if (g_proxy_running) {
        LOG(LOG_S, "proxy already running");
        return -1;
    }

    if (cache_bridge(env, thiz) != 0) {
        LOG(LOG_E, "failed to cache bridge protector");
        return -1;
    }

    struct sigaction sa;
    sa.sa_handler = sigsegv_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);

    if (setjmp(crash_jmp_buf) != 0) {
        LOG(LOG_S, "crash proxy, continuing...");
        g_proxy_running = 0;
        reset_params();
        clear_bridge(env);
        return 0;
    }

    int argc = (*env)->GetArrayLength(env, args);
    char *argv[argc];
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
    }

    LOG(LOG_S, "starting proxy with %d args", argc);
    g_proxy_running = 1;
    optind = optreset = 1;

    int result = main(argc, argv);

    for (int i = 0; i < argc; i++) {
        free(argv[i]);
    }

    LOG(LOG_S, "proxy return code %d", result);
    g_proxy_running = 0;
    reset_params();
    clear_bridge(env);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_maxwai_nclientv3_bypass_ByeDpiProxyBridge_jniStopProxy(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "send shutdown to proxy");

    if (!g_proxy_running) {
        LOG(LOG_S, "proxy is not running");
        return -1;
    }

    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    reset_params();

    return 0;
}

JNIEXPORT jint JNICALL
Java_com_maxwai_nclientv3_bypass_ByeDpiProxyBridge_jniForceClose(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jobject thiz) {
    LOG(LOG_S, "closing server socket (fd: %d)", server_fd);

    if (close(server_fd) == -1) {
        LOG(LOG_S, "failed to close server socket (fd: %d)", server_fd);
        return -1;
    }

    LOG(LOG_S, "proxy socket force close");
    return 0;
}
