// xh_mpv.cpp —— 我们自己的 libmpv 控制 + 渲染层（官方 libmpv，零分叉）
//
// 为什么要自己写这一层：
//   jniLibs 里的 libplayer.so 只导出 17 个 Java_is_xyz_mpv_* 符号，
//   它把 mpv_handle 锁死在内部静态变量里，不对外出口。
//   而 mpv_render_context_create 必须拿到那个 handle，
//   所以想用 render API（自己决定哪一帧上屏、什么时候上屏），
//   就必须由我们自己 create handle 并接管全部控制。
//
// 这一层做的事：
//   * mpv_create / initialize（vo=libmpv，帧交给我们渲染）
//   * 选项、命令、属性读写（字符串形式，够用且不用维护 node 树）
//   * EGL + GLES2 把 render API 的输出贴到 SurfaceView 的 ANativeWindow 上
//   * update 回调 → 通知 Kotlin 来取帧
//
// 刻意不做：
//   * 不解析 mpv 的 node/map 结构体（track-list 用扁平字符串属性读）
//   * 不走事件循环（Kotlin 侧定时轮询属性，简单可靠）

#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <mutex>
#include <atomic>

#define LOG_TAG "XhMpvNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ------------------------------------------------------------------ mpv 声明
// 只声明我们用到的类型与函数指针，避免依赖 mpv 头文件（构建机上没有）
typedef void mpv_handle;
typedef void mpv_render_context;

typedef struct mpv_render_param {
    int type;
    void *data;
} mpv_render_param;

typedef void *(*mpv_gl_get_proc_address_fn)(void *ctx, const char *name);

typedef struct mpv_opengl_init_params {
    mpv_gl_get_proc_address_fn get_proc_address;
    void *get_proc_address_ctx;
} mpv_opengl_init_params;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w;
    int h;
    int internal_format;
} mpv_opengl_fbo;

#define MPV_RENDER_PARAM_INVALID 0
#define MPV_RENDER_PARAM_API_TYPE 1
#define MPV_RENDER_PARAM_OPENGL_INIT_PARAMS 2
#define MPV_RENDER_PARAM_OPENGL_FBO 3
#define MPV_RENDER_PARAM_FLIP_Y 4
#define MPV_RENDER_PARAM_ADVANCED_CONTROL 10
#define MPV_RENDER_API_TYPE_OPENGL "opengl"

typedef void (*mpv_render_update_fn)(void *cb_ctx);

// 函数指针表
static mpv_handle *(*p_mpv_create)(void) = nullptr;
static int (*p_mpv_initialize)(mpv_handle *) = nullptr;
static int (*p_mpv_set_option_string)(mpv_handle *, const char *, const char *) = nullptr;
static int (*p_mpv_set_property_string)(mpv_handle *, const char *, const char *) = nullptr;
static char *(*p_mpv_get_property_string)(mpv_handle *, const char *) = nullptr;
static void (*p_mpv_free)(void *) = nullptr;
static int (*p_mpv_command)(mpv_handle *, const char **) = nullptr;
static void (*p_mpv_terminate_destroy)(mpv_handle *) = nullptr;
static int (*p_render_create)(mpv_handle *, mpv_render_context **, mpv_render_param *) = nullptr;
static void (*p_render_set_update_cb)(mpv_render_context *, mpv_render_update_fn, void *) = nullptr;
static int (*p_render_render)(mpv_render_context *, mpv_render_param *) = nullptr;
static void (*p_render_report_swap)(mpv_render_context *) = nullptr;
static void (*p_render_free)(mpv_render_context *) = nullptr;

static bool load_mpv() {
    static bool tried = false, ok = false;
    static std::mutex m;
    std::lock_guard<std::mutex> g(m);
    if (tried) return ok;
    tried = true;
    void *h = dlopen("libmpv.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        LOGE("dlopen libmpv.so 失败: %s", dlerror());
        return ok = false;
    }
#define BIND(name, dst)                                                    \
    dst = (decltype(dst)) dlsym(h, name);                                  \
    if (!dst) { LOGE("缺符号 %s", name); return ok = false; }
    BIND("mpv_create", p_mpv_create)
    BIND("mpv_initialize", p_mpv_initialize)
    BIND("mpv_set_option_string", p_mpv_set_option_string)
    BIND("mpv_set_property_string", p_mpv_set_property_string)
    BIND("mpv_get_property_string", p_mpv_get_property_string)
    BIND("mpv_free", p_mpv_free)
    BIND("mpv_command", p_mpv_command)
    BIND("mpv_terminate_destroy", p_mpv_terminate_destroy)
    BIND("mpv_render_context_create", p_render_create)
    BIND("mpv_render_context_set_update_callback", p_render_set_update_cb)
    BIND("mpv_render_context_render", p_render_render)
    BIND("mpv_render_context_report_swap", p_render_report_swap)
    BIND("mpv_render_context_free", p_render_free)
#undef BIND
    LOGI("libmpv render API 就绪");
    return ok = true;
}

// ------------------------------------------------------------------ 上下文
struct Context {
    mpv_handle *mpv = nullptr;
    mpv_render_context *render = nullptr;

    ANativeWindow *window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext glctx = EGL_NO_CONTEXT;
    EGLConfig config = nullptr;

    int width = 0;
    int height = 0;

    int esVersion = 2;      // 实际拿到的 GLES 版本：2 或 3
    int colorBits = 8;      // 期望的离屏目标位深：8 或 10（10 需要 ES3）

    std::atomic<bool> alive{true};

    /* ★渲染线程与窗口线程的互斥。
       mpv 的 update 回调在 mpv 自己的线程上触发，回调进 Java 的 onFrame() 又会同步回调
       nativeRender() —— 也就是说 eglMakeCurrent / mpv_render_context_render / eglSwapBuffers
       全都跑在 mpv 线程上。而窗口一变（菜单键、焦点变化、沉浸式重排）UI 线程会走
       nativeDetach()：销毁 render context + eglDestroySurface。
       两个线程没有任何同步 → 正 swap 的那一帧拿到已销毁的 EGLSurface/context = 原生 SIGSEGV，
       进程直接被杀（Java 层看不到异常，crashTail 也是空）—— 这就是"按菜单键闪退回桌面"。
       用 recursive_mutex：render 内部 report_swap 还会再触发 update 回调 → onFrame → nativeRender，
       同一线程重入，普通 mutex 会自锁死。 */
    std::recursive_mutex glLock;

    JavaVM *vm = nullptr;
    jobject listener = nullptr;          // 全局引用，update 回调时调它的 onFrame()
    jmethodID onFrame = nullptr;
};

static void *egl_proc(void * /*ctx*/, const char *name) {
    return (void *) eglGetProcAddress(name);
}

static void on_mpv_update(void *cb_ctx) {
    Context *c = (Context *) cb_ctx;
    // 销毁期间 mpv 线程仍可能回调（terminate 是异步的）。
    // 先查 alive，再取 listener —— 否则会调到一个已经 DeleteGlobalRef 的 jobject，
    // JNI 直接 abort（真机表现就是「按菜单键瞬间闪退」）。
    if (!c || !c->alive.load() || !c->vm || !c->listener || !c->onFrame) return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (c->vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (c->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (c->alive.load()) env->CallVoidMethod(c->listener, c->onFrame);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) c->vm->DetachCurrentThread();
}

static bool egl_init(Context *c) {
    if (c->display != EGL_NO_DISPLAY) return true;
    c->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (c->display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay 失败"); return false; }
    if (!eglInitialize(c->display, nullptr, nullptr)) { LOGE("eglInitialize 失败"); return false; }

    EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE};
    EGLint num = 0;
    if (!eglChooseConfig(c->display, attribs, &c->config, 1, &num) || num <= 0) {
        LOGE("eglChooseConfig 失败");
        return false;
    }
    // 先要 GLES3 上下文（10bit / HDR 需要），拿不到再回落 GLES2。
    // GLES3 上下文对 GLES2 调用是向下兼容的，回落只影响能不能用 10bit 目标。
    EGLint ctx3[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLint ctx2[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    c->glctx = eglCreateContext(c->display, c->config, EGL_NO_CONTEXT, ctx3);
    if (c->glctx != EGL_NO_CONTEXT) {
        c->esVersion = 3;
    } else {
        c->glctx = eglCreateContext(c->display, c->config, EGL_NO_CONTEXT, ctx2);
        if (c->glctx == EGL_NO_CONTEXT) { LOGE("eglCreateContext 失败（ES3/ES2 都不行）"); return false; }
        c->esVersion = 2;
    }
    LOGI("EGL 上下文就绪：GLES%d", c->esVersion);
    return true;
}

static bool bind_window(Context *c, ANativeWindow *win, int w, int h) {
    std::lock_guard<std::recursive_mutex> guard(c->glLock);
    if (!egl_init(c)) return false;
    if (c->surface != EGL_NO_SURFACE) {
        eglMakeCurrent(c->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(c->display, c->surface);
        c->surface = EGL_NO_SURFACE;
    }
    if (win) {
        c->surface = eglCreateWindowSurface(c->display, c->config, win, nullptr);
        if (c->surface == EGL_NO_SURFACE) { LOGE("eglCreateWindowSurface 失败"); return false; }
    }
    if (!eglMakeCurrent(c->display, c->surface, c->surface, c->glctx)) {
        LOGE("eglMakeCurrent 失败");
        return false;
    }
    c->width = w;
    c->height = h;
    return true;
}

static bool create_render(Context *c) {
    if (c->render) return true;
    mpv_opengl_init_params initParams{egl_proc, nullptr};
    int advanced = 1;
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, (void *) MPV_RENDER_API_TYPE_OPENGL},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &initParams},
            {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advanced},
            {MPV_RENDER_PARAM_INVALID, nullptr}};
    int rc = p_render_create(c->mpv, &c->render, params);
    if (rc < 0) { LOGE("mpv_render_context_create 失败 rc=%d", rc); return false; }
    p_render_set_update_cb(c->render, on_mpv_update, c);
    return true;
}

// ------------------------------------------------------------------ JNI
extern "C" JNIEXPORT jlong JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeCreate(JNIEnv *env, jclass, jobject listener) {
    if (!load_mpv()) return 0;
    Context *c = new Context();
    env->GetJavaVM(&c->vm);
    if (listener) {
        c->listener = env->NewGlobalRef(listener);
        jclass cls = env->GetObjectClass(listener);
        c->onFrame = env->GetMethodID(cls, "onFrame", "()V");
        env->DeleteLocalRef(cls);
    }
    c->mpv = p_mpv_create();
    if (!c->mpv) { LOGE("mpv_create 失败"); delete c; return 0; }
    // 帧交给我们渲染（render API），必须在 initialize 之前设
    p_mpv_set_option_string(c->mpv, "vo", "libmpv");
    p_mpv_set_option_string(c->mpv, "gpu-api", "opengl");
    p_mpv_set_option_string(c->mpv, "opengl-es", "yes");
    LOGI("mpv 句柄已创建（vo=libmpv，待 initialize）");
    return (jlong) c;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeInit(JNIEnv *, jclass, jlong th) {
    Context *c = (Context *) th;
    if (!c || !c->mpv) return JNI_FALSE;
    if (p_mpv_initialize(c->mpv) < 0) { LOGE("mpv_initialize 失败"); return JNI_FALSE; }
    LOGI("mpv initialize 完成");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeSetOption(JNIEnv *env, jclass, jlong th, jstring k, jstring v) {
    Context *c = (Context *) th;
    if (!c || !c->mpv) return JNI_FALSE;
    const char *ks = env->GetStringUTFChars(k, nullptr);
    const char *vs = env->GetStringUTFChars(v, nullptr);
    int rc = p_mpv_set_option_string(c->mpv, ks, vs);
    if (rc < 0) LOGI("setOption %s 失败 rc=%d", ks, rc);
    env->ReleaseStringUTFChars(k, ks);
    env->ReleaseStringUTFChars(v, vs);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeSetProperty(JNIEnv *env, jclass, jlong th, jstring k, jstring v) {
    Context *c = (Context *) th;
    if (!c || !c->mpv) return JNI_FALSE;
    const char *ks = env->GetStringUTFChars(k, nullptr);
    const char *vs = env->GetStringUTFChars(v, nullptr);
    int rc = p_mpv_set_property_string(c->mpv, ks, vs);
    env->ReleaseStringUTFChars(k, ks);
    env->ReleaseStringUTFChars(v, vs);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeGetProperty(JNIEnv *env, jclass, jlong th, jstring k) {
    Context *c = (Context *) th;
    if (!c || !c->mpv) return nullptr;
    const char *ks = env->GetStringUTFChars(k, nullptr);
    char *v = p_mpv_get_property_string(c->mpv, ks);
    env->ReleaseStringUTFChars(k, ks);
    if (!v) return nullptr;
    jstring out = env->NewStringUTF(v);
    p_mpv_free(v);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeCommand(JNIEnv *env, jclass, jlong th, jobjectArray args) {
    Context *c = (Context *) th;
    if (!c || !c->mpv) return JNI_FALSE;
    int n = env->GetArrayLength(args);
    const char **argv = (const char **) calloc(n + 1, sizeof(char *));
    for (int i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(args, i);
        if (s) {
            const char *us = env->GetStringUTFChars(s, nullptr);
            argv[i] = strdup(us ? us : "");
            if (us) env->ReleaseStringUTFChars(s, us);
            env->DeleteLocalRef(s);
        } else {
            argv[i] = strdup("");
        }
    }
    int rc = p_mpv_command(c->mpv, argv);
    for (int i = 0; i < n; i++) free((void *) argv[i]);
    free(argv);
    return rc >= 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeEsVersion(JNIEnv *, jclass, jlong th) {
    Context *c = (Context *) th;
    return c ? c->esVersion : 2;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeSetColorBits(JNIEnv *, jclass, jlong th, jint bits) {
    Context *c = (Context *) th;
    if (!c) return;
    c->colorBits = bits;
    LOGI("离屏目标位深=%d（ES%d）", bits, c->esVersion);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeAttach(JNIEnv *env, jclass, jlong th, jobject surface, jint w, jint h) {
    Context *c = (Context *) th;
    if (!c || !c->mpv || !surface) return JNI_FALSE;
    ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
    if (!win) { LOGE("ANativeWindow_fromSurface 失败"); return JNI_FALSE; }
    if (c->window) ANativeWindow_release(c->window);
    c->window = win;
    if (!bind_window(c, win, w, h)) return JNI_FALSE;
    if (!create_render(c)) return JNI_FALSE;
    char size[64];
    snprintf(size, sizeof(size), "%dx%d", w, h);
    p_mpv_set_property_string(c->mpv, "android-surface-size", size);
    LOGI("render 已挂上 Surface %dx%d", w, h);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeRender(JNIEnv *, jclass, jlong th, jint w, jint h) {
    Context *c = (Context *) th;
    if (!c) return JNI_FALSE;
    std::lock_guard<std::recursive_mutex> guard(c->glLock);
    if (!c->alive.load() || !c->render || c->surface == EGL_NO_SURFACE) return JNI_FALSE;
    if (w > 0 && h > 0 && (w != c->width || h != c->height)) {
        c->width = w;
        c->height = h;
        char size[64];
        snprintf(size, sizeof(size), "%dx%d", w, h);
        p_mpv_set_property_string(c->mpv, "android-surface-size", size);
    }
    if (!eglMakeCurrent(c->display, c->surface, c->surface, c->glctx)) return JNI_FALSE;
    // 离屏目标的内部格式按位深选：
    //   GLES2 → 只有 GL_RGBA（0x1908，8bit/通道）
    //   GLES3 → 可用 GL_RGB10_A2（0x8059，10bit/通道），HDR 片源才不丢精度
    // GL_RGBA8 / GL_RGB10_A2 不是 GLES2 头文件里的标识符，这里直接用数值，
    // 避免为了两个枚举把整个文件切到 GLES3 头（那会要求全链路都是 ES3）。
    int fboFormat = (c->colorBits >= 10 && c->esVersion >= 3) ? 0x8059 : 0x1908;
    mpv_opengl_fbo fbo{0, c->width, c->height, fboFormat};
    int flip = 1;
    mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flip},
            {MPV_RENDER_PARAM_INVALID, nullptr}};
    p_render_render(c->render, params);
    if (!eglSwapBuffers(c->display, c->surface)) return JNI_FALSE;
    p_render_report_swap(c->render);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeDetach(JNIEnv *, jclass, jlong th) {
    Context *c = (Context *) th;
    if (!c) return;
    std::lock_guard<std::recursive_mutex> guard(c->glLock);
    if (c->render) {
        p_render_set_update_cb(c->render, nullptr, nullptr);
        p_render_free(c->render);
        c->render = nullptr;
    }
    if (c->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(c->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (c->surface != EGL_NO_SURFACE) eglDestroySurface(c->display, c->surface);
        c->surface = EGL_NO_SURFACE;
    }
    if (c->window) {
        ANativeWindow_release(c->window);
        c->window = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xinghui_tv_mpv_XhMpv_nativeDestroy(JNIEnv *env, jclass, jlong th) {
    Context *c = (Context *) th;
    if (!c) return;
    c->alive.store(false);            // 先关掉回调闸门，再拆
    Java_com_xinghui_tv_mpv_XhMpv_nativeDetach(env, nullptr, th);
    if (c->glctx != EGL_NO_CONTEXT && c->display != EGL_NO_DISPLAY) {
        eglDestroyContext(c->display, c->glctx);
        c->glctx = EGL_NO_CONTEXT;
    }
    if (c->display != EGL_NO_DISPLAY) {
        eglTerminate(c->display);
        c->display = EGL_NO_DISPLAY;
    }
    if (c->mpv) {
        p_mpv_set_property_string(c->mpv, "vo", "null");
        p_mpv_terminate_destroy(c->mpv);
        c->mpv = nullptr;
    }
    if (c->listener) env->DeleteGlobalRef(c->listener);
    c->listener = nullptr;
    delete c;
    LOGI("mpv 实例已销毁");
}
