# OIOIOI Android app

A native Android app for the same server. GitHub Actions builds it on every push to `main` that touches `android/`
and publishes the APK at https://github.com/verdifica/oio-/releases/download/android-latest/oioioi.apk

- **Phone screen**: the web app (map, flags, photos, reports) in a WebView.
- **Drive mode** (`DriveService.kt`): a foreground service, so it keeps working with the screen off or another app
  open. It follows GPS, listens silently for "oi, oi, oi" (or "hoyo" said alone) with the offline Vosk recognizer,
  says "Hoyo marcado" and saves after 5 seconds (say "cancela" or tap Cancel in the notification), and warns about
  hazards ahead. The notification has Flag hazard and Stop buttons. Optional steering-wheel button.
- **Android Auto** (`car/OioiCarAppService.kt`): a points-of-interest app showing nearby hazards on a map with
  **Flag hazard** and **Drive** buttons.

## Install for testing

1. On the phone, open the download link above and install (allow installing from the browser when asked).
2. Open OIOIOI and allow microphone, location and notifications.
3. Android Auto only shows apps from the Play Store unless developer mode is on: in the Android Auto settings on the
   phone, tap **Version** about 10 times, then open the ⋮ menu > **Developer settings** and turn on **Unknown sources**.
   Reconnect to the car; OIOIOI appears in the car's app launcher.

## Build locally

Needs JDK 17 and the Android SDK (API 34): `gradle -p android assembleDebug`. The Spanish speech model is unpacked from
`public/vendor/vosk/model-es.tar.gz` into the app's assets during the build. Builds are signed with the test key in
`app/debug.keystore` so each new build installs over the previous one; a Play Store release needs its own key.
