package org.oioioi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Drive mode. Runs as a foreground service so it keeps going with the screen off or another app open:
 * follows GPS, listens silently for "oi, oi, oi" (or "hoyo") with the offline Vosk recognizer, flags hazards
 * after a 5-second cancel window, and warns about hazards ahead.
 */
class DriveService : Service(), LocationListener, RecognitionListener {

    data class Settings(val listen: Boolean, val word: String, val count: Int, val wheel: Boolean) {
        companion object {
            fun parse(json: String?): Settings {
                val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { JSONObject() }
                return Settings(o.optString("mode", "voice") != "none", o.optString("word", "oioioi"), o.optInt("count", 3).coerceIn(2, 3), o.optBoolean("wheel", false))
            }
        }
    }

    private class Pending(val loc: Location)

    companion object {
        @Volatile var instance: DriveService? = null
        val listeners = CopyOnWriteArraySet<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        fun changed() = main.post { listeners.forEach { it() } }

        const val ACTION_START = "start"; const val ACTION_STOP = "stop"
        const val ACTION_FLAG = "flag"; const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "drive"

        fun start(ctx: Context, settings: String) {
            ctx.getSharedPreferences("oioioi", Context.MODE_PRIVATE).edit().putString("settings", settings).apply() // reused by the car screen
            ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(ACTION_START).putExtra("settings", settings))
        }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_STOP)) }

        // "Hoy" sounds exactly like "oi", so both count. The other words keep everyday speech from being heard as "oi".
        private val OI = setOf("oi", "hoy")
        private val DECOYS = ("hoy voy soy doy estoy oye oiga oigan oír hoyo hoyos toy boy muy uy ay ahí hay oh no sí y o a e u el la los las un una de del en con por para que qué como cómo cuando dónde donde yo tú él ella nosotros ellos me te se le lo mi tu su mis nos es está están era fue ser estar tener tengo tiene vamos va van ir ver mira mirar eso esto esa este aquí allá ya ahora mañana tarde noche día bien mal más menos casa carro calle radio música mamá papá trabajo trabajar llamar hola adiós gracias señor señora todos todo nada algo mucho poco grande pequeño calor frío agua luz rojo verde vuelta derecha izquierda tráfico horrible cansado temprano pon dame dime quiero puedo sabes sé hace hacer dijo digo bueno buena claro vale pues pero porque si también tampoco siempre nunca otro otra vez hora tiempo pare sigue sigo cuidado ojo coño wow huy epa cancela cancelar").split(" ")
    }

    var settings = Settings(true, "oioioi", 3, false); private set
    @Volatile var location: Location? = null; private set
    var status = "Starting…"; private set
    val flagging get() = pending != null

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var speech: SpeechService? = null
    private var model: Model? = null
    private var pending: Pending? = null
    private var lastTrigger = 0L
    private var heard = listOf<String>()
    private var heardAt = 0L
    private var fired = false
    private val warned = mutableSetOf<String>()
    private var session: MediaSession? = null
    private var silence: AudioTrack? = null
    private val saveRunnable = Runnable { save() }
    private val refreshRunnable = object : Runnable {
        override fun run() { HazardStore.refreshAsync { changed() }; handler.postDelayed(this, 30000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Notification buttons only make sense while Drive mode runs; anything else that isn't a start ends here.
        if (intent?.action != ACTION_START) {
            when (intent?.action) {
                ACTION_FLAG -> instance?.flag()
                ACTION_CANCEL -> instance?.cancel()
            }
            if (instance == null || intent?.action == ACTION_STOP) stopSelf()
            return START_NOT_STICKY
        }
        val first = instance == null
        settings = Settings.parse(intent?.getStringExtra("settings"))
        val micOk = settings.listen && granted(Manifest.permission.RECORD_AUDIO)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (micOk) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(1, notification(), types)
        instance = this
        if (first) {
            startGps()
            tts = TextToSpeech(this) { ok ->
                if (ok == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("es", "US")
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        // Don't listen to our own voice coming back through the car speakers.
                        override fun onStart(id: String?) { speech?.setPause(true) }
                        override fun onDone(id: String?) { handler.postDelayed({ speech?.setPause(false) }, 700) }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { speech?.setPause(false) }
                    })
                    say("Escuchando.")
                }
            }
            handler.post(refreshRunnable)
        } else stopListening()
        if (micOk) startListening() else setStatus(if (settings.listen) "Microphone not allowed" else "Buttons only")
        if (settings.wheel) startWheel() else stopWheel()
        changed()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopListening(); model?.close(); model = null
        stopWheel()
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        tts?.shutdown(); tts = null
        changed()
        super.onDestroy()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- GPS and warnings ----------
    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { setStatus("Location not allowed"); return }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper()) } catch (e: Exception) {}
            try { lm.getLastKnownLocation(p)?.let { if (location == null) location = it } } catch (e: Exception) {}
        }
    }

    override fun onLocationChanged(loc: Location) {
        // Prefer GPS; take a network fix only if there is nothing better recent.
        val cur = location
        if (loc.provider != LocationManager.GPS_PROVIDER && cur != null && cur.provider == LocationManager.GPS_PROVIDER && loc.time - cur.time < 10000) return
        location = loc
        warnAhead(loc)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun warnAhead(loc: Location) {
        if (!loc.hasBearing() || loc.speed < 2f) return
        for (h in HazardStore.hazards) {
            if (!h.active) continue
            val d = Geo.distance(loc.latitude, loc.longitude, h.lat, h.lng)
            if (d > 400) { warned.remove(h.id); continue }
            if (d > 200 || h.id in warned) continue
            var diff = Math.abs(Geo.bearing(loc.latitude, loc.longitude, h.lat, h.lng) - loc.bearing) % 360
            if (diff > 180) diff = 360 - diff
            if (diff > 50) continue
            warned += h.id
            say("${Geo.typeEs(h.type)} adelante, a ${(Math.round(d / 10) * 10)} metros.")
        }
    }

    // ---------- flagging ----------
    fun flag() {
        if (pending != null) { handler.removeCallbacks(saveRunnable); save(); return } // second press saves now
        if (System.currentTimeMillis() - lastTrigger < 8000) return
        val loc = location ?: run { say("Todavía no tengo tu ubicación."); return }
        lastTrigger = System.currentTimeMillis()
        pending = Pending(Location(loc))
        say("Hoyo marcado. Guardando en cinco segundos.")
        handler.postDelayed(saveRunnable, 5000)
        updateNotification(); changed()
    }

    fun cancel() {
        if (pending == null) return
        handler.removeCallbacks(saveRunnable); pending = null
        say("Cancelado.")
        updateNotification(); changed()
    }

    private fun save() {
        val p = pending ?: return
        pending = null; updateNotification(); changed()
        val loc = p.loc
        if (loc.hasAccuracy() && loc.accuracy > 100) { say("La ubicación es aproximada. No guardé el hoyo."); return }
        Api.io.execute {
            try {
                val near = HazardStore.nearestActive(loc.latitude, loc.longitude)
                if (near != null && near.second <= 25) Api.confirm(this, near.first.id)
                else Api.flag(this, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, if (loc.hasBearing()) loc.bearing else null)
                HazardStore.refresh()
                handler.post { say("Guardado."); changed() }
            } catch (e: Exception) {
                handler.post { say("No pude guardar el hoyo. Revisa la conexión.") }
            }
        }
    }

    private fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime()) }

    // ---------- listening ----------
    private fun startListening() {
        setStatus("Loading voice model…")
        val m = model
        if (m != null) { listenWith(m); return }
        StorageService.unpack(this, "model-es", "model", { loaded ->
            model = loaded
            if (instance === this && settings.listen) listenWith(loaded)
        }, { e -> setStatus("Voice model error: ${e.message}") })
    }

    private fun listenWith(m: Model) {
        try {
            val grammar = JSONArray((listOf("oi") + DECOYS + "[unk]").distinct()).toString()
            speech?.shutdown()
            speech = SpeechService(Recognizer(m, 16000f, grammar), 16000f).also { it.startListening(this) }
            setStatus(if (settings.word == "hoyo") "Listening for \"hoyo\"" else "Listening for \"" + List(settings.count) { "oi" }.joinToString(", ") + "\"")
        } catch (e: Exception) { setStatus("Microphone error: ${e.message}") }
    }

    private fun stopListening() { speech?.stop(); speech?.shutdown(); speech = null }

    override fun onPartialResult(hypothesis: String?) = check(JSONObject(hypothesis ?: "{}").optString("partial"), false)
    override fun onResult(hypothesis: String?) = check(JSONObject(hypothesis ?: "{}").optString("text"), true)
    override fun onFinalResult(hypothesis: String?) = check(JSONObject(hypothesis ?: "{}").optString("text"), true)
    override fun onError(exception: Exception?) { setStatus("Microphone error") }
    override fun onTimeout() {}

    private fun check(text: String, final: Boolean) {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (pending != null && words.any { it.startsWith("cancela") }) { cancel(); return }
        if (settings.word == "hoyo") {
            // Counts only when said by itself, so talking about a hoyo doesn't flag one.
            if (final && words.isNotEmpty() && words.all { it == "hoyo" }) flag()
            return
        }
        // A run of "oi" may continue across recognizer results when there is a short pause between each one.
        val now = System.currentTimeMillis()
        val all = (if (now - heardAt < 2500) heard else emptyList()) + words
        val run = all.takeLastWhile { it in OI }.size
        if (!fired && run >= settings.count) { fired = true; flag() }
        if (final) { heard = if (fired || words.isEmpty()) emptyList() else all.takeLast(run); heardAt = now; fired = false }
    }

    // ---------- steering-wheel buttons ----------
    // Media buttons go to the app that is playing audio, so this plays silence while the option is on.
    private fun startWheel() {
        if (session != null) return
        val rate = 8000
        val buf = ShortArray(rate) { if (it % 2 == 0) 1 else -1 }
        silence = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(buf.size * 2).build().also {
                it.write(buf, 0, buf.size); it.setLoopPoints(0, buf.size, -1); it.play()
            }
        session = MediaSession(this, "oioioi").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                             else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    if (ev?.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) flag()
                    return true
                }
                override fun onPlay() = flag()
                override fun onPause() = flag()
                override fun onSkipToNext() = flag()
                override fun onSkipToPrevious() = flag()
            })
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
    }

    private fun stopWheel() {
        session?.release(); session = null
        silence?.let { try { it.stop() } catch (e: Exception) {}; it.release() }; silence = null
    }

    // ---------- notification ----------
    private fun setStatus(s: String) { status = s; updateNotification(); changed() }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drive mode", NotificationManager.IMPORTANCE_LOW))
        fun action(a: String, title: String, code: Int) = Notification.Action.Builder(null, title,
            PendingIntent.getService(this, code, Intent(this, DriveService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flag).setContentTitle(if (pending != null) "Flagging a hazard…" else "OIOIOI Drive mode")
            .setContentText(if (pending != null) "Saving in 5 seconds. Say \"cancela\" or tap Cancel." else status)
            .setOngoing(true).setContentIntent(open)
        if (pending != null) b.addAction(action(ACTION_CANCEL, "Cancel", 3)).addAction(action(ACTION_FLAG, "Save now", 1))
        else b.addAction(action(ACTION_FLAG, "Flag hazard", 1))
        b.addAction(action(ACTION_STOP, "Stop", 2))
        return b.build()
    }

    private fun updateNotification() {
        if (instance === this) getSystemService(NotificationManager::class.java).notify(1, notification())
    }
}
