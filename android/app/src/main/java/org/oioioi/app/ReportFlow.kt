package org.oioioi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One report, from opening the app to saving it: finds the position, says "Reportando hoyo",
 * counts down 5 seconds (the driver can cancel, pick a type or add a photo), then saves it on the phone.
 * Used by both the phone screen and the car screen.
 */
class ReportFlow(private val ctx: Context, private val onChange: () -> Unit) : LocationListener {
    enum class State { COUNTING, PAUSED, WAITING_GPS, SAVED, CANCELLED }

    val report = Report()
    var state = State.COUNTING; private set
    var secondsLeft = 5; private set
    var location: Location? = null; private set
    private var waited = 0
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: String? = null
    private val tick = object : Runnable {
        override fun run() {
            when (state) {
                State.COUNTING -> { secondsLeft--; if (secondsLeft <= 0) saveNow() }
                State.WAITING_GPS -> { waited++; if (location != null || waited >= 15) save() }
                else -> return
            }
            onChange()
            if (state == State.COUNTING || state == State.WAITING_GPS) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        tts = TextToSpeech(ctx) { ok ->
            if (ok != TextToSpeech.SUCCESS) return@TextToSpeech
            tts?.language = Locale("es", "US")
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            ttsReady = true
            queued?.let { say(it) }; queued = null
        }
        startGps()
        say("Reportando hoyo. Se guarda en cinco segundos.")
        handler.postDelayed(tick, 1000)
        onChange()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        stopGps()
        // Let the last sentence finish before closing the voice.
        val t = tts; tts = null
        handler.postDelayed({ t?.shutdown() }, 4000)
    }

    fun setType(t: String) { report.type = t; if (state == State.SAVED) ReportStore.save(ctx, report); onChange() }

    /** Stops the countdown, for example while a photo is taken. */
    fun pause() { if (state == State.COUNTING) { state = State.PAUSED; handler.removeCallbacks(tick); onChange() } }

    fun saveNow() {
        if (state == State.SAVED || state == State.CANCELLED || state == State.WAITING_GPS) return
        handler.removeCallbacks(tick)
        if (location == null) { state = State.WAITING_GPS; waited = 0; handler.postDelayed(tick, 1000); onChange() } else save()
    }

    fun cancel() {
        if (state == State.SAVED) return
        handler.removeCallbacks(tick); state = State.CANCELLED; stopGps()
        say("Reporte cancelado.")
        onChange()
    }

    private fun save() {
        handler.removeCallbacks(tick)
        location?.let { report.lat = it.latitude; report.lng = it.longitude; report.accuracy = if (it.hasAccuracy()) it.accuracy else null }
        stopGps()
        ReportStore.save(ctx, report)
        state = State.SAVED
        say(if (report.lat == null) "Reporte guardado, sin ubicación." else "Reporte guardado.")
        onChange()
        val lat = report.lat; val lng = report.lng
        if (lat != null && lng != null) Api.io.execute {
            ReportStore.lookupAddress(ctx, lat, lng)?.let { a ->
                report.address = a
                ReportStore.save(ctx, ReportStore.get(ctx, report.id)?.also { it.address = a } ?: report)
                handler.post(onChange)
            }
        }
    }

    private fun say(text: String) {
        if (!ttsReady) { queued = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r" + System.nanoTime())
    }

    // ---------- position ----------
    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        // A recent fix is good enough to start with; a fresh GPS fix replaces it as soon as it arrives.
        DriveService.instance?.location?.let { if (System.currentTimeMillis() - it.time < 30000) location = it }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 120000) offer(it) }
            } catch (e: Exception) {}
        }
    }

    private fun stopGps() { try { ctx.getSystemService(LocationManager::class.java).removeUpdates(this) } catch (e: Exception) {} }

    private fun offer(l: Location) {
        val cur = location
        val better = cur == null || l.time > cur.time + 20000 || (l.hasAccuracy() && (!cur.hasAccuracy() || l.accuracy <= cur.accuracy))
        if (better) { location = l; onChange() }
    }

    override fun onLocationChanged(l: Location) = offer(l)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
