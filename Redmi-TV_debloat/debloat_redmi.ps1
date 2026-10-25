# Redmi TV (MiTV-AXFR0) Debloat Script
# Preserves Default Android TV Launcher (com.google.android.tvlauncher)
# Freezes telemetry, analytics, ad engines, and heavy background services to eliminate startup lag.

param(
    [string]$Device = ($env:REDMI_TV_HOST, $env:TV_HOST | Where-Object { $_ } | Select-Object -First 1)
)

if (-not $Device) {
    $Device = "192.168.1.31:5555"
}
if ($Device -notmatch ":") {
    $Device = "$Device`:5555"
}

Write-Host "Connecting to Redmi TV at $Device..." -ForegroundColor Cyan
adb connect $Device

$BLOATWARE = @(
    # Telemetry and Tracking Engines
    "com.miui.tv.analytics",
    "com.xiaomi.statistic",
    "com.google.android.feedback",

    # PatchWall and Ad Banners / Web Promo Content
    "com.mitv.tvhome.atv",
    "com.xm.webcontent",
    "com.google.android.tvrecommendations",

    # Background Bloatware & Unused Services
    "com.xiaomi.android.tvsetup.partnercustomizer",
    "android.autoinstalls.config.xioami.dangal",
    "com.xiaomi.mitv.updateservice",
    "com.xiaomi.mitv.tvmanager",
    "com.xiaomi.wfdsinkhelperservice",

    # Unused Preinstalled Media / System Apps
    "com.xiaomi.mitv.handbook",
    "com.xiaomi.mimusic2",
    "com.mitv.videoplayer",
    "com.mitv.gallery",
    "com.mitv.tvlock",
    "com.xiaomo.tv.milegal",
    "com.google.android.videos",
    "com.google.android.play.games"
)

Write-Host "`nFreezing bloatware packages..." -ForegroundColor Yellow
foreach ($pkg in $BLOATWARE) {
    Write-Host "Disabling: $pkg" -NoNewline
    $res = adb -s $DEVICE shell pm disable-user --user 0 $pkg 2>&1
    Write-Host " -> $res" -ForegroundColor Green
}

# Free RAM from cached background processes
adb -s $DEVICE shell am kill-all

Write-Host "`nDebloat complete! Currently disabled packages on $DEVICE:" -ForegroundColor Cyan
adb -s $DEVICE shell pm list packages -d
