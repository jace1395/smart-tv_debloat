# Redmi TV (MiTV-AXFR0) Restore Script
# Restores all disabled packages if ever needed.

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
    "com.miui.tv.analytics",
    "com.xiaomi.statistic",
    "com.google.android.feedback",
    "com.mitv.tvhome.atv",
    "com.xm.webcontent",
    "com.google.android.tvrecommendations",
    "com.xiaomi.android.tvsetup.partnercustomizer",
    "android.autoinstalls.config.xioami.dangal",
    "com.xiaomi.mitv.updateservice",
    "com.xiaomi.mitv.tvmanager",
    "com.xiaomi.wfdsinkhelperservice",
    "com.xiaomi.mitv.handbook",
    "com.xiaomi.mimusic2",
    "com.mitv.videoplayer",
    "com.mitv.gallery",
    "com.mitv.tvlock",
    "com.xiaomo.tv.milegal",
    "com.google.android.videos",
    "com.google.android.play.games"
)

Write-Host "`nRe-enabling packages..." -ForegroundColor Yellow
foreach ($pkg in $BLOATWARE) {
    Write-Host "Enabling: $pkg" -NoNewline
    $res = adb -s $DEVICE shell pm enable $pkg 2>&1
    Write-Host " -> $res" -ForegroundColor Green
}

Write-Host "`nRestore complete!" -ForegroundColor Cyan
