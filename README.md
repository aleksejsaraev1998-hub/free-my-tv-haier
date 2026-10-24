# Free My TV — Haier FF Pro preview

Safe standalone preview for Haier Android TV FF Pro (MediaTek m7632, Android 14, armeabi-v7a, 2 GB RAM).

The build reads upstream [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) at reviewed commit `aa34335d25fa2224053d1bb16851e4522a6bbeb0`. It does not modify upstream.

## Bento Preview

The approved night Bento layout is implemented as a third, separate **bento** profile: `io.freemytv.haierpreview.bento`. It installs alongside Lite for rollback and keeps system HOME untouched. See [Bento features, installation limits and tests](docs/bento-preview.md). It includes a read-only CalDAV calendar agenda with on-TV credential entry and Keystore-encrypted credentials/cache, a local persistent thought, MET Norway weather, exchange rates, browser/VPN shortcuts and shelf selection. Local tasks have been replaced by the calendar panel. LAN board sync is not enabled.

## Features and comparison

See [the Russian feature comparison and TV test plan](docs/features-ru.md). Standard and Lite are separate app packages and can be installed side by side. Lite disables AGSL effects, caps image sizes and reduces animation/update rates; device performance remains unmeasured.

## Preview behavior

- Separate package: `io.freemytv.haierpreview`.
- Opens as a normal TV app; no HOME category or boot/update receiver.
- ADB transport replaced by a throwing stub. The Free My TV / Original switch cannot change the TV in either variant.
- Stock launcher, system packages, screensaver and accessibility settings are not changed by this app.
- Russian defaults, Chelyabinsk weather, Celsius, no crypto or TMDB/Watchmode keys. Weather uses Open-Meteo.
- This is a UI compatibility check, not a completed debloat or a measured RAM improvement.

## Build

Actions → **Haier Android TV 32-bit preview** → Run workflow.

The workflow builds **standard**, **lite** and **bento** profiles. Each produces its own artifact.

The workflow installs JDK/SDK only on the GitHub runner and publishes an artifact containing the APK, SHA256, signing certificate and manifest information. No Git or Android SDK installation is needed on the user's PC.

The Java/Dex APK contains no native libraries and supports 32-bit armeabi-v7a. minSdk 26, targetSdk 33, compileSdk 34. Actual GPU performance and device behavior still require a TV test.

## Signing

By default every CI run creates a disposable signing key. To retain update compatibility, set repository secret `HAIER_PREVIEW_KEYSTORE_BASE64` to the base64-encoded existing preview keystore. Upstream build.sh uses alias/password `freemytv`. Never commit the key, include it in artifacts, or print it.

The same optional secret can sign both distinct packages. A different signing key cannot update an installed copy. Removing only the preview app permits installation but loses that app's settings.

## Browser shortcut

Both profiles include a **Browser** shortcut on the shelf and in the bottom drawer. It opens an installed enabled browser with a launchable home activity, uses the system default when available, and offers a chooser for multiple candidates. Without a candidate it explains that a TV browser must be installed. No WebView, automatic installation, or page navigation is added. The globe icon uses Canvas primitives. Browser bookmarks remain in the browser. Device intent resolution and Back behavior still need a TV test.

## Device baseline

See [docs/haier-baseline.md](docs/haier-baseline.md). Raw diagnostic logs, Wi-Fi details, ADB pairing data and local backups are intentionally not published here.

No TV installation or system mode switch is part of this workflow. Installation and any future system changes must be agreed separately.

Upstream is MIT licensed; see [LICENSE.upstream](LICENSE.upstream).

## Safety checks

`tools/verify_preview.py` verifies both source and compiled manifests, an exact network-only permission list, the absence of HOME/boot hooks and native ABI dependencies, the APK signature, and the compiled ADB exception guards. Lite also checks that Scene bytecode creates no RuntimeShader. `tools/measure-preview.ps1` collects read-only diagnostics after a manual device test. No automated device writes are performed.
