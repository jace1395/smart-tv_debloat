import sys
from tv_connection import run_adb, resolve_tv_device

# Dynamically resolve TV device (from CLI arg, $env:TV_HOST, adb devices, or network scan)
target_device = resolve_tv_device()
print(f"Targeting TV device: {target_device}")

print("\n1. Trimming OS and app caches...")
out = run_adb(["shell", "pm", "trim-caches", "2000M"])
print("trim-caches:", out.strip())

# Clear caches of specific apps with large caches
for pkg in ["com.mitv.tvhome.atv", "com.google.android.tvlauncher", "com.google.android.youtube.tv"]:
    out = run_adb(["shell", "pm", "clear", pkg])
    print(f"Cleared cache/data of {pkg}: {out.strip()}")

# Uninstall defunct/unused system bloat for user 0
dead_apps = [
    "com.google.android.music",       # Google Play Music (defunct since 2020)
    "com.google.android.videos",      # Google Play Movies & TV (defunct)
    "com.google.android.play.games",  # Play Games on TV
    "com.mitv.videoplayer",           # Xiaomi stock video player
    "com.mitv.tvhome.mitvplus"        # MiTV+
]

print("\n2. Removing defunct/unused bloat for user 0...")
for app in dead_apps:
    out = run_adb(["shell", "pm", "uninstall", "-k", "--user", "0", app])
    print(f"Uninstall {app}: {out.strip()}")

# Disable heavy background daemons that run permanently
bg_daemons = [
    "com.android.tv",                                # Live Channels background tuner service (24MB RAM)
    "com.mitv.milinkservice",                        # Xiaomi MiLink casting daemon (6MB RAM)
    "com.xiaomi.android.tvsetup.partnercustomizer"  # Xiaomi partner customizer daemon (3MB RAM)
]

print("\n3. Disabling background tuner and casting daemons...")
for app in bg_daemons:
    out = run_adb(["shell", "pm", "disable-user", "--user", "0", app])
    print(f"Disable {app}: {out.strip()}")

# Force stop lingering background processes
print("\n4. Force stopping background hogs...")
for app in ["com.android.vending", "com.xiaomi.mitv.tvmanager", "com.sonyliv", "com.spotify.tv.android"]:
    run_adb(["shell", "am", "force-stop", app])

# Set background process limit to 2 (drastically limits background apps from piling up)
run_adb(["shell", "settings", "put", "global", "background_process_limit", "2"])
print("Set background_process_limit to 2")

print("\n5. Checking updated RAM and Storage...")
mem = run_adb(["shell", "dumpsys", "meminfo"])
for line in mem.splitlines()[-10:]:
    print(line)
