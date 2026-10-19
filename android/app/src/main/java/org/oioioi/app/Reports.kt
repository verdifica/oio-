package org.oioioi.app

import android.content.Context
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A hazard report kept on the phone, for filing a claim. */
data class Report(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    var lat: Double? = null, var lng: Double? = null, var accuracy: Float? = null,
    var type: String = "pothole", var address: String? = null,
    val photos: MutableList<String> = mutableListOf(), var note: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("createdAt", createdAt)
        .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL).put("accuracy", accuracy?.toDouble() ?: JSONObject.NULL)
        .put("type", type).put("address", address ?: JSONObject.NULL).put("photos", JSONArray(photos)).put("note", note)

    companion object {
        fun from(o: JSONObject) = Report(
            o.getString("id"), o.getLong("createdAt"),
            if (o.isNull("lat")) null else o.getDouble("lat"), if (o.isNull("lng")) null else o.getDouble("lng"),
            if (o.isNull("accuracy")) null else o.getDouble("accuracy").toFloat(),
            o.optString("type", "pothole"), if (o.isNull("address")) null else o.getString("address"),
            o.optJSONArray("photos")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
            o.optString("note", ""),
        )
    }
}

object ReportStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "reports.json")
    fun photoDir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }

    @Synchronized fun all(ctx: Context): List<Report> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try { val a = JSONArray(f.readText()); (0 until a.length()).map { Report.from(a.getJSONObject(it)) }.sortedByDescending { it.createdAt } } catch (e: Exception) { emptyList() }
    }

    fun get(ctx: Context, id: String) = all(ctx).find { it.id == id }

    @Synchronized fun save(ctx: Context, r: Report) {
        val list = all(ctx).filter { it.id != r.id } + r
        val tmp = File(ctx.filesDir, "reports.json.tmp")
        tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
        tmp.renameTo(file(ctx))
    }

    @Synchronized fun delete(ctx: Context, id: String) {
        get(ctx, id)?.photos?.forEach { File(photoDir(ctx), it).delete() }
        val list = all(ctx).filter { it.id != id }
        file(ctx).writeText(JSONArray(list.map { it.toJson() }).toString())
    }

    /** Street address of the spot, when the phone can look it up (usually needs a connection). */
    fun lookupAddress(ctx: Context, lat: Double, lng: Double): String? = try {
        @Suppress("DEPRECATION")
        Geocoder(ctx, Locale("es", "PR")).getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
    } catch (e: Exception) { null }
}

/** The driver's details for claim reports, kept on the phone only. */
object Profile {
    val FIELDS = listOf("name" to "Nombre / Name", "license" to "Licencia / License", "address" to "Dirección / Address",
        "make" to "Marca / Make", "model" to "Modelo / Model", "year" to "Año / Year", "plate" to "Tablilla / Plate")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)
    fun get(ctx: Context, k: String) = prefs(ctx).getString(k, "") ?: ""
    fun set(ctx: Context, k: String, v: String) = prefs(ctx).edit().putString(k, v).apply()
}

val TYPES = listOf("pothole" to "Hoyo / Pothole", "debris" to "Escombros / Debris", "flooding" to "Inundación / Flooding",
    "accident" to "Accidente / Accident", "other" to "Otro peligro / Other hazard")

fun typeLabel(t: String) = TYPES.find { it.first == t }?.second ?: t

/** Bilingual report text, the same layout as the web app's claim report. */
fun claimText(ctx: Context, r: Report): String {
    val p = { k: String -> Profile.get(ctx, k) }
    val car = listOf(p("make"), p("model"), p("year")).filter { it.isNotBlank() }.joinToString(" ")
    val date = SimpleDateFormat("d 'de' MMMM 'de' yyyy, h:mm a", Locale("es", "PR")).format(Date(r.createdAt))
    val where = if (r.lat == null || r.lng == null) "Sin ubicación / No location" else
        String.format(Locale.US, "%.6f, %.6f", r.lat, r.lng) + (r.accuracy?.let { " (±${Math.round(it)} m)" } ?: "")
    return buildString {
        appendLine("REPORTE DE CONDICIÓN PELIGROSA EN LA VÍA / ROAD HAZARD REPORT")
        appendLine()
        appendLine("Tipo / Type: ${typeLabel(r.type)}")
        appendLine("Fecha y hora / Date & time: $date")
        if (!r.address.isNullOrBlank()) appendLine("Dirección / Address: ${r.address}")
        appendLine("Ubicación / Location: $where")
        if (r.lat != null && r.lng != null) appendLine(String.format(Locale.US, "Mapa / Map: https://www.google.com/maps?q=%.6f,%.6f", r.lat, r.lng))
        appendLine("Fotos / Photos: ${if (r.photos.isEmpty()) "No" else r.photos.size.toString() + " (adjuntas / attached)"}")
        if (r.note.isNotBlank()) appendLine("Nota / Note: ${r.note}")
        appendLine()
        appendLine("Reportado por / Reported by: ${p("name").ifBlank { "________________" }}")
        appendLine("Licencia / License: ${p("license").ifBlank { "________________" }}")
        appendLine("Dirección / Address: ${p("address").ifBlank { "________________" }}")
        if (car.isNotBlank() || p("plate").isNotBlank()) appendLine("Vehículo / Vehicle: $car ${if (p("plate").isNotBlank()) "(tablilla / plate ${p("plate")})" else ""}".trimEnd())
        appendLine()
        append("Creado con OIOIOI / Created with OIOIOI")
    }
}
