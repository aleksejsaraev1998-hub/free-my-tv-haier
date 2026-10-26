# Haier paired Wi-Fi ADB boot restore

Small separate APK, `io.freemytv.adbboot`, version 1.0. Restores only Android's `adb_wifi_enabled` flag once on LOCKED_BOOT_COMPLETED / BOOT_COMPLETED when explicitly enabled in its TV UI. It respects a disabled developer mode. No root, INTERNET permission, TCP port 5555, shell service, changed pairing keys, authorization-timeout change or persistent background loop.

This Haier resets Wi-Fi debugging after reboot. The user explicitly requested automatic restoration on 2026-10-26. Android requires a one-time ADB grant:

```
adb shell pm grant io.freemytv.adbboot android.permission.WRITE_SECURE_SETTINGS
```

The permission is broad; this audited implementation writes only the one Wi-Fi debugging key. Android still controls trusted networks and paired TLS authorization. Open the app once and choose **Включить автоподключение**. The app records the last boot operation in its device-protected preferences and displays the status. Disable automatic restoration in its UI, revoke the grant, or uninstall the helper to stop it.

```
adb shell pm revoke io.freemytv.adbboot android.permission.WRITE_SECURE_SETTINGS
adb uninstall io.freemytv.adbboot
```

Build requires JDK 17 and Android SDK 34. Run `bash maintenance/adb-boot/build.sh`. It produces an aligned unsigned APK; sign with the owner's retained helper key. Do not commit keystores or passwords. The helper has a separate signing identity from Bento HOME. On the owner's Windows PC the signed APK and key are retained under HaierBentoAgent; private signing data stays on that PC.

Device validation on Haier Android 14, 2026-10-26: installed and granted permission; enabled in UI; rebooted; paired Wi-Fi ADB advertised itself on a new mDNS port and reconnected without owner intervention. Bento HOME remained the foreground/default HOME. Post-boot UI status was inspected to verify the receiver ran. This helper cannot turn on Wi-Fi hardware, approve a new network, replace lost pairing, or bypass a system restriction. The ADB port may change; discover the service using mDNS.
