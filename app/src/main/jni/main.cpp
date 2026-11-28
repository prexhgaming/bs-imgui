#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <thread>
#include <android/log.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <android/native_activity.h>
#include <errno.h>
#include <GLES3/gl3.h>
#include "imgui/imgui.h"
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, "Nexora", fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "Nexora", fmt, ##__VA_ARGS__)
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO, "Nexora", fmt, ##__VA_ARGS__)
#include "include/obfuscate.h"

#include "include/hpp/package.h"

static const std::string& PACKAGE_NAME() {
    static const std::string name = nexora::getPackageName();
    return name;
}

#include "include/includes.h"
#include "AndInlineHook/AndInlineHook.h"
#include "KittyMemory/KittyMemory.h"
#include "KittyMemory/MemoryPatch.h"
#include "KittyMemory/KittyScanner.h"
#include "KittyMemory/KittyUtils.h"
#include "xhook/xhook.h"
#include "include/hpp/srcpy.h"
#include "NetworkBlocker.hpp"

char FIFO_PATH[256] = {0};

bool g_pending_cjk_reload = false;
bool g_cjkLoaded = false;
float global_anim_time = 0.f;
GLuint g_logo_tex = 0;
ANativeActivity* g_activity = nullptr;
int Width = 0, Height = 0;
int screenWidth = -1, screenHeight = -1;
std::atomic<bool> aimgui_thread_ready{false};
bool windowOpen = true;
std::unordered_map<std::string, bool> sync_bool;
std::unordered_map<std::string, float> sync_float;
std::unordered_map<std::string, int> sync_int;
ImVec2 windowPos = {80, 120};
std::atomic<bool> menu_visible{true};

static constexpr uintptr_t PyRunOffset = 0x024dbcf0;

extern void AddNotification(const char* msg, int r, int g, int b, float dur);

static inline void sync_with_py_start_cleanup();

extern "C" void ANativeActivity_onCreate(ANativeActivity* activity, void* savedState, size_t savedStateSize) {
    g_activity = activity;
    sync_with_py_start_cleanup();
    LOGI("✅ Activity created");
}

static const std::string& srcpy_script() {
    static const std::string s = [] {
        std::string out = "_PKG_OVERRIDE = '" + PACKAGE_NAME() + "'\n";
        out += srcpy;
        return out;
    }();
    return s;
}

static int iLoaded = 0, iLoadAt = 2;
DEFINES(void*, iMissMyWife, const char* v0, int v1, void* v2, void* v3, void* v4) {
    static bool loaded = false;
    if (v1 == 257) {
        iLoaded++;
        if (iLoaded >= iLoadAt && !loaded) {
            loaded = true;
            _iMissMyWife(srcpy_script().c_str(), 257, v2, v2, NULL);
        }
    }
    return _iMissMyWife(v0, v1, v2, v3, v4);
}

#include "draw.h"
#include "include/input.h"

static void nexora_mkdir_recursive(const char* path) {
    char tmp[256]; char* p = NULL; size_t len;
    snprintf(tmp, sizeof(tmp), "%s", path); len = strlen(tmp);
    if (len == 0) return;
    if (tmp[len - 1] == '/') tmp[len - 1] = 0;
    for (p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, 0777); *p = '/'; }
    }
    mkdir(tmp, 0777);
}

void hack_thread() {
    ProcMap gameLib;
    do { sleep(1); gameLib = KittyMemory::getLibraryBaseMap("libGame.so"); } while (!gameLib.isValid());
    uintptr_t stableBase = (uintptr_t)gameLib.startAddress;
    int stableCount = 0;
    while (stableCount < 3) {
        usleep(500000);
        ProcMap recheck = KittyMemory::getLibraryBaseMap("libGame.so");
        if (recheck.isValid() && (uintptr_t)recheck.startAddress == stableBase) {
            stableCount++;
        } else if (recheck.isValid()) {
            stableBase = (uintptr_t)recheck.startAddress;
            stableCount = 0;
        }
    }
    sleep(1);
    uintptr_t libGameBase = stableBase;
    
    std::string _pkg_dir = nexora::packagePath("/imgui");
    nexora_mkdir_recursive(_pkg_dir.c_str());

    NetworkBlocker::initialize();
    
    xhook_register(".*\\.so$", "getaddrinfo", (void*)NetworkBlocker::getaddrinfo_hooked, (void**)&orig_getaddrinfo);
    xhook_register(".*\\.so$", "gethostbyname", (void*)NetworkBlocker::gethostbyname_hooked, (void**)&orig_gethostbyname);
    xhook_refresh(0);

    menu_visible = true;
    AddNotification("✅ NEXORA READY!", 140, 89, 242, 2.f);
    
    HOOKI((ptr)libGameBase + PyRunOffset, iMissMyWife);

    IMGUI();
    __INPUT__();
}

__attribute__((constructor))
void __0__() {
    std::thread(hack_thread).detach();
}
