# Passport Photo Studio (Android)

A native Android app for producing print-ready passport / ID photos:

- Pick a photo from the gallery
- **Auto face-align** (levels the eyes, centers and sizes the head) + manual zoom / move / rotate
- Brightness, contrast, saturation and **skin-blur** (targets skin tones only)
- **One-tap background removal** (ML Kit Subject Segmentation), then pick a background color
  (white / blue / black suggestions + full custom RGB)
- Customizable **bottom strip** (candidate name, DOB, auto-filled date, extra lines; default white / black, fully editable)
- **Print layout**: choose page size (A4 default), auto-tiles as many photos as fit, exports **JPG + PDF** at 300 DPI, or prints directly

Face detection and background removal run **on-device** via Google ML Kit. The background-removal
model is fetched once through Google Play Services on first use, then works offline.

## Build

Requires JDK 17 or newer and the Android SDK with `platforms;android-36` and
`build-tools;36.0.0` (the app targets Android 16 / API 36, which Google Play
requires for new apps since 31 August 2026).

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease      # app/build/outputs/bundle/release/app-release.aab
```

## Signing the Play upload

The upload key never lives in this repo. Put a `keystore.properties` in the repo
root (it is gitignored), or set the same values as environment variables:

```properties
storeFile=/absolute/path/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Environment equivalents: `PASSPORT_KEYSTORE_FILE`, `PASSPORT_KEYSTORE_PASSWORD`,
`PASSPORT_KEY_ALIAS`, `PASSPORT_KEY_PASSWORD`. Without them `bundleRelease`
still builds, it just comes out unsigned.

## Install the debug build on a phone

Download `app-debug.apk` from the Actions artifact (or build it locally), copy it to the
phone and install it. Android will ask you to allow "Install unknown apps" for the file
manager, which is expected for a debug build.

## Build in Android Studio

Open this folder in Android Studio (Koala or newer), let it sync, then **Run** on a
device or emulator (Android 10 / API 29 or newer).

## Notes

- Requires Android 10 (API 29) or newer.
- No internet permission is declared for the app itself; ML Kit uses Google Play Services,
  which is already present on the device, to fetch the segmentation model the first time.
- Saved files: JPG goes to **Pictures/PassportPhoto**, PDF to **Download/PassportPhoto**.
