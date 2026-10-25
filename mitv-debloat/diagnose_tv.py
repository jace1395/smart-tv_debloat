import json
import sys
from tv_connection import run_adb, resolve_tv_device

# Dynamically resolve TV device (from CLI arg, $env:TV_HOST, adb devices, or network scan)
target_device = resolve_tv_device()
print(f"Connected to TV device: {target_device}")

print("Fetching diskstats...")
diskstats = run_adb(["shell", "dumpsys", "diskstats"])
lines = diskstats.splitlines()

pkg_names, app_sizes, data_sizes, cache_sizes = [], [], [], []
for l in lines:
    if l.startswith("Package Names:"):
        try:
            pkg_names = json.loads(l.replace("Package Names:", "").strip())
        except Exception:
            pass
    elif l.startswith("App Sizes:"):
        try:
            app_sizes = json.loads(l.replace("App Sizes:", "").strip())
        except Exception:
            pass
    elif l.startswith("App Data Sizes:"):
        try:
            data_sizes = json.loads(l.replace("App Data Sizes:", "").strip())
        except Exception:
            pass
    elif l.startswith("Cache Sizes:"):
        try:
            cache_sizes = json.loads(l.replace("Cache Sizes:", "").strip())
        except Exception:
            pass

items = []
for p, a, d, c in zip(pkg_names, app_sizes, data_sizes, cache_sizes):
    items.append({
        "pkg": p,
        "app_mb": a / 1024 / 1024,
        "data_mb": d / 1024 / 1024,
        "cache_mb": c / 1024 / 1024,
        "total_mb": (a + d) / 1024 / 1024
    })

items.sort(key=lambda x: x["total_mb"], reverse=True)
print(f"\n{'Package':<48} | {'App MB':<8} | {'Data MB':<8} | {'Cache MB':<8} | {'Total MB':<8}")
print("-" * 92)
for it in items[:25]:
    print(f"{it['pkg']:<48} | {it['app_mb']:<8.1f} | {it['data_mb']:<8.1f} | {it['cache_mb']:<8.1f} | {it['total_mb']:<8.1f}")

print("\nFetching meminfo summary...")
meminfo = run_adb(["shell", "dumpsys", "meminfo"])
for line in meminfo.splitlines()[:45]:
    print(line)
