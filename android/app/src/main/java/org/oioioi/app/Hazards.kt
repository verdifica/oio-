package org.oioioi.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Hazard(val id: String, val lat: Double, val lng: Double, val type: String, val active: Boolean, val reporters: Int)

/** Talks to the same server and API as the web app. */
object Api {
    private const val BASE = BuildConfig.SERVER
    val io = Executors.newSingleThreadExecutor()

    /** Anonymous id of this phone, shared with the web view so votes count once. */
    fun uid(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("oioioi", Context.MODE_PRIVATE)
        return prefs.getString("uid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("uid", it).apply() }
    }

    private fun call(path: String, body: JSONObject?): String {
        val c = URL(BASE + "api/" + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000 // the free server can take a while to wake up
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) throw RuntimeException("Server error $code")
        return text
    }

    fun hazards(): List<Hazard> {
        val arr = org.json.JSONArray(call("hazards", null))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rep = o.optJSONObject("reporters")
            val n = rep?.keys()?.asSequence()?.count { rep.optLong(it) > 0 } ?: 0
            Hazard(o.getString("id"), o.getDouble("lat"), o.getDouble("lng"), o.optString("type", "pothole"), o.optString("status") != "fixed", n)
        }
    }

    fun flag(ctx: Context, lat: Double, lng: Double, accuracy: Float?, heading: Float?) {
        val body = JSONObject().put("uid", uid(ctx)).put("lat", lat).put("lng", lng).put("type", "pothole")
        if (accuracy != null) body.put("accuracy", accuracy.toDouble())
        if (heading != null) body.put("heading", heading.toDouble())
        call("hazards", body)
    }

    fun confirm(ctx: Context, id: String) { call("hazards/$id/confirm", JSONObject().put("uid", uid(ctx))) }
}

/** Latest list of hazards from the server, shared by the phone service and the car screen. */
object HazardStore {
    @Volatile var hazards: List<Hazard> = emptyList()
    @Volatile var lastError: String? = null

    fun refresh() {
        try { hazards = Api.hazards(); lastError = null } catch (e: Exception) { lastError = e.message }
    }

    fun refreshAsync(done: () -> Unit = {}) = Api.io.execute { refresh(); done() }

    fun nearestActive(lat: Double, lng: Double): Pair<Hazard, Double>? =
        hazards.filter { it.active }.map { it to Geo.distance(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }
}

object Geo {
    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun typeEs(t: String) = mapOf("pothole" to "Hoyo", "debris" to "Escombros", "flooding" to "Inundación", "accident" to "Accidente")[t] ?: "Otro peligro"
}
