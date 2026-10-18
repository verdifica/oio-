# OIOIOI phone prototype

A phone web app (it can be added to the home screen) for flagging road hazards hands-free.

## What it does

- **Drive mode** keeps the screen on, follows your GPS position and listens to the microphone.
- **Trigger**: shout your recorded shout (Menu > Record my shout, up to 3 recordings; "Play my shout" plays the latest, "Test my shout" shows a live meter and says "Recognized" without creating flags, and "Shout sensitivity" trades missed shouts against false alarms), or (the default, and most reliable) say a trigger word: "hoyo", "oioioi" or "ay ay ay" (Menu > Listen for, Trigger word). "Test my trigger" works for both. The big red **Flag hazard** button does the same.
- After a trigger the phone beeps, says "Hoyo marcado" and **saves automatically after 5 seconds** at your location. Before that you can pick the type (pothole, debris, flooding, accident, other), add a photo with the red camera button (this stops the timer; the photo opens on the map after saving). Photos taken at an already-flagged spot are added to that flag, up to 6 per flag, or cancel (tap, or say "cancela" in word mode).
- If an active flag already exists within 25 m, your flag is added to it as another reporter instead of creating a duplicate.
- Flags show as a red flag on a yellow circle. Fixed ones turn grey.
- Tap a flag for **Still there** (join the reporters), **It's fixed** (vote to clear) and **Claim report**. A flag is marked fixed once "fixed" votes reach half the number of reporters.
- **Warnings**: within 200 m of an active hazard ahead of you, the app shows a yellow banner, beeps and says "Pothole ahead, 120 meters".
- **Claim report**: a Spanish/English report with date, coordinates, Google Maps link, photo link, number of drivers confirming, and your details from Menu (name, address, license, optional car and plate). Your details stay on your phone only.
- **Export**: Menu > Download KML (Google My Maps, Google Earth) or GeoJSON (QGIS, ArcGIS, public GIS). The same feeds are live at `/api/export.kml` and `/api/export.geojson` (add `?all=1` to include fixed ones).

## Run it

Needs Node 18 or newer.

```
npm install
node server.js          # then open http://localhost:8080
```

Where flags are kept:
- **With `DATABASE_URL` set** to a Postgres connection string, flags and photos are stored in that database (tables `hazards` and `photos`, created automatically). The live app uses a free [Neon](https://neon.tech) database this way.
- **Without it**, flags go to `data/hazards.json` and photos to `data/photos/`. Fine for trying it on one computer.

### On a phone

Phones only allow GPS and the microphone on `https://` pages (or `localhost`). Options:

1. **Temporary HTTPS link from your computer**: run the server, then `npx cloudflared tunnel --url http://localhost:8080` and open the `https://…trycloudflare.com` link it prints on the phone. Anyone with the link can see and add flags while it runs.
2. **USB (Android)**: connect the phone, open `chrome://inspect` on the computer, turn on port forwarding for 8080, then open `http://localhost:8080` on the phone.
3. **Hosted**: deploy to any Node host (Render, Fly.io, Railway, a small server). Set `DATABASE_URL` so flags survive restarts. `render.yaml` deploys it to Render's free plan.

To test at a desk without GPS, turn on Menu > "Place flags by tapping the map".

## Where flags are stored, and why

Recommendation: keep flags in our own store and publish them as an open KML/GeoJSON feed, instead of writing them into an existing public map.

| Option | Can we add hazards to it? | Catch |
|---|---|---|
| Google Maps | No public way to add reports to Maps navigation | Only Google's own in-app reports appear there. Google My Maps can import our KML as a separate map. |
| Waze | Only through the Waze for Cities partner program | Needs an approved partnership. Worth applying for later. |
| OpenStreetMap Notes | Yes, anonymously | Notes are meant for fixing map data. Mass app-made hazard notes get treated as spam, there is no "confirm" count, and closing a note needs an OSM account. |
| Own store + open feed (this prototype) | Yes | We run the server. In return we control confirms, clears, photos and claim reports, and the feed loads in Google My Maps, Google Earth and any GIS. |

Flags are stored in a free hosted Postgres database (Neon), so they survive restarts of the free Render server.

## Not in this prototype yet

- **Android Auto.** Android Auto only accepts certain app categories (navigation, points of interest, messaging, media). The hazard reporter would be a native Android app built with the Car App Library, using this same server.
- **Listening with the screen off.** Web apps can't keep the microphone on in the background; the native app will.
- **Accounts.** Each phone gets an anonymous id, so one person counts once per phone.
- **Shout matching** needs a sound that is well above the background noise (which it keeps measuring), at least a fraction of your recording's loudness, shaped like your recordings above 300 Hz (to skip engine rumble), and held for about half the length of your shout. It was tuned with synthetic car sounds and still needs real road testing. If it stays unreliable, the next step is a small trained sound recognizer (TensorFlow.js) using several shouts and recordings of car noise.
- **Approximate location**: if the phone reports accuracy worse than 100 m (for example Chrome without "precise location" on Android), the app says so and does not auto-save the flag.

## Files

- `server.js`: web server, API, KML/GeoJSON export, and storage (Postgres or local files).
- `public/index.html`: the whole app (map, listening, flags, report).
- `public/vendor/leaflet/`: map library (Leaflet 1.9.4, BSD-2 license), bundled so the app loads without a CDN.
- `public/manifest.webmanifest`, `public/sw.js`, `public/icon.svg`: home-screen install.

## Erase all flags

In the Neon console, open the project's **SQL Editor** and run:

```sql
TRUNCATE hazards, photos;
```
