# Bento HOME release gates (Haier FF Pro)

Status: **implementation branch only**; do not install or switch the TV from this branch.

## Hardware / baseline
- Haier Android TV FF Pro / daan / m7632 / armeabi-v7a; Android 14; ~2 GB RAM.
- Existing HOME: `com.google.android.tvlauncher/.MainActivity`.
- Preview Bento: `io.freemytv.haierpreview.bento`, versionCode 8, versionName preview.7.
- Preview is **not** a HOME application. Its ADB implementation is intentionally disabled.
- Data: calendar credentials/cache are Android Keystore-encrypted, `allowBackup=false`; uninstalling the app or changing signing key will lose access. **Never uninstall the existing Bento to fix a signature mismatch.**
- Existing local restore-haier.ps1 was tested DRY RUN ONLY. Its ADB Wi-Fi port may change; it is not proven to recover actual HOME.
- Current stock launcher and Wi-Fi must remain enabled until HOME migration passes all tests.

## Gate A — signing / update continuity
1. Locate existing Bento signing identity **locally**, never upload raw keystore to GitHub repository or paste passwords into chat. An optional encrypted GitHub Actions secret can be considered with user's separate approval.
2. Collect installed APK signing-certificate SHA256 locally using a trusted Android build-tools `apksigner` / package-manager method; collect new APK signing-certificate fingerprint and ensure it is exactly the same.
3. Keep same package name for data-preserving updates and monotonically increase `versionCode`. Test `adb install -r`, never uninstall. Fail closed when certificate differs.
4. Add immutable versioned release artifacts and checksums; retain previous known-good APK signed with same key.
5. For stable HOME variant, prefer evolving the existing Bento package with matching signature. A separately signed app cannot inherit Android Keystore data from Bento; no silent migration claim.

## Gate B — HOME build
1. Preserve all calendar/shortcut functionality; add HOME intent filter only to a dedicated stable variant with explicit tests. Verify both source and compiled manifest.
2. Require working TV physical controls: HOME, Back, settings, HDMI/input selector, DPAD, sleep/wake, cold boot, network, keyboard and calendar time.
3. Read-only test first; then propose installing stable APK without changing default HOME. Get explicit owner approval before setting HOME.
4. Use supported `cmd package set-home-activity --user 0` **after verifying launcher resolve and current user**. Do not disable stock HOME as part of this operation.
5. Confirm cold boot + HOME across multiple trials; preserve stock HOME as first recovery. Validate actual rollback (not just dry run) before considering any further changes.
6. Never rely on ADB Wi-Fi to be reachable after modifying critical network packages. No root, recovery, firmware or factory-menu actions.

## Gate C — reversible Google cleanup
1. Begin with `tools/disable-haier-google-extras.ps1 -Mode Inspect`. Preserve output/journal on the owner PC.
2. Candidate voice stack: `com.google.android.katniss`, installed hotword enrollment; only after owner approves exact packages. Media apps only if owner confirms unused.
3. Treat `com.google.android.tvrecommendations` and `com.google.android.tvlauncher` as **protected until stable HOME is proven**. Separate review/rollback before touching them.
4. Never disable Google Play Services/Play Store, TTS and keyboard, Android TV remote, Calendar Sync, Cast, Setup Wizard, MediaTek TV services, HDMI/CEC, DRM, Wi-Fi/network services, accessibility/settings, TV input, VPN, or arbitrary `com.google.*`.
5. Compare actual PSS/MemAvailable and cold-start behavior; cache/RSS/PSS cannot simply be added as guaranteed RAM savings. Disabling a system package doesn't necessarily reclaim its read-only system image disk space.
6. No `uninstall`, `pm clear`, mass deletion or automatic reboot.

## Execution split
- GitHub: source, code reviews, tests, build/checksum/release.
- Personal Windows Agent 0.4.0: inspect permitted local files and status; does not have arbitrary shell capability. Do not claim remote ADB installs through it until tool support is verified.
- Device operations: owner-confirmed local ADB through supported executor with actual rollback checkpoint and connectivity verified.
