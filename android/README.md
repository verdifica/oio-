# OIOIOI Android app

A native Android app for the same server. GitHub Actions builds it on every push to `main` that touches `android/`
and publishes the APK at https://github.com/verdifica/oio-/releases/download/android-latest/oioioi.apk

- **Opening the app makes a report** (`ReportActivity.kt`, `ReportFlow.kt`): the app is named "Hoyo Hoyo": say "Hey Google, abre Hoyo Hoyo" (or tap the
  icon). It finds the position, says "Reportando hoyo", and saves after 5 seconds unless cancelled; the driver can pick
  the type or add a photo first. Reports stay on the phone (`Reports.kt`), with the street address when the phone can
  look it up, and a bilingual claim report that can be shared with the photos.
- **Android Auto** (`car/OioiCarAppService.kt`): opening OIOIOI on the car screen starts a report the same way, with
  Guardar / Cancelar buttons.
- The earlier shared-map version (`MainActivity.kt` web view + `DriveService.kt` silent "oi oi oi" Drive mode) is still
  in the code but not linked from the app.

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
