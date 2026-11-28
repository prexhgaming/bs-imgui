#pragma once

// ============================================================
// RUNTIME PACKAGE NAME
// ============================================================
// Nothing in this project may hardcode the app's package name. The very same
// library is loaded by every build of the game (com.netease.newspike,
// ...newspikeme, sandboxed clones, ...), so a hardcoded name silently points
// every path at the wrong app: the config dir gets created where the game never
// looks, the Python side never finds data.json and the ESP falls back to its
// default 1080x720 resolution.
//
// The kernel already knows who we are: the first NUL-terminated entry of
// /proc/self/cmdline is the process name, i.e. "<package>" or
// "<package>:<suffix>" for a private process (":remote", ":push", ...). Only the
// package part matters - the data dir is per package, not per process.
//
// If cmdline is unreadable (some sandboxes blank it) the APK path in our own
// maps file carries the package as well:
//   /data/app/~~<hash>==/<package>-<hash>==/base.apk
// so we take the directory above the .apk and strip the "-<hash>==" decoration.
//
// The result is resolved once and cached: it cannot change for the life of the
// process and several threads ask for it (hack_thread, the ImGui thread, ...).

#include <fstream>
#include <string>

namespace nexora {

namespace detail {

// "/data/app/~~x==/com.foo.bar-y==/base.apk" -> "com.foo.bar"
inline std::string packageFromApkPath(const std::string& path) {
    // Directory that holds the apk file.
    size_t slash = path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return std::string();
    size_t prev = path.find_last_of('/', slash - 1);
    if (prev == std::string::npos) return std::string();
    std::string dir = path.substr(prev + 1, slash - prev - 1);

    // Newer Android versions decorate the dir with the install hash.
    size_t dash = dir.find('-');
    if (dash != std::string::npos) dir.resize(dash);
    return dir;
}

inline std::string fromCmdline() {
    std::ifstream file("/proc/self/cmdline", std::ios::binary);
    if (!file) return std::string();
    // The file is a list of NUL-separated argv strings and argv[0] is the
    // process name, so getline with '\0' as delimiter gives us exactly that.
    std::string name;
    std::getline(file, name, '\0');
    return name;
}

inline std::string fromMaps() {
    std::ifstream maps("/proc/self/maps");
    if (!maps) return std::string();
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find(".apk") == std::string::npos) continue;
        // The last whitespace-separated token of a maps line is the path.
        size_t sp = line.find_last_of(" \t");
        if (sp == std::string::npos) continue;
        std::string pkg = packageFromApkPath(line.substr(sp + 1));
        if (!pkg.empty()) return pkg;
    }
    return std::string();
}

// A real package name has no path separator and no whitespace, and always has a
// dot. A cmdline that says "/system/bin/app_process" (zygote-style launch) is
// not a package name at all and must fall through to the maps lookup.
inline bool looksLikePackage(const std::string& name) {
    if (name.empty()) return false;
    if (name.find('/') != std::string::npos) return false;
    if (name.find(' ') != std::string::npos) return false;
    return name.find('.') != std::string::npos;
}

} // namespace detail

// Cached package name, e.g. "com.netease.newspike". Empty only when both
// /proc/self/cmdline and /proc/self/maps failed - callers must treat that as
// "unknown" instead of substituting a guess.
inline const std::string& getPackageName() {
    static const std::string resolved = [] {
        std::string name = detail::fromCmdline();
        // "<package>:<suffix>" -> "<package>"
        size_t colon = name.find(':');
        if (colon != std::string::npos) name.resize(colon);
        if (!detail::looksLikePackage(name)) name = detail::fromMaps();
        return name;
    }();
    return resolved;
}

// Convenience for the "/data/data/<pkg>..." paths built all over the project.
inline std::string packagePath(const std::string& suffix) {
    return "/data/data/" + getPackageName() + suffix;
}

} // namespace nexora
