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

## Get the APK without Android Studio (GitHub Actions)

1. Create a new GitHub repository.
2. Push this folder to it:
   ```bash
   cd PassportPhotoStudio-Android
   git init && git add . && git commit -m "Passport Photo Studio"
   git branch -M main
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab. The **Build APK** workflow runs automatically on push
   (or click *Run workflow*).
4. When it finishes, open the run and download the **PassportPhotoStudio-debug-apk** artifact.
   Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone and install it (enable "Install unknown apps" for your file manager).
   This is a debug build, so Android may warn it is from an unknown developer - that is expected.

## Build locally in Android Studio

Open the `PassportPhotoStudio-Android` folder in Android Studio (Koala or newer), let it sync,
then **Run** on a device/emulator (Android 10 / API 29 or newer).

## Notes

- Requires Android 10 (API 29) or newer.
- No internet permission is declared for the app itself; ML Kit uses Google Play Services,
  which is already present on the device, to fetch the segmentation model the first time.
- Saved files: JPG goes to **Pictures/PassportPhoto**, PDF to **Download/PassportPhoto**.
