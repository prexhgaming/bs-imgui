#include "SeccompUtils.h"
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <ucontext.h>
#include <android/log.h>

#define LOG_TAG "SECCOMP"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define SECMAGIC 0xdeadbeef

#if defined(__aarch64__)
uint64_t OriSyscall(uint64_t num, uint64_t arg1, uint64_t arg2, uint64_t arg3,uint64_t arg4, uint64_t arg5, uint64_t arg6) {
    uint64_t ret;
    asm volatile ("mov x8, %1\n""mov x0, %2\n" "mov x1, %3\n" "mov x2, %4\n" "mov x3, %5\n" "mov x4, %6\n" "mov x5, %7\n" "svc #0\n" "mov %0, x0\n" : "=r"(ret) : "r"(num), "r"(arg1), "r"(arg2), "r"(arg3),   "r"(arg4), "r"(arg5), "r"(arg6) : "x8", "x0", "x1", "x2", "x3", "x4", "x5");
    return ret;
}
#elif defined(__arm__)
uint32_t OriSyscall(uint32_t num, uint32_t arg1, uint32_t arg2, uint32_t arg3,             uint32_t arg4, uint32_t arg5, uint32_t arg6) {
    uint32_t ret;
    asm volatile ( "mov r7, %1\n" "mov r0, %2\n" "mov r1, %3\n" "mov r2, %4\n" "mov r3, %5\n" "mov r4, %6\n" "mov r5, %7\n" "svc #0\n" "mov %0, r0\n" : "=r"(ret) : "r"(num), "r"(arg1), "r"(arg2), "r"(arg3),   "r"(arg4), "r"(arg5), "r"(arg6) : "r7", "r0", "r1", "r2", "r3", "r4", "r5");
    return ret;
}
#else
#error "Unsupported architecture"
#endif

static void sigsys_handler(int signo, siginfo_t *info, void *context) {
    ucontext_t *ctx = (ucontext_t *)context;

#if defined(__aarch64__)
    auto syscall_number = ctx->uc_mcontext.regs[8];
    auto arg1 = ctx->uc_mcontext.regs[0];
    auto arg2 = ctx->uc_mcontext.regs[1];
    auto arg3 = ctx->uc_mcontext.regs[2];
    auto arg4 = ctx->uc_mcontext.regs[3];
#elif defined(__arm__)
    auto syscall_number = ctx->uc_mcontext.arm_r7;
    auto arg1 = ctx->uc_mcontext.arm_r0;
    auto arg2 = ctx->uc_mcontext.arm_r1;
    auto arg3 = ctx->uc_mcontext.arm_r2;
    auto arg4 = ctx->uc_mcontext.arm_r3;
#endif

    if (syscall_number == __NR_openat) {
        const char *pathname = reinterpret_cast<const char *>(arg2);
        ALOGE("Intercepted openat call: %s", pathname);

#if defined(__aarch64__)
        ctx->uc_mcontext.regs[0] = OriSyscall(__NR_openat, arg1, (uint64_t)pathname, arg3, arg4, SECMAGIC, SECMAGIC);
#elif defined(__arm__)
        ctx->uc_mcontext.arm_r0 = OriSyscall(__NR_openat, arg1, (uint32_t)pathname, arg3, arg4, SECMAGIC, SECMAGIC);
#endif
    }
}

namespace SeccompUtils {

void init() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 2),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[4])),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SECMAGIC, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    };

    struct sock_fprog prog = {
        .filter = filter,
        .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
    };

    struct sigaction sa{};
    sa.sa_sigaction = sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigfillset(&sa.sa_mask);

    if (sigaction(SIGSYS, &sa, nullptr) == -1) {
        ALOGE("Error: sigaction setup failed");
        return;
    }

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == -1) {
        ALOGE("Error: PR_SET_NO_NEW_PRIVS failed");
        return;
    }

    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) == -1) {
        ALOGE("Error: SECCOMP_MODE_FILTER setup failed");
        return;
    }

    ALOGE("Seccomp filter successfully installed.");
}

} // namespace SeccompUtils
