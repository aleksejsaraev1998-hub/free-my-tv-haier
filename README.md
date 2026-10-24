# Free My TV — Haier FF Pro preview

Safe standalone preview for Haier Android TV FF Pro (MediaTek m7632, Android 14, armeabi-v7a, 2 GB RAM).

The build reads upstream [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) at reviewed commit `aa34335d25fa2224053d1bb16851e4522a6bbeb0`. It does not modify upstream.

## Preview behavior

- Separate package: `io.freemytv.haierpreview`.
- Opens as a normal TV app; no HOME category or boot/update receiver.
- ADB transport replaced by a throwing stub. The Free My TV / Original switch cannot change the TV in this variant.
- Stock launcher, system packages, screensaver and accessibility settings are not changed by this app.
- Russian defaults, Chelyabinsk weather, Celsius, no crypto or TMDB/Watchmode keys. Weather uses Open-Meteo.
- This is a UI compatibility check, not a completed debloat or a measured RAM improvement.

## Build

Actions → **Haier Android TV 32-bit preview** → Run workflow.

The workflow installs JDK/SDK only on the GitHub runner and publishes an artifact containing the APK, SHA256, signing certificate and manifest information. No Git or Android SDK installation is needed on the user's PC.

The Java/Dex APK contains no native libraries and supports 32-bit armeabi-v7a. minSdk 26, targetSdk 33, compileSdk 34. Actual GPU performance and device behavior still require a TV test.

## Signing

By default every CI run creates a disposable signing key. To retain update compatibility, set repository secret `HAIER_PREVIEW_KEYSTORE_BASE64` to the base64-encoded existing preview keystore. Upstream build.sh uses alias/password `freemytv`. Never commit the key, include it in artifacts, or print it.

A different signing key cannot update an installed copy. Removing only the preview app permits installation but loses that app's settings.

## Device baseline

See [docs/haier-baseline.md](docs/haier-baseline.md). Raw diagnostic logs, Wi-Fi details, ADB pairing data and local backups are intentionally not published here.

No TV installation or system mode switch is part of this workflow. Installation and any future system changes must be agreed separately.

Upstream is MIT licensed; see [LICENSE.upstream](LICENSE.upstream).
