# Bento HOME test build – deployment checklist

The GitHub Actions `bento-home` artifact is **a separate app**, `io.freemytv.haierpreview.home`. It is not the user's existing `io.freemytv.haierpreview.bento`, therefore it **cannot read or transfer** the existing Bento encrypted calendar credentials. This is an isolated navigation test, not a replacement/update of the existing Bento.

Successful CI is a static compilation and APK verification, not proof that the TV has accepted this build.

## Before a manual TV test
1. Fetch the **artifact from the passing, exact reviewed commit** (do not mix builds); verify `SHA256SUMS.txt` and the APK signer locally.
2. Inspect manifest, and confirm HOME is present only in the `bento-home` APK. Verify no boot receiver, package-changing ADB code or broad permissions.
3. Verify connected ADB serial and installed original Bento, keyboard and stock HOME, and have a recovery procedure that does not rely solely on Wi-Fi.
4. Keep original Google HOME enabled. Record current `cmd package resolve-activity ... HOME` and package states before and after.
5. For first test: **install separate APK only**, run from Applications and validate DPAD/Back, Settings, HDMI/CEC, sound, Wi-Fi, wake, clock/calendar placeholder.
6. Only with explicit owner approval: test selecting Bento HOME. Save exact before/after; restore original HOME if HOME/Back/Settings/input keys fail.
7. Cold boot and physical HOME button tests precede any optional Google TV package disablement.

## Signing
- Existing Bento package `io.freemytv.haierpreview.bento` may only be updated via `adb install -r` by an APK signed with its *exact existing certificate*, same package, higher versionCode.
- `signature.txt` on local PC was found, but is a prior generic build artifact and does **not** establish the signer of the installed Bento package.
- The temporary key produced by default GitHub Actions builds is **not suitable** for updating existing Bento. Don't uninstall Bento to resolve certificate mismatch.
- Do not publish keystores, credentials, or signed app configuration to GitHub. Any necessary owner-only signing setup happens as a distinct secured task.

## Device operations
No television ADB commands have been executed in preparing this branch. Personal Windows Agent 0.4.0 in this session provides file inventory and read access but no general-purpose shell; local ADB installation/verification must use an appropriately authorized executor or owner-run commands. Re-check that tooling before attempting installation.
