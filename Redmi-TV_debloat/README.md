# Redmi TV Debloat (MiTV-AXFR0 / Android TV 11)

Connected via ADB at `192.168.1.31:5555`.

## Strategy
- **Default Launcher Kept**: The stock Android TV Launcher (`com.google.android.tvlauncher`) is preserved as requested.
- **Hardware Integrations Preserved**: All hardware, display backlight, picture controls (`mitv.service`), Bluetooth remotes (`com.mi.bluetooth`), and HDMI inputs (`com.droidlogic.tvinput`) are untouched.
- **Bloatware & Lag Sources Frozen**: All telemetry, PatchWall background overhead, ad servers, and unneeded media apps are disabled via `pm disable-user --user 0`.

## Frozen Packages Summary

| Package | Component | Impact on Startup Lag |
|---|---|---|
| `com.miui.tv.analytics` | MIUI Analytics | High (stops tracking & telemetry daemon) |
| `com.xiaomi.statistic` | Xiaomi Statistics | High (stops background data harvesting) |
| `com.mitv.tvhome.atv` | PatchWall Launcher | **Extreme** (stops full secondary launcher from consuming RAM/CPU) |
| `com.xm.webcontent` | Web Ad / Banner Engine | High (stops background HTTP fetching of promo banners) |
| `com.google.android.tvrecommendations` | Android TV Ad Recommendations | High (stops home screen sponsored trailer feeds) |
| `com.xiaomi.android.tvsetup.partnercustomizer` | Partner Apps Customizer | Medium (stops boot-time partner checks) |
| `android.autoinstalls.config.xioami.dangal` | Auto-install stub | Low |
| `com.xiaomi.mitv.updateservice` | Xiaomi OTA Update Service | Medium (prevents unwanted background OTA checks & re-enabling bloat) |
| `com.xiaomi.mitv.handbook` | Xiaomi Handbook | Low |
| `com.xiaomi.mimusic2` | Mi TV Music | Medium |
| `com.mitv.videoplayer` | Mi Video Player | Medium |
| `com.mitv.gallery` | Mi Gallery | Low |
| `com.mitv.tvlock` | Mi TV Lock | Low |
| `com.xiaomo.tv.milegal` | Legal terms daemon | Low |
| `com.google.android.videos` | Google Play Movies / TV | Low |
| `com.google.android.play.games` | Google Play Games | Low |
| `com.google.android.feedback` | Google Feedback | Low |

## How to Run
- To re-apply debloat: Run `powershell -ExecutionPolicy Bypass -File .\debloat_redmi.ps1`
- To restore all packages: Run `powershell -ExecutionPolicy Bypass -File .\restore_redmi.ps1`
