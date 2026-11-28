#include "BaseHook.h"
#include <dlfcn.h>
#include <android/log.h>
#include <cstdlib>
#define LOG_TAG "BaseHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

void BaseHook::init(JNIEnv *env) { 


}
