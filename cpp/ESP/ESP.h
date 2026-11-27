#pragma once
#ifndef IMCOLOR_DEFINED
#define IMCOLOR_DEFINED
#endif
#include <string>
#include <vector>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <chrono>
#include "imgui/imgui.h"
#include "include/sync_entities.h"
#include "entity.h"
#include "ivon.h"

#ifdef WHITE
#undef WHITE
#endif
#ifdef BLACK
#undef BLACK
#endif

extern int    Width;
extern int    Height;
extern float  global_anim_time;
extern std::atomic<bool> menu_visible;
extern void AddNotification(const char* msg, int r, int g, int b, float dur);

namespace ESP {
// ==================== COLORS ====================
namespace Colors {
    // Enemy in line of sight: bright and readable.
    static constexpr ImU32 COLOR_VISIBLE      = IM_COL32(255, 255, 255, 255);
    static constexpr ImU32 COLOR_VISIBLE_GLOW = IM_COL32(255, 255, 255, 60);

    // Enemy behind a wall: dimmed, desaturated.
    static constexpr ImU32 COLOR_HIDDEN       = IM_COL32(150, 150, 150, 100);
    static constexpr ImU32 COLOR_HIDDEN_BG    = IM_COL32(50,  50,  50,  150);

    // Knocked player: bright red, the loudest colour on screen. When knocked,
    // the whole name plate turns red - class distinction no longer matters.
    static constexpr ImU32 COLOR_KNOCK        = IM_COL32(255, 50,  50,  255);
    static constexpr ImU32 COLOR_KNOCK_BG     = IM_COL32(200, 35,  35,  235);

    // Aim-lane accent for the CURRENT target. The lane body stays white; only
    // the target endpoint (dot + ring + the short final segment) uses this, so
    // "who am I actually locking onto" reads at a glance. Knocked still wins.
    static constexpr ImU32 COLOR_AIM_TARGET   = IM_COL32(255, 200,  40, 255);

    // Entity class is shown by the name-tag badge only; all ESP lines (box,
    // skeleton, lane) stay white so the overlay stays clean. The badge makes
    // class readable at a glance:
    //   AI / bot      -> green badge
    //   human player  -> red badge
    // Zombies never reach the renderer (see SkipEntity below); the zombie
    // colour is kept only because other callers include this header.
    static constexpr ImU32 COLOR_TAG_BOT      = IM_COL32( 40, 158,  74, 245);
    static constexpr ImU32 COLOR_TAG_PLAYER   = IM_COL32(193,  46,  46, 245);
    static constexpr ImU32 COLOR_ZOMBIE       = IM_COL32(86,  214, 130, 255);
    static constexpr ImU32 COLOR_GREEN        = IM_COL32(74,  222, 128, 255);
    static constexpr ImU32 COLOR_PANEL_BG     = IM_COL32(10,  10,  14,  220);

    // Keep the hue, swap the alpha. IM_COL32 is packed 0xAABBGGRR.
    static inline ImU32 WithAlpha(ImU32 c, int a) {
        if (a < 0)   a = 0;
        if (a > 255) a = 255;
        return (c & 0x00FFFFFFu) | ((ImU32)a << 24);
    }

    static inline ImU32 Rainbow(float t) {
        float h = t - 0.5f; // Shift the wheel so the sweep starts at the top
        h -= floorf(h);
        ImVec4 c = (ImVec4)ImColor::HSV(h, 0.85f, 1.0f);
        return IM_COL32((int)(c.x * 255), (int)(c.y * 255), (int)(c.z * 255), 255);
    }
}

// ==================== FRAME FLAGS ====================
struct FrameFlags {
    bool  bESP         = true;
    bool  bBox         = true;
    bool  bHealth      = true;
    bool  bDistance    = true;
    bool  bName        = true;
    bool  bWeapon      = true;
    bool  bSkeleton    = true;
    bool  bLane        = true;
    bool  bRadar       = false;
    bool  bAIM         = false;
    bool  bAimLane     = true;
    bool  bLootESP     = false;
    float fMaxDist     = 300.f;
    float fRadarSize   = 150.f;
    float fRadarRange  = 400.f;
    float fAimFov      = 60.f;
    float fAimMaxDist  = 300.f;
    float fLootMaxDist = 120.f;
};
static FrameFlags s_ff;
static ImFont*    s_font = nullptr;

// ==================== HELPERS ====================
// Fixed text scale: near or far, the font and line sizes stay identical.
static inline float _ts(float dist, float base = 12.f) {
    return base;
}

static inline bool sg(const char* k, bool def) {
    auto it = sync_bool.find(k);
    return it != sync_bool.end() ? it->second : def;
}
static inline float sf(const char* k, float def) {
    auto it = sync_float.find(k);
    return it != sync_float.end() ? it->second : def;
}

static void SnapshotFlags() {
    s_ff.bESP        = sg("bESP", true);
    s_ff.bBox        = sg("bESP_Box", true);
    s_ff.bHealth     = sg("bESP_Health", true);
    s_ff.bDistance   = sg("bESP_Distance", true);
    s_ff.bName       = sg("bESP_Name", true);
    s_ff.bWeapon     = sg("bESP_Weapon", true);
    s_ff.bSkeleton   = sg("bESP_Skeleton", true);
    s_ff.bLane       = sg("bESP_Lane", true);
    s_ff.bRadar      = sg("bESP_Radar", false);
    s_ff.bAIM        = sg("bAIM", false);
    s_ff.bAimLane    = sg("bAIM_Lane", true);
    s_ff.bLootESP    = sg("bLootESP", false);
    s_ff.fMaxDist    = sf("fESP_MaxDistance", 500.f);
    s_ff.fRadarSize  = sf("fESP_RadarSize", 150.f);
    s_ff.fRadarRange = sf("fESP_RadarRange", 400.f);
    // Keep these defaults in sync with SetDefaultConfig() in draw.h and with
    // the values Python reads (fAIM_Fov / fAIM_MaxDist). They used to be 60/300
    // here, so the FOV circle was drawn far smaller than the real aim radius
    // until a saved config happened to load.
    s_ff.fAimFov     = sf("fAIM_Fov", 120.f);
    s_ff.fAimMaxDist = sf("fAIM_MaxDist", 400.f);
    s_ff.fLootMaxDist = sf("fLootESP_MaxDist", 120.f);
}

// ==================== UTILS ====================
namespace Utils {
    static inline bool IsValidFloat(float v) { return !std::isnan(v) && !std::isinf(v) && v > -10000.f && v < 10000.f; }
    static inline bool IsValidVec2(const ImVec2& v) { return IsValidFloat(v.x) && IsValidFloat(v.y); }
    static inline ImFont* GetSafeFont() {
        ImGuiIO& io = ImGui::GetIO();
        if (!io.Fonts || io.Fonts->Fonts.Size == 0 || !io.Fonts->Fonts[0]) return nullptr;
        return io.Fonts->Fonts[0];
    }
    static inline bool IsInBounds(const ImVec2& p) {
        if (!IsValidVec2(p)) return false;
        return p.x >= -100.f && p.x <= (float)Width + 100.f && p.y >= -100.f && p.y <= (float)Height + 100.f;
    }
    // Looser than IsInBounds: used for bones that may sit slightly off screen,
    // while still rejecting exploded projections (NaN or values in the
    // thousands) that used to draw one long line across the whole screen.
    static inline bool IsSaneScreen(const ImVec2& p) {
        if (!IsValidVec2(p)) return false;
        float mx = (float)Width  * 2.f + 400.f;
        float my = (float)Height * 2.f + 400.f;
        return std::fabs(p.x) <= mx && std::fabs(p.y) <= my;
    }
    static inline void Text(ImDrawList* dl, float x, float y, ImU32 col, const char* txt, float size = 12.f) {
        if (!dl || !txt || !txt[0]) return;
        ImFont* font = GetSafeFont();
        if (!font) { dl->AddText(ImVec2(x, y), col, txt); return; }
        dl->AddText(font, size, ImVec2(x + 1, y + 1), IM_COL32(0, 0, 0, 180), txt);
        dl->AddText(font, size, ImVec2(x, y), col, txt);
    }
    static inline void TextCentered(ImDrawList* dl, float cx, float y, ImU32 col, const char* txt, float size = 12.f) {
        if (!dl || !txt || !txt[0]) return;
        float w = s_font ? s_font->CalcTextSizeA(size, FLT_MAX, 0.f, txt).x : ImGui::CalcTextSize(txt).x;
        Text(dl, cx - w * 0.5f, y, col, txt, size);
    }
    static inline void RectFilled(ImDrawList* dl, const ImVec2& a, const ImVec2& b, ImU32 col, float r = 0.f) {
        if (!dl || !IsValidVec2(a) || !IsValidVec2(b)) return;
        if (a.x > b.x || a.y > b.y) return;
        dl->AddRectFilled(a, b, col, r);
    }
    static inline void Line(ImDrawList* dl, const ImVec2& a, const ImVec2& b, ImU32 col, float thickness = 1.f) {
        if (!dl || !IsValidVec2(a) || !IsValidVec2(b)) return;
        dl->AddLine(a, b, col, thickness);
    }
}

// ==================== ENTITY FILTER ====================
// Single filter used by every draw path: world objects (loot), dead players,
// teammates and zombies produce no overlay at all. Zombies are excluded on
// request: no box, name, health bar, weapon label, skeleton or lane.
static inline bool SkipEntity(const Entity& e) {
    return e.IsWorldObject() || !e.is_alive || e.is_teammate || e.is_zombie;
}

// ==================== ENTITY COLOUR ====================
// All ESP lines (box, skeleton, lane) are white. The only exception is a
// knocked entity: everything about it goes red because "downed" is a state
// worth seeing at a glance, and it no longer needs a class colour.
// In line of sight: full brightness. Behind cover: 45% alpha.
static inline ImU32 ClassColor(const Entity& e, int alpha = 255) {
    if (e.is_knocked) return Colors::WithAlpha(Colors::COLOR_KNOCK, alpha);

    int a = e.is_visible ? alpha : (alpha * 45) / 100;
    return Colors::WithAlpha(Colors::COLOR_VISIBLE, a);
}

// Solid pill behind the class tag. This is the ONLY place entity class is
// encoded now that lines are white:
//   AI / bot      -> green
//   human player  -> red
// Knocked entities ignore class and get a solid red pill (COLOR_KNOCK_BG);
// the caller paints the whole plate red too. Behind a wall the pill is dimmed.
static inline ImU32 ClassTagColor(const Entity& e) {
    if (e.is_knocked) return Colors::COLOR_KNOCK_BG;

    ImU32 base = e.is_bot ? Colors::COLOR_TAG_BOT      // AI
                          : Colors::COLOR_TAG_PLAYER;  // human (zombies filtered upstream)
    return e.is_visible ? base : Colors::WithAlpha(base, 150);
}

static inline const char* ClassTag(const Entity& e) {
    if (e.is_knocked) return "KNOCK";
    if (e.is_bot)     return "AI";
    return "PL";
}

// ==================== BONE HELPER ====================
namespace BoneHelper {
    static bool GetFootPos(const Entity& e, const ImVec2& head, ImVec2& foot) {
        if (!Utils::IsValidVec2(head) || head.y <= 0.f) return false;
        ImVec2 lf, rf, sp;
        if (e.GetBonePosition(BONE_LFOOT, lf) && Utils::IsInBounds(lf)) { foot = lf; return true; }
        if (e.GetBonePosition(BONE_RFOOT, rf) && Utils::IsInBounds(rf)) { foot = rf; return true; }
        if (e.GetBonePosition(BONE_SPINE, sp) && Utils::IsInBounds(sp)) {
            foot = ImVec2(head.x, sp.y + (sp.y - head.y) * 0.5f);
            return true;
        }
        // Fallback: estimate the height from the distance so the box never
        // collapses when no foot bone is available.
        float d = std::max(e.distance, 1.f);
        float estH = (50.f * 70.f) / d;
        estH = std::clamp(estH, 8.f, 260.f);
        foot = ImVec2(head.x, head.y + estH);
        return true;
    }
}

// ==================== 1. ESP COUNT ====================
namespace ESPCount {
    static void Draw(ImDrawList* dl, const std::vector<Entity>& ents) {
        if (!dl) return;
        int players = 0, bots = 0;
        for (const auto& e : ents) {
            if (SkipEntity(e)) continue;
            if (e.distance > s_ff.fMaxDist) continue;
            if (e.is_bot) bots++; else players++;
        }
        char buf[64];
        snprintf(buf, sizeof(buf), "Players: %d  |  Bots: %d", players, bots);
        float fs = 13.f;
        float cx = Width * 0.5f;
        float cy = 15.f;
        float tw = s_font ? s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, buf).x : ImGui::CalcTextSize(buf).x;
        float pad = 8.f;
        Utils::RectFilled(dl, ImVec2(cx - tw/2 - pad, cy - pad/2), ImVec2(cx + tw/2 + pad, cy + fs + pad/2 + 2), IM_COL32(0, 0, 0, 160), 6.f);
        Utils::TextCentered(dl, cx, cy, Colors::COLOR_VISIBLE, buf, fs);
    }
}

// ==================== 2. BOTTOM INFO ====================
namespace BottomInfo {
    static void Draw(ImDrawList* dl) {
        if (!dl || !s_font) return;
        float fs = 14.f; // Slightly larger so it stays readable on any background
        float x = 15.f, y = Height - 95.f;
        float gap = 22.f;

        // Panel behind the text so it reads on any game background.
        Utils::RectFilled(dl, ImVec2(x - 8.f, y - 8.f), ImVec2(x + 260.f, y + 3 * gap + 8.f), IM_COL32(0, 0, 0, 180), 8.f);

        // 1. Brand name (bright accent blue)
        Utils::Text(dl, x, y, IM_COL32(0, 180, 255, 255), "Nexora", fs + 2.f);

        // 2. Telegram link (white / grey)
        Utils::Text(dl, x, y + gap, IM_COL32(220, 220, 220, 230), "https://t.me/ShorekeeperID", fs);

        // 3. Tagline (neon green)
        Utils::Text(dl, x, y + gap * 2.f, Colors::COLOR_GREEN, "Best Cheat", fs);
    }
}

// ==================== 3. ESP LANE (Top Anchor) ====================
namespace ESPLane {
    static void Draw(ImDrawList* dl, const std::vector<Entity>& ents) {
        if (!dl || !s_ff.bLane) return;
        ImVec2 anchor(Width * 0.5f, 10.f);
        for (const auto& e : ents) {
            if (SkipEntity(e)) continue;
            if (e.distance > s_ff.fMaxDist) continue;
            ImVec2 head;
            if (e.GetBonePosition(BONE_HEAD, head) && Utils::IsInBounds(head)) {
                // Lines stay white (red only when knocked) so class is read from
                // the name-tag badge, not from the lane colour.
                ImU32 laneCol = ClassColor(e, e.is_visible ? 180 : 80);
                Utils::Line(dl, anchor, head, laneCol, 1.f);
            }
        }
    }
}

// ==================== AIM BONE ====================
// The Target Bone combo writes iAIM_TargetBone. Python resolves that index with
// _AIM_BONE_MAP in srcpy.cpp: 0 Head, 1 Neck, 2 Chest (biped Spine1), 3 Spine,
// 4 Pelvis. The aim line has to follow the SAME bone - it used to be hardcoded
// to the neck, so the line pointed at the neck while the shot went to the head,
// chest, spine or pelvis the user had actually selected.
static inline BoneIdx AimBoneFromConfig() {
    auto it = sync_int.find("iAIM_TargetBone");
    const int idx = (it != sync_int.end()) ? it->second : 1;
    switch (idx) {
        case 0:  return BONE_HEAD;
        case 1:  return BONE_NECK;
        case 2:  return BONE_SPINE1;   // menu label "Chest"
        case 3:  return BONE_SPINE;
        case 4:  return BONE_PELVIS;
        default: return BONE_NECK;
    }
}

// Selected bone first, then the same fallback chain Python uses (_aim_bone_pos):
// a model that lacks the requested bone must not make the lane vanish.
static inline bool GetAimBonePos(const Entity& e, BoneIdx bone, ImVec2& out) {
    if (bone != BONE_NONE && e.GetBonePosition(bone, out)) return true;
    if (e.GetBonePosition(BONE_NECK, out))                return true;
    return e.GetBonePosition(BONE_HEAD, out);
}

// ==================== 4. AIM LANE (Bottom Anchor) ====================
namespace AimLane {
    static void Draw(ImDrawList* dl, const std::vector<Entity>& ents) {
        if (!dl || !s_ff.bAIM || !s_ff.bAimLane) return;

        // Mirror the candidate filter of AIMUpdate() in srcpy.cpp, otherwise the
        // line happily points at an entity the aimbot has been told to ignore
        // (bots / knocked / behind cover) and the two disagree on screen.
        const bool ignoreBots    = sg("bAIM_IgnoreBots", false);
        const bool ignoreKnocked = sg("bAIM_IgnoreKnocked", true);
        const bool checkVis      = sg("bAIM_CheckVisibility", false);
        const BoneIdx aimBone    = AimBoneFromConfig();

        const Entity* bestTarget = nullptr;
        float bestScore = FLT_MAX;
        const float cx = Width  * 0.5f;
        const float cy = Height * 0.5f;
        // Same as the FOV circle in AimESP: the radius is in pixels.
        float fovR = s_ff.fAimFov;
        fovR = std::clamp(fovR, 10.f, 800.f);
        const float fovR2 = fovR * fovR;

        for (const auto& e : ents) {
            if (SkipEntity(e)) continue;
            // Python clamps the same value with min(fAIM_MaxDist, 500), so a
            // slider set to 600 used to leave the line tracking targets the
            // aimbot had already discarded.
            if (e.distance > std::min(s_ff.fAimMaxDist, 500.f)) continue;
            if (ignoreBots && e.is_bot) continue;
            if (ignoreKnocked && e.is_knocked) continue;

            // Only when the aimbot itself is doing the raycast. With the toggle
            // off Python reports every entity as visible, so gating on it here
            // would just be a no-op that hides the real setting.
            if (checkVis && !e.is_visible) continue;

            ImVec2 target;
            if (!GetAimBonePos(e, aimBone, target)) continue;
            if (!Utils::IsInBounds(target)) continue;

            // Pick the candidate closest to the crosshair instead of the one
            // closest in world space: the old code always pointed at the
            // nearest enemy even when it sat at the edge of the screen, so the
            // lane pointed somewhere unrelated.
            float dx = target.x - cx;
            float dy = target.y - cy;
            float d2 = dx * dx + dy * dy;
            if (d2 > fovR2) continue;
            if (d2 < bestScore) { bestScore = d2; bestTarget = &e; }
        }

        if (bestTarget) {
            ImVec2 anchor(cx, Height - 10.f);
            ImVec2 target;
            if (GetAimBonePos(*bestTarget, aimBone, target) &&
                Utils::IsInBounds(target)) {
                // The whole aim line uses the accent colour, not just the dot.
                // Knocked targets go full red. The final segment is drawn
                // thicker so the eye is pulled to the exact bone being aimed at.
                ImU32 tgtCol  = bestTarget->is_knocked ? Colors::COLOR_KNOCK
                                                       : Colors::COLOR_AIM_TARGET;

                const float segLen = 28.f;
                float dx = anchor.x - target.x;
                float dy = anchor.y - target.y;
                float len = std::sqrt(dx * dx + dy * dy);
                ImVec2 segStart = target;
                if (len > 1.f) {
                    float t = std::min(segLen, len) / len;                       // last <=28px before target
                    segStart = ImVec2(target.x + dx * t, target.y + dy * t);
                }

                Utils::Line(dl, anchor, segStart, tgtCol, 1.8f);               // full lane is the accent colour
                Utils::Line(dl, segStart, target, tgtCol, 2.4f);                // final segment on top of the accent
                dl->AddCircleFilled(target, 3.2f, tgtCol, 12);                  // target dot
                dl->AddCircle(target, 7.0f, Colors::WithAlpha(tgtCol, 160), 16, 1.4f); // target ring
            }
        }
    }
}

// ==================== 5. BOX ESP ====================
// bESP_Box already existed in the menu and defaults to true, but no code drew
// a box, so the toggle was dead. Brackets are white (red if knocked); class is
// read from the name-tag badge instead of the line colour.
namespace BoxESP {
    static void Draw(ImDrawList* dl, const Entity& e, const ImVec2& head, const ImVec2& foot) {
        if (!dl || !s_ff.bBox) return;

        float h = foot.y - head.y;
        if (h < 5.f) return;
        if (h > (float)Height * 2.5f) return;

        float w = h * 0.52f;
        if (w < 6.f) w = 6.f;
        if (w > (float)Width * 1.5f) w = (float)Width * 1.5f;

        float cx = (head.x + foot.x) * 0.5f;
        ImVec2 a(cx - w * 0.5f, head.y);
        ImVec2 b(cx + w * 0.5f, foot.y);

        if (!Utils::IsSaneScreen(a) || !Utils::IsSaneScreen(b)) return;
        if (a.x > b.x || a.y > b.y) return;

        ImU32 col   = ClassColor(e, 255);
        ImU32 shade = Colors::WithAlpha(IM_COL32(0, 0, 0, 255), e.is_visible ? 130 : 80);
        float thick = e.is_visible ? 1.4f : 1.0f;

        // Thin dark outline behind the bracket so it reads on bright scenery.
        Utils::Line(dl, ImVec2(a.x - 1.f, a.y), ImVec2(b.x + 1.f, a.y), shade, thick + 1.f);
        Utils::Line(dl, ImVec2(a.x - 1.f, b.y), ImVec2(b.x + 1.f, b.y), shade, thick + 1.f);
        Utils::Line(dl, ImVec2(a.x, a.y - 1.f), ImVec2(a.x, b.y + 1.f), shade, thick + 1.f);
        Utils::Line(dl, ImVec2(b.x, a.y - 1.f), ImVec2(b.x, b.y + 1.f), shade, thick + 1.f);

        // Corner brackets only - a full rectangle becomes noise with many targets.
        float cw = std::max(4.f, std::min(w * 0.25f, h * 0.25f));
        Utils::Line(dl, ImVec2(a.x, a.y), ImVec2(a.x + cw, a.y), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(a.x, a.y), ImVec2(a.x, a.y + cw), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(b.x - cw, a.y), ImVec2(b.x, a.y), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(b.x, a.y), ImVec2(b.x, a.y + cw), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(a.x, b.y - cw), ImVec2(a.x, b.y), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(a.x, b.y), ImVec2(a.x + cw, b.y), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(b.x - cw, b.y), ImVec2(b.x, b.y), col, thick + 0.6f);
        Utils::Line(dl, ImVec2(b.x, b.y - cw), ImVec2(b.x, b.y), col, thick + 0.6f);
    }
}

// ==================== 6. NAME PANEL (WITH KNOCK & WALL INDICATOR) ====================
namespace NamePanel {
    static void Draw(ImDrawList* dl, const Entity& e, const ImVec2& head) {
        if (!dl || !s_font) return;
        if (!s_ff.bName && !s_ff.bDistance) return;

        float fs = _ts(e.distance, 12.f);
        char distBuf[16];
        if (e.distance >= 1000.f) snprintf(distBuf, sizeof(distBuf), "%.1fkm", e.distance / 1000.f);
        else                      snprintf(distBuf, sizeof(distBuf), "%.0fm", e.distance);

        std::string nameStr;
        if (!e.name.empty()) nameStr = e.name.length() > 14 ? e.name.substr(0, 13) + "." : e.name;
        else nameStr = e.is_bot ? "robot" : "player";
        // The behind-wall marker now sits on the name so the class tag
        // (AI / PL) stays visible. It used to replace the tag with "[W]",
        // which erased the entity class as soon as it went behind cover.
        if (!e.is_visible) nameStr = "[W] " + nameStr;

        // Class decides both the tag and the text colour.
        const char* aiTag     = ClassTag(e);
        ImU32       tagBg     = ClassTagColor(e);
        ImU32       textColor = ClassColor(e, e.is_visible ? 255 : 150);

        float padX = 8.f, padY = 4.f;
        float segGap = 6.f;
        float aiW   = s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, aiTag).x;
        float nameW = s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, nameStr.c_str()).x;
        float distW = s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, distBuf).x;

        float aiBoxW   = aiW   + padX * 2.f;
        float nameBoxW = nameW + padX * 2.f;
        float distBoxW = distW + padX * 2.f;
        float rowH     = fs + padY * 2.f;
        float totalW   = aiBoxW + nameBoxW + distBoxW + segGap * 2.f;

        float bx = head.x - totalW * 0.5f;
        float by = head.y - rowH - 8.f;

        if (!Utils::IsInBounds(ImVec2(bx, by))) return;

        // Main panel background. Knocked entities get a FULL red plate - the
        // class badge no longer matters because the player is already down.
        ImU32 mainBg = e.is_knocked ? Colors::WithAlpha(Colors::COLOR_KNOCK, e.is_visible ? 245 : 200)
                                   : (e.is_visible ? Colors::COLOR_PANEL_BG : Colors::COLOR_HIDDEN_BG);
        Utils::RectFilled(dl, ImVec2(bx, by), ImVec2(bx + totalW, by + rowH), mainBg, 4.f);

        // Class tag background (AI / KNOCK / PL). Knocked -> solid red.
        Utils::RectFilled(dl, ImVec2(bx, by), ImVec2(bx + aiBoxW, by + rowH), tagBg, 4.f);
        // White text on the red plate so the name stays readable.
        ImU32 nameCol = e.is_knocked ? Colors::COLOR_VISIBLE : textColor;

        // Texts. The tag text is always white on its coloured pill; name and
        // distance follow nameCol (white on a red knocked plate).
        Utils::TextCentered(dl, bx + aiBoxW * 0.5f, by + padY - 1.f, Colors::COLOR_VISIBLE, aiTag, fs);
        Utils::TextCentered(dl, bx + aiBoxW + segGap + nameBoxW * 0.5f, by + padY - 1.f, nameCol, nameStr.c_str(), fs);
        Utils::TextCentered(dl, bx + aiBoxW + segGap + nameBoxW + segGap + distBoxW * 0.5f, by + padY - 1.f, nameCol, distBuf, fs);

        // HP + armor bar
        if (s_ff.bHealth) {
            float hp = e.HpPercent();
            float armor = e.ArmorPercent();
            float total = std::min(1.0f, hp + armor);
            float lineY = by + rowH + 2.f;
            float barH = 2.5f;

            Utils::RectFilled(dl, ImVec2(bx, lineY), ImVec2(bx + totalW, lineY + barH), IM_COL32(0,0,0,150), 1.f);
            // Red while knocked, green otherwise
            ImU32 barCol = e.is_knocked ? Colors::COLOR_KNOCK : Colors::COLOR_GREEN;
            Utils::RectFilled(dl, ImVec2(bx, lineY), ImVec2(bx + totalW * total, lineY + barH), barCol, 1.f);
        }
    }
}

// ==================== 7. WEAPON ESP ====================
namespace WeaponESP {
    static void Draw(ImDrawList* dl, const Entity& e, const ImVec2& foot) {
        if (!dl || !s_font) return;
        if (!s_ff.bWeapon) return;
        if (e.weapon_name.empty() || e.weapon_name == "Punk" || e.weapon_name == "Unknown") return;

        float fs = _ts(e.distance, 10.f);
        const char* wName = e.weapon_name.c_str();
        float tw = s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, wName).x;
        float x = foot.x - tw * 0.5f;
        float y = foot.y + 4.f;

        // More transparent when the target is behind a wall
        ImU32 bgCol = e.is_visible ? IM_COL32(0, 0, 0, 160) : IM_COL32(0, 0, 0, 100);
        Utils::RectFilled(dl, ImVec2(x - 6.f, y - 1.f), ImVec2(x + tw + 6.f, y + fs + 3.f), bgCol, 6.f);

        ImU32 wCol = e.is_visible ? IM_COL32(255, 215, 60, 220) : IM_COL32(150, 150, 150, 150);
        Utils::Text(dl, x + 1, y + 1, IM_COL32(0, 0, 0, 180), wName, fs);
        Utils::Text(dl, x, y, wCol, wName, fs);
    }
}

// ==================== 8. SKELETON ====================
namespace SkeletonESP {
    static void Draw(ImDrawList* dl, const Entity& e) {
        if (!s_ff.bSkeleton) return;
        // Distance cutoff: beyond 150 m the projection can become unreliable,
        // so the old version simply hid the skeleton at those ranges.
        if (e.distance > 150.f) return;

        // White lines (red when knocked), dimmer behind a wall.
        ImU32 col   = ClassColor(e);
        ImU32 dark  = e.is_visible ? IM_COL32(0, 0, 0, 140) : IM_COL32(0, 0, 0, 80);
        float thick = e.is_visible ? 1.6f : 1.2f;

        static constexpr BoneIdx full[][2] = {
            {BONE_HEAD, BONE_NECK}, {BONE_NECK, BONE_SPINE}, {BONE_SPINE, BONE_SPINE1},
            {BONE_SPINE1, BONE_PELVIS},
            {BONE_NECK, BONE_LSHOULDER}, {BONE_LSHOULDER, BONE_LELBOW}, {BONE_LELBOW, BONE_LHAND},
            {BONE_NECK, BONE_RSHOULDER}, {BONE_RSHOULDER, BONE_RELBOW}, {BONE_RELBOW, BONE_RHAND},
            {BONE_PELVIS, BONE_LTHIGH}, {BONE_LTHIGH, BONE_LKNEE}, {BONE_LKNEE, BONE_LFOOT},
            {BONE_PELVIS, BONE_RTHIGH}, {BONE_RTHIGH, BONE_RKNEE}, {BONE_RKNEE, BONE_RFOOT},
        };
        // Draw a tiered set of bones: all when close, fewer at medium range,
        // and only the spine/ pelvis at long range. This prevents drawing
        // garbage lines when the projection is marginal.
        const int cnt = (e.distance < 40.f) ? 15 : (e.distance < 80.f) ? 9 : 6;

        ImVec2 a, b;
        for (int i = 0; i < cnt; i++) {
            if (!e.GetBonePosition(full[i][0], a)) continue;
            if (!e.GetBonePosition(full[i][1], b)) continue;
            // Use IsInBounds (the backup's check) so that marginal projections
            // don't produce lines that stretch across the whole screen.
            if (!Utils::IsInBounds(a) || !Utils::IsInBounds(b)) continue;
            Utils::Line(dl, a, b, dark, thick + 1.4f);   // outline for contrast
            Utils::Line(dl, a, b, col,  thick);
        }

        ImVec2 headP;
        if (e.GetBonePosition(BONE_HEAD, headP) && Utils::IsInBounds(headP)) {
            float r = e.distance < 40.f ? 3.f : 2.f;
            dl->AddCircleFilled(headP, r + 1.4f, dark, 12);
            dl->AddCircleFilled(headP, r, col, 12);
        }
    }
}

// ==================== 9. AIM ESP (FOV CIRCLE) ====================
namespace AimESP {
    static void DrawFovCircle(ImDrawList* dl) {
        if (!dl || !s_ff.bAIM) return;

        // Pixel radius, identical to the value Python compares against
        // (max_fov = min(sync_attrs['fAIM_Fov'], 400)). Reading it from s_ff
        // keeps the circle and the aim lane on one number - they used to have
        // different fallbacks (60 here, 120 there) and drifted apart.
        float r = s_ff.fAimFov;
        r = std::clamp(r, 10.f, 800.f); // Safety clamp

        float cx = Width * 0.5f, cy = Height * 0.5f;
        const int segs = 96;

        // Main rainbow circle
        for (int i = 0; i < segs; i++) {
            float a0 = (float)i     / segs * 6.28318530718f - 1.57079632679f;
            float a1 = (float)(i+1) / segs * 6.28318530718f - 1.57079632679f;
            ImVec2 p0(cx + cosf(a0) * r, cy + sinf(a0) * r);
            ImVec2 p1(cx + cosf(a1) * r, cy + sinf(a1) * r);
            ImU32 col = Colors::Rainbow((float)i / segs);
            Utils::Line(dl, p0, p1, col, 2.2f);
        }
        // Glow circle
        for (int i = 0; i < segs; i++) {
            float a0 = (float)i     / segs * 6.28318530718f - 1.57079632679f;
            float a1 = (float)(i+1) / segs * 6.28318530718f - 1.57079632679f;
            ImVec2 p0(cx + cosf(a0) * (r + 3.f), cy + sinf(a0) * (r + 3.f));
            ImVec2 p1(cx + cosf(a1) * (r + 3.f), cy + sinf(a1) * (r + 3.f));
            ImU32 col = Colors::Rainbow((float)i / segs) & 0x30FFFFFF;
            Utils::Line(dl, p0, p1, col, 4.f);
        }
    }
}

// ==================== 10. LOOT ESP ====================
namespace LootItemESP {
    static void Draw(ImDrawList* dl, const std::vector<Entity>& ents) {
        if (!dl || !s_font || !s_ff.bLootESP) return;
        for (const auto& e : ents) {
            if (!e.IsWorldObject()) continue;
            if (e.distance > s_ff.fLootMaxDist) continue;
            if (!e.is_on_screen) continue;

            ImVec2 pos;
            if (!e.GetBonePosition(BONE_HEAD, pos) || !Utils::IsInBounds(pos)) continue;

            std::string label = e.item_label.empty() ? "Item" : e.item_label;
            if (e.item_count > 1) {
                char buf[160];
                snprintf(buf, sizeof(buf), "%s x%d", label.c_str(), e.item_count);
                label = buf;
            }
            std::string distStr = e.GetDistanceString();
            std::string full = label + "  [" + distStr + "]";

            float fs = 12.f;
            float tw = s_font->CalcTextSizeA(fs, FLT_MAX, 0.f, full.c_str()).x;
            float pad = 5.f;
            Utils::RectFilled(dl, ImVec2(pos.x - tw/2 - pad, pos.y - fs/2 - pad/2),
                               ImVec2(pos.x + tw/2 + pad, pos.y + fs/2 + pad/2), IM_COL32(0, 0, 0, 160), 4.f);
            Utils::TextCentered(dl, pos.x, pos.y - fs/2, IM_COL32(255, 215, 0, 255), full.c_str(), fs);
        }
    }
}

// ==================== MAIN DRAW ====================
inline void DrawAll(ImDrawList* dl, const std::vector<Entity>& ents) {
    if (!dl) return;
    if (Width <= 0 || Height <= 0) return;
    s_font = Utils::GetSafeFont();
    SnapshotFlags();
    if (!s_ff.bESP) return;

    ESPCount::Draw(dl, ents);
    BottomInfo::Draw(dl);
    ESPLane::Draw(dl, ents);
    AimLane::Draw(dl, ents);
    AimESP::DrawFovCircle(dl);
    LootItemESP::Draw(dl, ents);

    for (const auto& e : ents) {
        if (SkipEntity(e)) continue;
        if (!e.is_on_screen || e.bone_cache.isEmpty()) continue;
        if (e.distance > s_ff.fMaxDist) continue;

        ImVec2 head, foot;
        if (!e.GetBonePosition(BONE_HEAD, head) || !Utils::IsInBounds(head)) continue;
        if (!BoneHelper::GetFootPos(e, head, foot)) continue;
        if (head.y < 0.f || head.y > (float)Height + 100.f || foot.y < head.y) continue;

        BoxESP::Draw(dl, e, head, foot);
        NamePanel::Draw(dl, e, head);
        WeaponESP::Draw(dl, e, foot);
        SkeletonESP::Draw(dl, e);
    }
}
} // namespace ESP
