#include "../obfuscate.h"

const char* srcpy = O(R"srcpy(
import json, threading, functools, time, socket, os

# ============================================================
# 🔥 RUNTIME DATA (file-based, read-only from our side)
# ============================================================
# The C++ side owns data.json: the menu writes a flat {key: value} snapshot of
# the live toggles and we only read it. No port, no handshake, nothing lost if a
# datagram is dropped. Python still needs one socket to push entity data back.
#
# data.json is NOT config.json. config.json is the menu's own nested
# {"bool":..,"float":..,"int":..} settings file that has to survive a restart;
# we must never write it, and the C++ side keeps the two apart on purpose.
# The C++ side writes into GetConfigDir(), which is the *internal* app data
# directory (ANativeActivity::internalDataPath + "/Nexora/") whenever the
# activity is up - no storage permission needed, and unlike /storage/emulated/0
# it is guaranteed to exist and be writable. We must look in the same place.
# The exact path depends on how the sandbox rewrites it, so probe a few
# candidates and keep whichever exists. Getting this wrong is not cosmetic:
# sync_attrs stays empty, fWidth/fHeight fall back to 1080x720, and C++ then
# rescales every bone by Height/720 - the ESP drifts far off the players and
# wobbles as the camera pans.
# The package name is never hardcoded: this same script runs inside every build
# of the game (com.netease.newspike, ...newspikeme, sandboxed clones, ...), and a
# hardcoded name would point every path at the wrong app. C++ resolves it once
# from /proc/self/cmdline (see include/hpp/package.h) and hands it over as
# _PKG_OVERRIDE, so both sides always agree on the same data dir. Only if that is
# missing do we read cmdline ourselves - and if that fails too we return an empty
# string instead of guessing a name.
def _proc_pkg():
    try:
        name = _PKG_OVERRIDE
    except NameError:
        name = ''
    if not name:
        try:
            with open('/proc/self/cmdline', 'rb') as f:
                name = f.read().split(b'\x00')[0].decode('utf-8', 'replace')
        except Exception:
            name = ''
    # A sandboxed process is often "<pkg>:<suffix>"; the data dir is per package.
    return name.split(':')[0]

_PKG = _proc_pkg()
# Internal data dir only - the single path C++ writes (GetConfigDir()).
# The old list also had /storage/emulated/0/Nexora/data.json, which was C++'s
# fallback for "activity not up yet". That fallback is gone, and leaving the
# entry here would let a stale external file shadow the live internal one.
# /data/user/0 and /data/data are the same internal dir through different
# mounts; both spellings are kept because the sandbox may rewrite only one.
# Empty when _PKG could not be resolved at all: no paths, no false positives.
_DATA_CANDIDATES = [
    '/data/user/0/%s/files/Nexora/data.json' % _PKG,
    '/data/data/%s/files/Nexora/data.json'   % _PKG,
] if _PKG else []
DATA_PATH     = _DATA_CANDIDATES[0] if _DATA_CANDIDATES else ''
UDP_RECV_PORT = 50001   # Python -> C++ (ESP entity data)

_send_sock = None
_udp_initialized = False

def _init_udp():
    global _send_sock, _udp_initialized
    if _udp_initialized:
        return
    try:
        _send_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        _send_sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 2*1024*1024)
        _send_sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0x10)
        _send_sock.settimeout(0.001)
        _udp_initialized = True
    except Exception as e:
        print(f"[UDP] Init error: {e}")

def _write_udp(data):
    """Send ESP data to C++"""
    global _send_sock
    if not _udp_initialized:
        _init_udp()
    try:
        if isinstance(data, str):
            data = data.encode('utf-8')
        _send_sock.sendto(data, ("127.0.0.1", UDP_RECV_PORT))
    except Exception:
        pass

def _log(tag, msg):
    """Send a log line to C++ via UDP so it appears in Android logcat."""
    global _send_sock, _udp_initialized
    if not _udp_initialized:
        _init_udp()
    try:
        payload = json.dumps({"log": "%s | %s" % (tag, msg)})
        _send_sock.sendto(payload.encode('utf-8'), ("127.0.0.1", UDP_RECV_PORT))
    except Exception:
        pass

# ============================================================
# 🔥 DATA READER
# ============================================================
# sync_attrs is kept as a plain dict so every existing
# sync_attrs.get('bESP_...') call site works unchanged.
sync_attrs = {}
_data_mtime = None
_data_lock = threading.Lock()

def _read_data():
    """Re-read data.json if it changed on disk. Cheap: one stat per tick."""
    global sync_attrs, _data_mtime, DATA_PATH
    try:
        st = os.stat(DATA_PATH)
    except Exception:
        # Not at the current path yet (or it moved): re-probe the candidates so
        # we pick the file up as soon as C++ creates it.
        for p in _DATA_CANDIDATES:
            try:
                os.stat(p)
                DATA_PATH = p
                _data_mtime = None
                print(f"[CFG] data.json found at {p}")
                break
            except Exception:
                continue
        else:
            return
        try:
            st = os.stat(DATA_PATH)
        except Exception:
            return
    try:
        mtime = st.st_mtime_ns
        if mtime == _data_mtime:
            return
        with open(DATA_PATH, 'r') as f:
            parsed = json.load(f)
        if isinstance(parsed, dict):
            with _data_lock:
                sync_attrs.update(parsed)
            _data_mtime = mtime
    except Exception:
        pass

def _data_thread():
    while True:
        _read_data()
        time.sleep(0.1)

# ============================================================
# 🔥 START DATA READER
# ============================================================
# Settings come from data.json and nowhere else: whatever values C++ wrote are
# the values the features use.
_read_data()
if sync_attrs:
    print(f"[CFG] data.json = {DATA_PATH} ({len(sync_attrs)} keys)")
    _log("CFG", "data.json = %s (%d keys)" % (DATA_PATH, len(sync_attrs)))
else:
    print(f"[CFG] data.json not readable yet; pkg={_PKG} candidates={_DATA_CANDIDATES}")
    _log("CFG", "data.json not readable yet; pkg=%s" % _PKG)
threading.Thread(target=_data_thread, daemon=True).start()

# ============================================================
# 🔥 GAME IMPORTS — only what aim / visuals / weapon need
# ============================================================
from gclient.framework.entities.space import Space
from gclient.framework.util.replay_util import IsPlayerTeammate
from gclient.framework.camera.fps_placer import FpsPlacer
from gclient.gameplay.logic_base.entities.combat_avatar import CombatAvatar, PlayerCombatAvatar
from gclient.gameplay.logic_base.equips.weapon_case import WeaponCase
from gshare.formula import Distance3D

# Weapon display names for the ESP panel, read lazily from the game's own equip
# table. The old build_auto_wep_dictionary() walked every entry of
# gun_skin_template_base_data at import time to build four dicts (WEP,
# WEP_NAMES, WEP_SKIN_INFO, SKIN_TO_EQUIP) that only the skin hack used, and
# kept them alive for the whole session. This touches one dict entry per weapon
# actually on screen, and the table is one the game already has loaded.
try:
    import gclient.data.equip_data as _equip_data
except Exception:
    _equip_data = None

# Loot ESP labels. This used to be imported inside the hero-skin block, so the
# loot path depended on that block's try/except succeeding; it is a plain
# module-level import now that the skin code is gone.
try:
    import gclient.data.lobby_item_data as _lobby_item_data
except Exception:
    _lobby_item_data = None

def _wep_name(equip_id):
    try:
        info = _equip_data.data.get(equip_id) if _equip_data else None
        if info:
            name = info.get('name')
            if name:
                return name
    except Exception:
        pass
    return 'Punk'

_exc_last = {}

def _log_exc(tag):
    """Rate-limited exception report.

    TRY/HOOK used to swallow everything with a bare `except: pass`, so a
    NameError or a changed engine attribute looked exactly like "the feature is
    off". One line per tag per 5 s keeps logcat readable.
    """
    try:
        now = time.time()
        if now - _exc_last.get(tag, 0.0) < 5.0:
            return
        _exc_last[tag] = now
        import traceback
        tb = traceback.format_exc().replace('\n', ' | ')
        print("[NEXORA][ERR][%s] %s" % (tag, tb[:400]))
    except Exception:
        pass

def HOOK(clazz, bLog=False, _name=None):
    def decorator(func, _name=_name):
        name = func.__name__
        _name = _name or '_' + name
        setattr(clazz, _name, getattr(clazz, name, None))
        @functools.wraps(getattr(clazz, name, func))
        def wrapper(*args, **kwargs):
            try:
                return func(*args, **kwargs)
            except Exception:
                _log_exc(name)
                return None
        setattr(clazz, name, wrapper)
        return func
    return decorator

def TRY(_func=None, *, bLog=False):
    def decorator(original):
        @functools.wraps(original)
        def wrapper(*args, **kwargs):
            try:
                return original(*args, **kwargs)
            except Exception:
                _log_exc(original.__name__)
                return None
        return wrapper
    return decorator(_func) if _func else decorator



_XRAY_COLORS = ((1,0,0), (0,1,0), (1,1,1), (0,0.5,1), (1,0,1))

_ESP_BONES = [
    ('head',      'biped Head'),
    ('neck',      'biped Neck'),
    ('spine',     'biped Spine'),
    ('spine1',    'biped Spine1'),
    ('pelvis',    'biped Pelvis'),
    ('lshoulder', 'biped L UpperArm'),
    ('rshoulder', 'biped R UpperArm'),
    ('lforearm',  'biped L Forearm'),
    ('rforearm',  'biped R Forearm'),
    ('lhand',     'biped L Hand'),
    ('rhand',     'biped R Hand'),
    ('lthigh',    'biped L Thigh'),
    ('rthigh',    'biped R Thigh'),
    ('lknee',     'biped L Calf'),
    ('rknee',     'biped R Calf'),
    ('lfoot',     'biped L Foot'),
    ('rfoot',     'biped R Foot'),
]

# The C++ box needs a real bottom edge. Without a foot bone it falls back to a
# distance estimate that is far too tall, so these are fetched for every
# on-screen entity, independently of the skeleton toggle.
_BOX_BONES = [
    ('pelvis',  'biped Pelvis'),
    ('lfoot',   'biped L Foot'),
    ('rfoot',   'biped R Foot'),
]

def _ab_safe(inst):
    return True

def WorldToScreenPoint(worldpos):
    return Space._instance.camera.engine_camera.GetScreenPointFromWorldPoint(worldpos)

def IsVisible(local, target, targetId, distance=100):
    raycast = Space._instance.RawRaycast(local, distance, 19, with_trigger=False, to_pos=target)
    ownerid = getattr(raycast.Body, 'ownerid', None)
    if raycast.Body and ownerid:
        return ownerid == targetId
    return raycast.Flags == 7

def Distance(a, b):
    dx = b[0] - a[0]; dy = b[1] - a[1]; dz = b[2] - a[2]
    return (dx*dx + dy*dy + dz*dz) ** 0.5

def _get_item_id(entity):
    for attr in ('item_id', 'gid', 'id', 'proto_id'):
        try:
            v = getattr(entity, attr, None)
            if v:
                return int(v)
        except Exception:
            continue
    return 0

# ============================================================
# 🔥 DIAGNOSTICS
# ============================================================
# Both updates are wrapped in @TRY / bare `except: pass`, so a NameError or a
# missing attribute is completely invisible - the feature just does nothing.
# These lines go to logcat once every couple of seconds and are the fastest way
# to see which stage is failing.
_dbg_last_esp = 0.0
_dbg_last_aim = 0.0

def _dbg(tag, msg):
    try:
        print("[NEXORA][%s] %s" % (tag, msg))
    except Exception:
        pass
    try:
        _log("[%s]" % tag, msg)
    except Exception:
        pass

def _esp_diag(entities):
    global _dbg_last_esp
    now = time.time()
    if now - _dbg_last_esp < 2.0:
        return
    _dbg_last_esp = now
    with_bones = 0
    on_screen = 0
    for e in entities:
        if not isinstance(e, dict):
            continue
        if len(e.get('bones') or {}) > 1:
            with_bones += 1
        if e.get('is_on_screen'):
            on_screen += 1
    _dbg("ESP", "keys=%d skel=%s maxdist=%s ents=%d on_screen=%d with_bones=%d" % (
        len(sync_attrs), sync_attrs.get('bESP_Skeleton'),
        sync_attrs.get('fESP_MaxDistance'), len(entities), on_screen, with_bones))

def _aim_diag(msg):
    global _dbg_last_aim
    now = time.time()
    if now - _dbg_last_aim < 2.0:
        return
    _dbg_last_aim = now
    _dbg("AIM", msg)

# ============================================================
# 🔥 SDK DUMPER
# ============================================================
# Driven by the "Dump SDK" toggle (bSDKDump). It is EDGE triggered: the dump
# runs once when the flag flips false->true, on a worker thread, so the ESP/aim
# tick is never stalled. Nothing here may raise into the caller - every
# strategy records its outcome in dump_log.txt instead, because we do not know
# in advance which introspection accessors this engine build actually exposes.
#
# Output (first writable candidate wins):
#   /storage/emulated/0/Android/data/<pkg>/files/sdk   <- requested location
#   .../files/Nexora/sdk on internal storage           <- fallback
#     bones.txt          real bone names of a live model + hit/miss for ours
#     sdk/<module>.py    API of every gclient.* / gshare.* module
#     sdk/libGame_SDK.py class field offsets (drop-in for the old SDK)
#     dump_log.txt       which accessors worked, what failed and why
_sdk_prev_flag = False
_sdk_busy      = False
# Live state mirrored to the C++ dashboard over UDP.
_sdk_live_status = 'idle'
_sdk_live_root   = ''
_sdk_live_files  = []
_sdk_live_log    = []

try:
    import sys as _sys
except Exception:
    _sys = None

# Packages the overlay itself imports (Space, CombatAvatar, WeaponCase,
# FpsPlacer all live under these), so they are the ones worth dumping.
_SDK_PREFIXES   = ('gclient.', 'gshare.')
_SDK_MAX_MODS   = 900
_SDK_MAX_CLASS  = 600
_SDK_MAX_MEMBER = 400
_SDK_MAX_NAMES  = 512

# Which bone word to look for when suggesting a replacement for a MISS. The
# left/right side is handled separately so 'lshoulder' prefers a name that also
# mentions L/Left.
_SDK_BONE_TOKEN = {
    'head': 'head', 'neck': 'neck', 'spine': 'spine', 'spine1': 'spine',
    'pelvis': 'pelvis', 'lshoulder': 'arm', 'rshoulder': 'arm',
    'lforearm': 'forearm', 'rforearm': 'forearm', 'lhand': 'hand',
    'rhand': 'hand', 'lthigh': 'thigh', 'rthigh': 'thigh',
    'lknee': 'calf', 'rknee': 'calf', 'lfoot': 'foot', 'rfoot': 'foot',
}

def _sdk_dirs():
    # No package name resolved -> no candidate dirs (see _proc_pkg).
    if not _PKG:
        return []
    return [
        '/storage/emulated/0/Android/data/%s/files/sdk' % _PKG,
        '/sdcard/Android/data/%s/files/sdk' % _PKG,
        '/data/user/0/%s/files/Nexora/sdk' % _PKG,
        '/data/data/%s/files/Nexora/sdk' % _PKG,
    ]

def _sdk_pick_dir():
    """First candidate we can actually create and write into."""
    for d in _sdk_dirs():
        try:
            os.makedirs(d)
        except Exception:
            pass
        try:
            probe = os.path.join(d, '.probe')
            with open(probe, 'w') as f:
                f.write('ok')
            os.remove(probe)
            return d
        except Exception:
            continue
    return None

def _sdk_write(path, text):
    global _sdk_live_files, _sdk_live_root
    try:
        with open(path, 'w') as f:
            f.write(text)
        try:
            if _sdk_live_root:
                rel = os.path.relpath(path, _sdk_live_root)
            else:
                rel = os.path.basename(path)
        except Exception:
            rel = os.path.basename(path)
        if rel not in _sdk_live_files:
            _sdk_live_files.append(rel)
        try:
            _write_udp(json.dumps({"sdk_file": rel}))
        except Exception:
            pass
        return True
    except Exception:
        return False

def _sdk_coerce_names(v):
    """Turn whatever a bone accessor returned into a list of plain names."""
    if v is None:
        return []
    try:
        items = list(v)
    except Exception:
        return []
    names = []
    for item in items[:_SDK_MAX_NAMES]:
        try:
            if isinstance(item, str):
                names.append(item)
            else:
                n = getattr(item, 'name', None)
                if n is None:
                    n = getattr(item, 'Name', None)
                if n:
                    names.append(str(n))
        except Exception:
            continue
    return names

def _sdk_bone_names(model):
    """Best-effort list of a model's real bone names.

    Returns (names, how). `how` records the accessor that worked, which is the
    single most useful line in the log: it tells us what this build exposes and
    therefore how a future dumper should be written.
    """
    # 1) accessors that hand back the whole list at once
    for attr in ('GetBoneNames', 'GetAllBoneNames', 'GetBones', 'GetJointNames',
                 'GetSkeletonBoneNames'):
        fn = getattr(model, attr, None)
        if callable(fn):
            try:
                names = _sdk_coerce_names(fn())
                if names:
                    return names, attr + '()'
            except Exception:
                pass
    # 2) collections hanging off the model or its skeleton
    holders = [(model, 'model')]
    for side in ('skeleton', 'Skeleton', 'skeletal_mesh', 'SkeletalMesh'):
        try:
            h = getattr(model, side, None)
            if h is not None:
                holders.append((h, 'model.' + side))
        except Exception:
            pass
    for holder, label in holders:
        for attr in ('bones', 'Bones', 'joints', 'Joints', 'bone_names'):
            try:
                names = _sdk_coerce_names(getattr(holder, attr, None))
                if names:
                    return names, '%s.%s' % (label, attr)
            except Exception:
                pass
    # 3) indexed accessor
    cnt = None
    for attr in ('GetBoneCount', 'GetBonesCount', 'bone_count', 'BoneCount'):
        try:
            v = getattr(model, attr, None)
            cnt = int(v() if callable(v) else v)
            if cnt > 0:
                break
        except Exception:
            cnt = None
    if cnt:
        names = []
        for i in range(min(cnt, _SDK_MAX_NAMES)):
            for attr in ('GetBoneName', 'GetBoneNameByIndex', 'GetJointName'):
                fn = getattr(model, attr, None)
                if callable(fn):
                    try:
                        names.append(str(fn(i)))
                        break
                    except Exception:
                        continue
        if names:
            return names, 'GetBoneName(i)'
    return [], 'none'

def _sdk_side_ok(short_key, name):
    """Does the candidate name carry the same left/right side as our key?"""
    if short_key[0] not in ('l', 'r'):
        return True
    low = name.lower()
    want = ('l', 'left') if short_key[0] == 'l' else ('r', 'right')
    other = ('r', 'right') if short_key[0] == 'l' else ('l', 'left')
    def has(tokens, allow_suffix):
        for t in tokens:
            if allow_suffix:
                # 'biped L Arm' / 'Bip01 L_Calf' - a leading side marker
                if low.startswith(t + ' ') or low.startswith(t + '_') or low.startswith(t + '-'):
                    return True
                if (' ' + t + ' ') in low or ('_' + t + '_') in low:
                    return True
            elif t in low:
                return True
        return False
    if has(other, False) and not has(want, False):
        return False
    return True

def _sdk_suggest(short_key, real_names):
    """Plausible replacements for one of our bone names."""
    token = _SDK_BONE_TOKEN.get(short_key, short_key)
    hits = [n for n in real_names if token in n.lower() and _sdk_side_ok(short_key, n)]
    # prefer the same side, then the shortest (least decorated) name
    hits.sort(key=lambda s: (0 if _sdk_side_ok(short_key, s) else 1, len(s)))
    return hits[:6]

def _sdk_live_models():
    """(label, model) pairs taken from the same entities the ESP walks."""
    out = []
    # `genv` is provided by the engine in most builds, but a missing attribute
    # here used to kill the whole lookup (the space fallback never ran because
    # `getattr(genv, ...)` raised before we reached it). Resolve defensively.
    _genv = None
    try:
        _genv = genv
    except Exception:
        _genv = None
    try:
        av = getattr(_genv, 'avatar', None) if _genv is not None else None
        ca = getattr(av, 'combat_avatar', None) if av else None
        m = getattr(ca, 'model', None) if ca else None
        if m:
            out.append(('local:%s' % getattr(ca, 'name', '?'), m))
    except Exception:
        pass
    try:
        inst = None
        if _genv is not None:
            try:
                inst = getattr(_genv, 'space', None)
            except Exception:
                inst = None
        if not inst:
            inst = Space._instance
        for eid, ent in list((getattr(inst, 'entities', None) or {}).items()):
            try:
                if not isinstance(ent, CombatAvatar):
                    continue
                m = getattr(ent, 'model', None)
                if m:
                    out.append(('entity:%s' % eid, m))
                if len(out) >= 6:
                    break
            except Exception:
                continue
    except Exception:
        pass
    return out

def _sdk_dump_bones(root, log):
    """bones.txt - the file that actually fixes the skeleton and aim bones."""
    lines = ['=== NEXORA SDK DUMP / BONES ===',
             'package : %s' % _PKG,
             'time    : %s' % time.strftime('%Y-%m-%d %H:%M:%S')]
    models = _sdk_live_models()
    if not models:
        lines.append('NO LIVE MODEL FOUND - be in a match with at least one avatar.')
        _sdk_write(os.path.join(root, 'bones.txt'), '\n'.join(lines) + '\n')
        log.append('bones: no live model (not in a match?)')
        return False

    label, model = models[0]
    real, how = _sdk_bone_names(model)
    lines.append('model   : %s' % label)
    lines.append('accessor: %s' % how)
    lines.append('')

    if not real:
        lines.append('Could not enumerate bone names from this model.')
        lines.append('Attributes present on the model object:')
        try:
            names = [a for a in dir(model) if not a.startswith('_')]
            for a in names[:120]:
                lines.append('  ' + a)
            small = [a for a in names if 'bone' in a.lower() or 'skeleton' in a.lower()
                     or 'joint' in a.lower()]
            lines.append('')
            lines.append('bone/skeleton/joint attributes: %s' % (small or 'none'))
        except Exception as e:
            lines.append('dir() failed: %r' % (e,))
        _sdk_write(os.path.join(root, 'bones.txt'), '\n'.join(lines) + '\n')
        log.append('bones: model found but no bone list (%s)' % how)
        return False

    lines.append('--- OUR 17 NAMES ---')
    lines.append('%-12s %-24s %s' % ('key', 'engine name', 'result'))
    hits = 0
    misses = []
    for key, engine_name in _ESP_BONES:
        ok = False
        try:
            ok = bool(model.GetBoneWorldPosition(engine_name))
        except Exception:
            ok = False
        if ok:
            hits += 1
            lines.append('%-12s %-24s HIT' % (key, engine_name))
        else:
            miss = '%-12s %-24s MISS' % (key, engine_name)
            sugg = _sdk_suggest(key, real)
            if sugg:
                miss += '   -> try: %s' % ', '.join(sugg)
            lines.append(miss)
            misses.append((key, sugg))
    lines.append('')
    lines.append('--- REAL BONE LIST (%d) ---' % len(real))
    for n in real:
        lines.append('  ' + n)
    lines.append('')
    lines.append('--- AIM BONE MAP ---')
    for idx, name in sorted(_AIM_BONE_MAP.items()):
        try:
            ok = bool(model.GetBoneWorldPosition(name))
        except Exception:
            ok = False
        lines.append('%d %-16s %s' % (idx, name, 'HIT' if ok else 'MISS'))

    _sdk_write(os.path.join(root, 'bones.txt'), '\n'.join(lines) + '\n')
    log.append('bones: %d/%d of our names hit, real list=%d, accessor=%s'
               % (hits, len(_ESP_BONES), len(real), how))
    if misses:
        log.append('bones: MISS -> ' + ', '.join('%s' % k for k, _ in misses))
    return True

def _sdk_member_lines(cls):
    """Members of a class, read from __dict__ so descriptors never fire."""
    out = []
    try:
        members = list(vars(cls).items())
    except Exception as e:
        return ['    # vars() failed: %r' % (e,)]
    for name, val in members[:_SDK_MAX_MEMBER]:
        if name.startswith('__'):
            continue
        try:
            if callable(val):
                out.append('    def %s(...): ...' % name)
            elif isinstance(val, bool):
                out.append('    %s: bool = %s' % (name, val))
            elif isinstance(val, int):
                if 0 < val < 0x100000:
                    out.append('    %s: int = 0x%X' % (name, val))
                else:
                    out.append('    %s: int = %d' % (name, val))
            elif isinstance(val, float):
                out.append('    %s: float = %r' % (name, val))
            elif isinstance(val, str):
                s = val if len(val) <= 60 else val[:57] + '...'
                out.append('    %s: str = %r' % (name, s))
            elif isinstance(val, property):
                out.append('    %s: <property>' % name)
            else:
                out.append('    %s: %s' % (name, type(val).__name__))
        except Exception:
            continue
    return out

def _sdk_dump_api(root, log):
    """sdk/<module>.py for every engine module in sys.modules."""
    if _sys is None:
        log.append('api: sys unavailable')
        return 0
    outdir = os.path.join(root, 'sdk')
    try:
        os.makedirs(outdir)
    except Exception:
        pass

    try:
        pairs = [(n, m) for n, m in list(_sys.modules.items())
                 if n and m is not None and n.startswith(_SDK_PREFIXES)]
    except Exception as e:
        log.append('api: sys.modules failed: %r' % (e,))
        return 0
    pairs.sort()
    written = 0
    offset_classes = {}
    for modname, mod in pairs[:_SDK_MAX_MODS]:
        try:
            body = ['# module: %s' % modname,
                    '# dumped from the running engine',
                    'import sys', '']
            try:
                classes = [(n, v) for n, v in vars(mod).items()
                           if isinstance(v, type) and not n.startswith('_')]
            except Exception as e:
                body.append('# vars() failed: %r' % (e,))
                classes = []
            for cname, cls in classes[:_SDK_MAX_CLASS]:
                try:
                    bases = ', '.join(b.__name__ for b in cls.__bases__) or 'object'
                except Exception:
                    bases = 'object'
                body.append('class %s(%s):' % (cname, bases))
                mem = _sdk_member_lines(cls)
                body.extend(mem or ['    ...'])
                body.append('')
                # collect int-like members for the offsets file
                ints = {}
                try:
                    for mn, mv in list(vars(cls).items()):
                        if mn.startswith('_'):
                            continue
                        if isinstance(mv, int) and not isinstance(mv, bool) and 0 < mv < 0x100000:
                            ints[mn] = mv
                except Exception:
                    pass
                if ints:
                    offset_classes[cname] = ints
            body.append('')
            body.append('def %s(): ...' % ('Get' + modname.split('.')[-1].capitalize()))
            fname = modname.replace('.', '_') + '.py'
            if _sdk_write(os.path.join(outdir, fname), '\n'.join(body[:4000]) + '\n'):
                written += 1
        except Exception:
            continue

    # Offsets file in the same shape as the old backup SDK, so it is a drop-in.
    off = ['# Messiah Engine Python SDK', '# regenerated at runtime by the Nexora SDK dumper',
           '# class field offsets (best effort: every int-like class member)', '']
    for cname in sorted(offset_classes):
        off.append('class %s:' % cname)
        for mn, mv in sorted(offset_classes[cname].items()):
            off.append('    %s = 0x%X' % (mn, mv))
        off.append('')
    _sdk_write(os.path.join(outdir, 'libGame_SDK.py'), '\n'.join(off) + '\n')

    log.append('api: %d module file(s), %d class(es) with int members'
               % (written, len(offset_classes)))
    return written

def _sdk_run():
    global _sdk_busy, _sdk_live_status, _sdk_live_root, _sdk_live_files, _sdk_live_log
    t0 = time.time()
    log = []
    _sdk_live_files = []
    _sdk_live_log = []
    _sdk_live_status = 'running'
    try:
        root = _sdk_pick_dir()
        if not root:
            _dbg('SDK', 'no writable output directory')
            log.append('no writable output directory (tried %s)' % (_sdk_dirs(),))
            _sdk_live_status = 'failed: no writable dir'
            try:
                _write_udp(json.dumps({"sdk_reset": True, "sdk_root": "", "sdk_status": _sdk_live_status}))
            except Exception:
                pass
            return
        _sdk_live_root = root
        try:
            _write_udp(json.dumps({"sdk_reset": True, "sdk_root": root, "sdk_status": "running"}))
        except Exception:
            pass
        log.append('dir: %s' % root)
        try:
            _sdk_dump_bones(root, log)
        except Exception as e:
            log.append('bones: exception %r' % (e,))
        try:
            _sdk_dump_api(root, log)
        except Exception as e:
            log.append('api: exception %r' % (e,))
        log.append('elapsed: %.1fs' % (time.time() - t0))
        _sdk_write(os.path.join(root, 'dump_log.txt'), '\n'.join(log) + '\n')
        _sdk_live_status = 'done'
        try:
            _write_udp(json.dumps({"sdk_status": "done", "sdk_root": root,
                                    "sdk_files": _sdk_live_files,
                                    "sdk_count": len(_sdk_live_files),
                                    "sdk_elapsed": round(time.time() - t0, 1)}))
        except Exception:
            pass
        _dbg('SDK', 'dump finished -> %s' % root)
        for line in log:
            _dbg('SDK', line)
            _sdk_live_log.append(line)
    except Exception as e:
        _sdk_live_status = 'error: %r' % (e,)
        try:
            _write_udp(json.dumps({"sdk_status": _sdk_live_status}))
        except Exception:
            pass
        try:
            _dbg('SDK', 'dump failed: %r' % (e,))
        except Exception:
            pass
    finally:
        _sdk_busy = False

def _sdk_poll():
    """Cheap per-tick check. Only the false->true flip starts a dump."""
    global _sdk_prev_flag, _sdk_busy
    flag = bool(sync_attrs.get('bSDKDump', False))
    if flag and not _sdk_prev_flag and not _sdk_busy:
        _sdk_busy = True
        try:
            threading.Thread(target=_sdk_run, daemon=True).start()
        except Exception:
            _sdk_run()          # thread creation failed: run inline, still once
    _sdk_prev_flag = flag

@TRY
def EspUpdate(*_, **__):
    if not sync_attrs.get('bESP', True):
        return
    inst = genv.space
    if not isinstance(inst, Space) or not inst.entities or not genv.avatar:
        return
    if not _ab_safe(inst):
        return
    localEntity = genv.avatar.combat_avatar
    if not localEntity or not hasattr(localEntity, 'model'):
        return
    _cam            = Space._instance.camera
    _cp             = _cam.position
    cam_pos         = (_cp.x, _cp.y, _cp.z)
    _cam_pos        = _cp
    _eng_cam        = _cam.engine_camera
    _bXray          = sync_attrs.get('bXray', False)
    _bVisCheck      = sync_attrs.get('bVisCheck', False)
    _bFilterZombie  = sync_attrs.get('bFilterZombie', False)
    _bZombieHL      = sync_attrs.get('bZombieHighlight', False)
    _iXrayColor     = sync_attrs.get('iXrayColor', 0)
    xray_c          = _XRAY_COLORS[_iXrayColor if _iXrayColor < len(_XRAY_COLORS) else 0]

    def get_bone_screen(model, bone_name):
        try:
            wp = model.GetBoneWorldPosition(bone_name)
            if not wp:
                return None
            sp = _eng_cam.GetScreenPointFromWorldPoint(wp)
            return [sp.x, sp.y] if sp.z > 0.0 else None
        except:
            return None

    # Must match the key C++ actually writes (fESP_MaxDistance in draw.h /
    # ESP.h). The old spelling 'fESP_MaxDist' never existed in the config, so
    # the script always fell back to its own default.
    _MAX_DIST = float(sync_attrs.get('fESP_MaxDistance', 500.0))
    entities = []
    _bLootESP = sync_attrs.get('bLootESP', False)
    _LOOT_MAX_DIST = float(sync_attrs.get('fLootESP_MaxDist', 120.0))
    _bHealth = sync_attrs.get('bESP_Health', True)
    _bWeapon = sync_attrs.get('bESP_Weapon', True)
    _bSkeleton = sync_attrs.get('bESP_Skeleton', False)

    for entity_id, entity in list(inst.entities.items()):
        try:
            if _bLootESP and getattr(entity, 'IsItem', False):
                try:
                    pos = getattr(entity, 'position', None) or getattr(entity, 'pos', None)
                    if pos:
                        wpos = (pos.x, pos.y, pos.z)
                        dist_i = Distance(cam_pos, wpos)
                        if dist_i <= _LOOT_MAX_DIST:
                            screen = _eng_cam.GetScreenPointFromWorldPoint(pos)
                            is_on_screen_i = screen.z > 0.0
                            bones_i = {"head": [screen.x, screen.y]} if is_on_screen_i else {}
                            item_id = _get_item_id(entity)
                            item_proto = _lobby_item_data.data.get(item_id, {}) if (item_id and _lobby_item_data) else {}
                            item_label = item_proto.get('name', 'Unknown') if hasattr(item_proto, 'get') else 'Unknown'
                            item_count = 1
                            try:
                                item_count = int(getattr(entity, 'count', 0) or getattr(entity, 'item_count', 0) or 1)
                            except Exception:
                                pass
                            entities.append({
                                "is_item": True,
                                "item_label": item_label,
                                "item_count": item_count,
                                "distance": dist_i,
                                "is_on_screen": is_on_screen_i,
                                "bones": bones_i,
                            })
                except Exception:
                    pass
                continue

            if _bXray and isinstance(entity, CombatAvatar) and hasattr(entity, 'model'):
                entity.model.UseTechHighLightXray(param=xray_c, param2=(0,0,0,0), color2=xray_c)
            if not isinstance(entity, CombatAvatar):
                continue
            if not getattr(entity, 'model', None):
                continue
            if not entity.is_alive:
                continue
            if IsPlayerTeammate(entity_id):
                continue

            is_zombie = getattr(entity, 'IsRobotCombatAvatarZombie', False)
            if _bFilterZombie and is_zombie:
                continue
            if _bZombieHL and is_zombie:
                if hasattr(entity.model, 'UseTechHighLightXray'):
                    entity.model.UseTechHighLightXray(param=(1.0,0.5,0.0), param2=(0,0,0,0), color2=(1.0,0.5,0.0))

            entityHeadPos = entity.model.GetBoneWorldPosition('biped Head')
            if not entityHeadPos:
                continue

            _ehp   = (entityHeadPos.x, entityHeadPos.y, entityHeadPos.z)
            dist_e = Distance(cam_pos, _ehp)
            if dist_e > _MAX_DIST:
                continue

            model = entity.model
            head_screen = _eng_cam.GetScreenPointFromWorldPoint(entityHeadPos)
            is_on_screen = head_screen.z > 0.0

            bones = {}
            if is_on_screen:
                bones["head"] = [head_screen.x, head_screen.y]
                # Always fetch the bones the box needs. Gating these behind
                # bESP_Skeleton made the box fall back to a distance estimate
                # whenever the skeleton was off, which drew boxes several times
                # taller than the player.
                for key, bone_name in _BOX_BONES:
                    s = get_bone_screen(model, bone_name)
                    if s:
                        bones[key] = s

            if _bSkeleton and is_on_screen:
                for key, bone_name in _ESP_BONES:
                    s = get_bone_screen(model, bone_name)
                    if s:
                        bones[key] = s

            player_name  = getattr(entity, 'name', 'Unknown')
            is_bot       = bool(entity.IsRobotCombatAvatar)
            is_knocked = bool(entity.is_dying_state)
            is_visible = IsVisible(_cam_pos, entityHeadPos, entity.id) if _bVisCheck else True
            entry = {
                "name": player_name,
                "is_zombie": is_zombie,
                "is_bot": is_bot,
                "distance": dist_e,
                "is_on_screen": is_on_screen,
                "bones": bones,
                "is_visible": is_visible,
                "is_alive": True,
                "is_knocked": is_knocked,
            }
            # Only gather what the enabled C++ overlay actually draws. The
            # parser on the other side is j.value(key, default), so a key that
            # is left out simply falls back to its default - and the GetCurWeapon
            # lookup below is an engine call per entity per frame, so it is worth
            # skipping when the weapon label is off.
            if _bHealth:
                entry["is_health"] = float(getattr(entity, 'hp', 0))
                entry["cur_maxhp"] = float(getattr(entity, 'cur_maxhp', 100))
                entry["is_armor"] = float(getattr(entity, 'armor', 0.0))
            if _bWeapon:
                try:
                    w = entity.GetCurWeapon()
                    if w:
                        entry["weapon_name"] = _wep_name(int(w.equip_id))
                except: pass
            entities.append(entry)
        except:
            continue

    _esp_diag(entities)

    try:
        _cam_euler = _cam.euler_angles if hasattr(_cam, 'euler_angles') else None
        _cam_yaw   = float(_cam_euler.y) if _cam_euler else 0.0
        _payload = {
            "players": entities,
            "world_name": getattr(inst, 'world_name', 'Unknown'),
            "real_world_name": getattr(inst, 'real_world_name', ''),
            "is_moba_map": getattr(inst, 'is_moba_map', False),
            "is_replaying": getattr(inst, 'is_replaying', False),
            "total_entities": len(inst.entities),
            "cam_yaw": _cam_yaw,
        }
        # Only claim a resolution when it came from the config. Sending a guess
        # here is what made C++ rescale bones by Height/720 and draw the ESP
        # nowhere near the players; omitting the keys makes C++ draw 1:1.
        if 'fWidth' in sync_attrs and 'fHeight' in sync_attrs:
            _payload["screen_w"] = float(sync_attrs['fWidth'])
            _payload["screen_h"] = float(sync_attrs['fHeight'])
        _write_udp(json.dumps(_payload, separators=(",", ":")) + "\n")
    except:
        pass

FpsPlacerInstance   = None
_ai_last_hash       = 0
_ai_idle_frames     = 0
_ai_tune_frame      = 0
_AI_TUNE_INTERVAL   = 30
_aim_pos_track     = {}
_AIM_MAX_LEAD_TIME = 0.6
_AIM_MIN_DT        = 0.02
_AIM_MAX_DT        = 0.25

_AI_MODE_INT = {
    'idle':0,'close':1,'mid':2,'long':3,'sniper':4,
    'smg':5,'cqc':6,'rush':7,'shotgun':8,'melee':9,
    'sky':10,'vehicle':11,'dmr':3,
}

_AIM_BONE_MAP = {
    0:'biped Head', 1:'biped Neck', 2:'biped Spine1',
    3:'biped Spine', 4:'biped Pelvis',
}

def _aim_bone_pos(entity, bone_name):
    """World position to aim at, robust against a bone this model lacks.

    `GetBoneWorldPosition(a) or GetBoneWorldPosition(b)` is NOT a safe fallback:
    when the first name is missing from the skeleton the engine raises instead of
    returning None, so the second call never runs and the caller's bare `except`
    drops the whole candidate. Every entity then gets skipped and aim targets
    nothing - while ESP boxes still draw, because they only need 'biped Head'.
    Each lookup gets its own guard, and a model with no usable bone still aims at
    the entity origin rather than being discarded.
    """
    model = getattr(entity, 'model', None)
    if model is not None:
        for name in (bone_name, 'biped Head', 'biped Neck'):
            if not name:
                continue
            try:
                p = model.GetBoneWorldPosition(name)
                if p:
                    return p
            except Exception:
                continue
    try:
        return entity.position
    except Exception:
        return None

_AIM_SNIPER_IDS  = frozenset({39,47,77,103,122,45,133})
_AIM_SHOTGUN_IDS = frozenset({34,41,79,126})
_AIM_SMG_IDS     = frozenset({2,65,67,90,110,129})
_AIM_MELEE_IDS   = frozenset({33,84,95,96,97,106,107,112,131,134})

_AIM_PRESETS = {
    'sniper':  dict(snap=0.7,  fov=45.0,  pred=True,  bone=0, bspd=900.0),
    'long':    dict(snap=1.2,  fov=80.0,  pred=True,  bone=0, bspd=700.0),
    'mid':     dict(snap=2.0,  fov=120.0, pred=True,  bone=1, bspd=650.0),
    'smg':     dict(snap=3.0,  fov=150.0, pred=True,  bone=2, bspd=500.0),
    'close':   dict(snap=2.5,  fov=140.0, pred=True,  bone=1, bspd=600.0),
    'shotgun': dict(snap=3.5,  fov=170.0, pred=False, bone=2, bspd=400.0),
    'cqc':     dict(snap=4.5,  fov=190.0, pred=False, bone=2, bspd=400.0),
    'rush':    dict(snap=5.0,  fov=200.0, pred=False, bone=2, bspd=500.0),
    'melee':   dict(snap=6.0,  fov=200.0, pred=False, bone=4, bspd=300.0),
    'sky':     dict(snap=2.0,  fov=160.0, pred=True,  bone=1, bspd=600.0),
    'vehicle': dict(snap=3.5,  fov=180.0, pred=True,  bone=2, bspd=700.0),
}

def _cond_hash(mode, ec, ads, sprint, pose, hp_low, veh, sky):
    return hash((mode, min(ec, 6), ads, sprint, pose, hp_low, veh, sky))

@HOOK(FpsPlacer, 0)
def OnStaticTick(self, *_, **__):
    global FpsPlacerInstance
    if self:
        FpsPlacerInstance = self

def AIM_AutoTune(localEntity, enemy_data):
    global _ai_last_hash, _ai_idle_frames
    if not sync_attrs.get('bAIM_AutoTune', False):
        return
    try:
        if not enemy_data:
            _ai_idle_frames += 1
            if _ai_idle_frames > 10:
                sync_attrs.update({'iAI_Mode':0,'iAI_EnemyCount':0,'bAI_Active':False,'bAI_Changed':False})
                _aim_pos_track.clear()
            return
        _ai_idle_frames = 0
        dists       = [e['dist'] for e in enemy_data]
        avg_dist    = sum(dists) / len(dists)
        min_dist    = min(dists)
        enemy_count = len(enemy_data)
        try:
            w = localEntity.GetCurWeapon()
            wid = int(w.equip_id) if w else 0
        except: wid = 0
        is_ads    = bool(getattr(localEntity, 'is_ads', False))
        is_sprint = bool(getattr(localEntity, 'is_sprint', False))
        is_sky    = bool(getattr(localEntity, 'is_on_sky', False))
        is_veh    = bool(getattr(localEntity, 'is_vehicle_driver', False)) or bool(getattr(localEntity, 'is_vehicle_passenger', False))
        pose_type = int(getattr(localEntity, 'pose_type', 0))
        my_hp     = float(getattr(localEntity, 'hp', 100))
        my_maxhp  = float(getattr(localEntity, 'cur_maxhp', 100))
        hp_low    = (my_hp / max(my_maxhp, 1)) < 0.35
        try: optic = int(localEntity.GetCurWeaponPartOpticType() or 0)
        except: optic = 0
        if wid in _AIM_MELEE_IDS:   mode = 'melee'
        elif is_sky:                 mode = 'sky'
        elif is_veh:                 mode = 'vehicle'
        elif wid in _AIM_SNIPER_IDS and optic >= 4: mode = 'sniper'
        elif wid in _AIM_SHOTGUN_IDS:               mode = 'shotgun'
        elif wid in _AIM_SMG_IDS and min_dist < 20: mode = 'cqc'
        elif wid in _AIM_SMG_IDS:                   mode = 'smg'
        elif min_dist < 10:          mode = 'cqc'
        elif enemy_count >= 4 and avg_dist < 50: mode = 'rush'
        elif avg_dist > 120:         mode = 'long'
        elif avg_dist > 60:          mode = 'mid'
        else:                        mode = 'close'
        p = dict(_AIM_PRESETS.get(mode, _AIM_PRESETS['mid']))
        mod_flags = 0
        if enemy_count >= 3:
            p['fov'] = min(p['fov'] + 30.0, 220.0); mod_flags |= (1 << 4)
        if is_sprint:
            p['snap'] = min(p['snap'] * 1.5, 12.0); mod_flags |= (1 << 0)
        if is_ads and mode not in ('sniper', 'long'):
            p['snap'] = max(p['snap'] * 0.8, 0.5);  mod_flags |= (1 << 1)
        if pose_type == 2:
            p['snap'] = max(p['snap'] * 0.7, 0.4)
            p['fov']  = max(p['fov']  - 20.0, 30.0); mod_flags |= (1 << 2)
        if hp_low:
            p['snap'] = min(p['snap'] * 1.3, 12.0); mod_flags |= (1 << 3)
        cur_hash = _cond_hash(mode, enemy_count, is_ads, is_sprint, pose_type, hp_low, is_veh, is_sky)
        changed  = (cur_hash != _ai_last_hash)
        _ai_last_hash = cur_hash
        sync_attrs.update({
            'fAIM_SnapStrength': round(p['snap'], 2),
            'fAIM_Fov':          round(p['fov'], 1),
            'bAIM_Prediction':   p['pred'],
            'iAIM_TargetBone':   p['bone'],
            'fAIM_BulletSpeed':  p['bspd'],
            'iAI_Mode':          _AI_MODE_INT.get(mode, 0),
            'iAI_EnemyCount':    enemy_count,
            'fAI_AvgDist':       round(avg_dist, 1),
            'fAI_MinDist':       round(min_dist, 1),
            'iAI_ModFlags':      mod_flags,
            'bAI_Active':        True,
            'bAI_Changed':       changed,
            'fAI_SnapTuned':     round(p['snap'], 2),
            'fAI_FovTuned':      round(p['fov'], 1),
            'iAI_BoneTuned':     p['bone'],
        })
    except: pass

@TRY
def AIMUpdate(*_, **__):
    global FpsPlacerInstance, _ai_tune_frame, _aim_pos_track
    _USE_EULER = False
    try:
        if not sync_attrs.get('bAIM', False) or not genv.avatar:
            return
        localEntity = genv.avatar.combat_avatar
        if not localEntity or not localEntity.is_alive:
            return
        trigger = sync_attrs.get('iAIM_Trigger', 2)
        # Menu: 0=OFF, 1=On Shoot, 2=On ADS, 3=Both.
        # These flags do not exist on every avatar subclass, so they are read
        # defensively - and an *unreadable* flag never closes the gate. Gating on
        # a state we cannot read is indistinguishable from "aim is broken".
        _has_shoot   = hasattr(localEntity, 'is_shooting')
        _has_ads     = hasattr(localEntity, 'is_ads')
        _is_shooting = bool(getattr(localEntity, 'is_shooting', False))
        _is_ads      = bool(getattr(localEntity, 'is_ads', False))
        _firing      = (_is_shooting or _is_ads) or not (_has_shoot and _has_ads)
        if trigger == 0:
            _aim_diag("gate OFF (trigger=0)")
            return
        if trigger in (1, 2) and not _firing:
            _aim_diag("gate wait trig=%s ads=%s shoot=%s" % (trigger, _is_ads, _is_shooting))
            return
        if not FpsPlacerInstance:
            # Hook-injected instance is primary; fall back to the camera's
            # fps_placer if the OnStaticTick hook never fired.
            try:
                cam = Space._instance.camera
                if hasattr(cam, 'fps_placer') and cam.fps_placer:
                    FpsPlacerInstance = cam.fps_placer
            except: pass
        if not FpsPlacerInstance:
            # Last resort: rotate via the camera's euler angles directly
            # (ESP already reads cam.euler_angles, so this attribute exists).
            try:
                _cam = Space._instance.camera
                if hasattr(_cam, 'euler_angles') and _cam.euler_angles:
                    _ea = _cam.euler_angles
                    _ea.y = float(getattr(_ea, 'y', 0.0))
                    _ea.x = float(getattr(_ea, 'x', 0.0))
                    FpsPlacerInstance = _cam  # marker: use euler path below
                    _USE_EULER = True
                else:
                    return
            except:
                return
        _aim_diag("run trig=%s ads=%s shoot=%s fpp=%s euler=%s" % (
            trigger, _is_ads, _is_shooting, bool(FpsPlacerInstance), _USE_EULER))
        # The menu does not expose fWidth/fHeight, so they are usually absent
        # from data.json. Fall back to 1080×720 instead of returning early -
        # that early return permanently disabled aim while ESP worked fine,
        # because ESP does not need the screen centre.
        sw = sync_attrs.get('fWidth', 1080.0)
        sh = sync_attrs.get('fHeight', 720.0)
        if not (0 < sw <= 5000 and 0 < sh <= 5000):
            sw, sh = 1080.0, 720.0
        cx         = sw * 0.5
        cy         = sh * 0.5
        max_fov    = min(sync_attrs.get('fAIM_Fov', 180.0), 400.0)
        max_dist   = min(sync_attrs.get('fAIM_MaxDist', 300.0), 500.0)
        check_vis  = sync_attrs.get('bAIM_CheckVisibility', False)
        ignore_bots    = sync_attrs.get('bAIM_IgnoreBots', False)
        ignore_knocked = sync_attrs.get('bAIM_IgnoreKnocked', True)
        smoothing      = 0.00021 * sync_attrs.get('fAIM_SnapStrength', 1.5)
        selected_bone  = _AIM_BONE_MAP.get(sync_attrs.get('iAIM_TargetBone', 1), 'biped Neck')
        use_pred       = sync_attrs.get('bAIM_Prediction', True)
        bullet_speed   = max(sync_attrs.get('fAIM_BulletSpeed', 600.0), 50.0)
        try:
            camera_pos    = Space._instance.camera.position
            entities_dict = Space._instance.entities
            if not camera_pos or not entities_dict:
                return
        except: return
        _ai_tune_frame += 1
        if _ai_tune_frame >= _AI_TUNE_INTERVAL or sync_attrs.get('bAI_Changed', False):
            if _ai_tune_frame >= _AI_TUNE_INTERVAL:
                _ai_tune_frame = 0
            temp_enemies = []
            for entityId, entity in list(entities_dict.items()):
                try:
                    if not isinstance(entity, CombatAvatar) or not entity.is_alive: continue
                    try:
                        if IsPlayerTeammate(entityId): continue
                    except: continue
                    if ignore_bots:
                        try:
                            if entity.IsRobotCombatAvatar: continue
                        except: pass
                    try:
                        lp = getattr(localEntity, 'position', None)
                        cp = Space._instance.camera.position
                        dist3d = Distance3D(lp if lp else cp, entity.position)
                    except: continue
                    if dist3d <= max_dist:
                        temp_enemies.append({"dist": dist3d})
                except: continue
            AIM_AutoTune(localEntity, temp_enemies)
        candidates = []
        _st_bone = 0; _st_proj = 0; _st_fov = 0
        for entityId, entity in list(entities_dict.items()):
            try:
                if not hasattr(entity, 'is_alive') or not isinstance(entity, CombatAvatar): continue
                if not entity.is_alive: continue
                try:
                    if IsPlayerTeammate(entityId): continue
                except: continue
                if ignore_bots:
                    try:
                        if entity.IsRobotCombatAvatar: continue
                    except: pass
                if ignore_knocked:
                    try:
                        if entity.is_dying_state: continue
                    except: pass
                try:
                    lp = getattr(localEntity, 'position', None)
                    cp = Space._instance.camera.position
                    dist3d = Distance3D(lp if lp else cp, entity.position)
                except: continue
                if dist3d > max_dist: continue
                bone_pos = _aim_bone_pos(entity, selected_bone)
                if not bone_pos:
                    _st_bone += 1
                    continue
                if check_vis:
                    try:
                        # Guarded lookup: an unguarded GetBoneWorldPosition on a
                        # model without that bone raises, and the bare except
                        # below would then drop the whole candidate - aim silently
                        # stopped working with Check Visibility on.
                        head_pos = _aim_bone_pos(entity, 'biped Head')
                        if not head_pos or not IsVisible(camera_pos, head_pos, entity.id): continue
                    except: continue
                try:
                    sp = WorldToScreenPoint(bone_pos)
                    if not hasattr(sp, 'z') or sp.z <= 0.01:
                        _st_proj += 1
                        continue
                except Exception:
                    _st_proj += 1
                    continue
                pred_x, pred_y = sp.x, sp.y
                now_t = time.time()
                prev = _aim_pos_track.get(entityId)
                if use_pred and prev:
                    ptype = type(bone_pos)
                    px, py, pz, pt = prev
                    dt = now_t - pt
                    if _AIM_MIN_DT < dt < _AIM_MAX_DT:
                        try:
                            prev_world = ptype(px, py, pz)
                            prev_screen = WorldToScreenPoint(prev_world)
                            if hasattr(prev_screen, 'z') and prev_screen.z > 0.01:
                                vx = (sp.x - prev_screen.x) / dt
                                vy = (sp.y - prev_screen.y) / dt
                                lead_time = min(dist3d / bullet_speed, _AIM_MAX_LEAD_TIME)
                                lx = vx * lead_time
                                ly = vy * lead_time
                                lead_cap = max_fov * 1.5
                                lmag = (lx*lx + ly*ly) ** 0.5
                                if lmag > lead_cap and lmag > 0.0001:
                                    scale = lead_cap / lmag
                                    lx *= scale; ly *= scale
                                pred_x = sp.x + lx
                                pred_y = sp.y + ly
                        except: pass
                _aim_pos_track[entityId] = (bone_pos.x, bone_pos.y, bone_pos.z, now_t)
                dx = pred_x - cx; dy = pred_y - cy
                fov_dist = (dx*dx + dy*dy) ** 0.5
                if fov_dist <= max_fov:
                    candidates.append((fov_dist, pred_x, pred_y, dist3d, entityId))
                else:
                    _st_fov += 1
            except: continue
        # Prune aim tracker to prevent unbounded growth
        aim_ids = {c[4] for c in candidates}
        _aim_pos_track = {k: v for k, v in _aim_pos_track.items() if k in aim_ids}
        _aim_diag("stage bone=%d proj=%d fov_out=%d ok=%d regs=%d bone=%s maxdist=%.0f fov=%.0f" % (
            _st_bone, _st_proj, _st_fov, len(candidates), _tick_regs,
            selected_bone, max_dist, max_fov))
        if not candidates:
            return
        candidates.sort(key=lambda x: x[0])
        _, tx, ty, _, _ = candidates[0]
        dx = tx - cx; dy = ty - cy
        if abs(dx) < 1.5 and abs(dy) < 1.5:
            return
        yaw   = max(min(dx * smoothing, 5.0), -5.0)
        pitch = max(min(-dy * smoothing, 5.0), -5.0)
        _aim_diag("rotate cands=%d dx=%.1f dy=%.1f yaw=%.3f pitch=%.3f" % (
            len(candidates), dx, dy, yaw, pitch))
        if _USE_EULER:
            try:
                _ea = Space._instance.camera.euler_angles
                _ea.y += yaw
                _ea.x += pitch
                Space._instance.camera.euler_angles = _ea
            except: pass
        else:
            try:
                FpsPlacerInstance.Rotate(yaw, pitch)
            except: pass
    except: pass

# Registration state for the space post-tick - see _register_tick().
_tick_space = None
_tick_regs  = 0

def on_space_tick(dtime):
    try:
        _sdk_poll()
    except:
        pass
    try:
        if sync_attrs.get('bESP', True):
            EspUpdate()
        if sync_attrs.get('bAIM', False):
            AIMUpdate()
    except:
        pass

def _register_tick():
    """Register on_space_tick once per Space instance.

    This callback is the only thing that drives ESP *and* aim - neither is a
    direct engine hook, both are called from here. Registering unconditionally
    on every _on_enter_space adds another copy of the callback each match, and
    AIMUpdate would then apply its yaw/pitch delta once per copy per frame: aim
    overshoots and degrades with every match played. Guarding on the Space
    identity still re-registers when the engine hands out a new Space.
    """
    global _tick_space, _tick_regs
    try:
        sp = Space._instance
        if sp is None or sp is _tick_space:
            return
        sp.AddSpacePostTick(on_space_tick)
        _tick_space = sp
        _tick_regs += 1
        _dbg("AIM", "space post-tick registered (n=%d)" % _tick_regs)
    except Exception:
        _log_exc("register_tick")

@HOOK(PlayerCombatAvatar, 0)
def on_enter_space(self, *args, **kwargs):
    result = PlayerCombatAvatar._on_enter_space(self, *args, **kwargs)
    _register_tick()
    return result


# ============================================================
# WEAPON MODS
# ============================================================

@HOOK(WeaponCase, 0)
def GetWeaponAttrValue(self, name, default=None):
    result = WeaponCase._GetWeaponAttrValue(self, name, default)
    try:
        if sync_attrs.get('bNoRecoil', False):
            if name in ('HorizontalRecoilAnimScale', 'VerticalRecoilAnimScale'):
                return 0.0
            if 'Recoil' in name:
                return 0.0
        
        if sync_attrs.get('bNoSpread', False):
            if 'Scatter' in name or 'Spread' in name:
                return 0.0
        
        if sync_attrs.get('bNoSway', False):
            if name == 'SightStabilize' or 'Sway' in name:
                return 0.0
        
        if sync_attrs.get('bRapidFire', False):
            if 'FireIntervalTimeConf' in name:
                mult = sync_attrs.get('fFireRateMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'BurstIntervalTimeConf' in name:
                mult = sync_attrs.get('fFireRateMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bFastReload', False):
            if 'ReloadAnimTimeConf' in name:
                mult = sync_attrs.get('fReloadSpeedMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'EmptyReloadAnimTimeConf' in name:
                mult = sync_attrs.get('fReloadSpeedMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bInstantSwitch', False):
            if name == 'ChangeWeaponSpeed':
                mult = sync_attrs.get('fSwitchSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bInstantScope', False):
            if 'AdsUpAnimTimeConf' in name:
                mult = sync_attrs.get('fADSSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'AdsUpFovTime' in name:
                mult = sync_attrs.get('fADSSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bInfiniteAmmo', False):
            if 'Ammo' in name:
                return 9999
    except:
        pass
    return result

@HOOK(WeaponCase, 0)
def GetWeaponAttrValueImmediately(self, name, default=None):
    result = WeaponCase._GetWeaponAttrValueImmediately(self, name, default)
    try:
        if sync_attrs.get('bRapidFire', False):
            if 'FireIntervalTimeConf' in name:
                mult = sync_attrs.get('fFireRateMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'BurstIntervalTimeConf' in name:
                mult = sync_attrs.get('fFireRateMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bFastReload', False):
            if 'ReloadAnimTimeConf' in name:
                mult = sync_attrs.get('fReloadSpeedMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'EmptyReloadAnimTimeConf' in name:
                mult = sync_attrs.get('fReloadSpeedMult', 2.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bInstantSwitch', False):
            if name == 'ChangeWeaponSpeed':
                mult = sync_attrs.get('fSwitchSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
        
        if sync_attrs.get('bInstantScope', False):
            if 'AdsUpAnimTimeConf' in name:
                mult = sync_attrs.get('fADSSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
            if 'AdsUpFovTime' in name:
                mult = sync_attrs.get('fADSSpeedMult', 3.0)
                if mult != 1.0:
                    return result / max(mult, 0.1)
    except:
        pass
    return result

@HOOK(WeaponCase, 0)
def GetAmmo(self):
    if sync_attrs.get('bInfiniteAmmo', False):
        return 9999
    return WeaponCase._GetAmmo(self)

@HOOK(WeaponCase, 0)
def GetMaxAmmo(self):
    if sync_attrs.get('bInfiniteAmmo', False):
        return 9999
    return WeaponCase._GetMaxAmmo(self)

@HOOK(WeaponCase, 0)
def NeedReload(self):
    if sync_attrs.get('bInfiniteAmmo', False):
        return False
    return WeaponCase._NeedReload(self)

print("[ALL FEATURES] Loaded successfully!")
print("  - Weapon: No Recoil, No Spread, No Sway, Rapid Fire, Fast Reload, Instant Switch, Instant Scope, Infinite Ammo")
print("  - Player: God Mode, Fly, Teleport, Speed Hack, Super Jump, No Cooldown, Infinite Stamina, No Fall Damage, Water Walk, Air Stuck, No Gravity, No Flash")
print("  - Skin: Weapon Skin Hack, Hero Skin Hack")
print("  - Visual: ESP, Loot ESP, Skeleton ESP")
print("  - Aimbot: Auto Aim, Prediction, Auto Tune")
print("  - Misc: FPS Unlock, Kill Broadcast, Report Blocker")

)srcpy").chr();
