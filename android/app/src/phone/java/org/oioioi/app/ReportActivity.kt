package org.oioioi.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's main screen. Opening the app ("Hey Google, abre Hoyo Hoyo") starts a report right away;
 * it saves itself after 5 seconds unless the driver cancels. Reports stay on the phone.
 */
class ReportActivity : Activity() {
    private var flow: ReportFlow? = null
    private var screen = "list"
    private var detailId: String? = null
    private var photoFile: File? = null
    private var photoForFlow = false
    private lateinit var root: LinearLayout

    private val bg = Color.parseColor("#101214"); private val card = Color.parseColor("#1D2125")
    private val yellow = Color.parseColor("#F2C200"); private val red = Color.parseColor("#D62828"); private val muted = Color.parseColor("#9AA4AE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let a voice command open it over the lock screen.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); fitsSystemWindows = true; addView(root) }) // keep clear of the status bar
        val wanted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        savedInstanceState?.let { screen = it.getString("screen", "list"); detailId = it.getString("detail") }
        if (savedInstanceState == null) startReport() else render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        // The report started before location was allowed; start it again now that it is.
        if (results.any { it == PackageManager.PERMISSION_GRANTED } && flow?.state == ReportFlow.State.COUNTING) startReport()
    }

    // Opening the app again while it is in the background starts a new report.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val busy = flow?.state.let { it == ReportFlow.State.COUNTING || it == ReportFlow.State.PAUSED || it == ReportFlow.State.WAITING_GPS }
        if (intent.action == Intent.ACTION_MAIN && !busy) startReport()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("screen", if (screen == "flow") "list" else screen); out.putString("detail", detailId) }
    override fun onDestroy() { flow?.release(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (screen == "list") super.onBackPressed() else { flow?.cancel(); show("list") } }

    private fun startReport() {
        flow?.release()
        flow = ReportFlow(this) { runOnUiThread { onFlowChange() } }
        screen = "flow"
        flow!!.start()
    }

    private fun onFlowChange() {
        val f = flow ?: return
        if (screen != "flow") return
        when (f.state) {
            ReportFlow.State.SAVED -> { detailId = f.report.id; show("detail") }
            ReportFlow.State.CANCELLED -> show("list")
            else -> render()
        }
    }

    private fun show(s: String) { screen = s; render() }

    private fun render() {
        root.removeAllViews()
        when (screen) {
            "flow" -> renderFlow()
            "detail" -> ReportStore.get(this, detailId ?: "")?.let { renderDetail(it) } ?: show("list")
            "profile" -> renderProfile()
            else -> renderList()
        }
    }

    // ---------- reporting ----------
    private fun renderFlow() {
        val f = flow ?: return
        title(when (f.state) { ReportFlow.State.WAITING_GPS -> "Buscando tu ubicación…"; ReportFlow.State.PAUSED -> "Reporte en pausa"; else -> "Reportando hoyo" })
        if (f.state == ReportFlow.State.COUNTING) add(text("${f.secondsLeft}", 96f, yellow, bold = true).apply { gravity = Gravity.CENTER })
        add(text(when (f.state) {
            ReportFlow.State.COUNTING -> "Se guarda en ${f.secondsLeft} segundos / Saving in ${f.secondsLeft} s"
            ReportFlow.State.WAITING_GPS -> "Se guarda en cuanto haya GPS / Saving as soon as there is GPS"
            else -> "Toca Guardar cuando estés listo / Tap Save when ready"
        }, 16f, Color.WHITE))
        val loc = f.location
        add(text(if (loc == null) "Sin GPS todavía / No GPS yet" else String.format(Locale.US, "GPS ±%d m", Math.round(if (loc.hasAccuracy()) loc.accuracy else 0f)), 14f, muted))
        add(text("Tipo / Type", 14f, muted).apply { setPadding(0, dp(18), 0, dp(6)) })
        for ((k, label) in TYPES) add(button(label, if (f.report.type == k) yellow else card, if (f.report.type == k) Color.BLACK else Color.WHITE) { f.setType(k) })
        add(button("📷  Foto / Photo", red, Color.WHITE) { f.pause(); takePhoto(true) })
        add(button("Guardar ahora / Save now", Color.WHITE, Color.BLACK) { f.saveNow() })
        add(button("Cancelar / Cancel", card, Color.WHITE) { f.cancel() })
    }

    // ---------- one report ----------
    private fun renderDetail(r: Report) {
        title("Reporte guardado")
        add(text(r.address ?: "", 16f, yellow))
        for (name in r.photos) {
            val file = File(ReportStore.photoDir(this), name)
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: continue
            add(ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(0, dp(8), 0, dp(8)) })
        }
        add(text(claimText(this, r), 14f, Color.WHITE).apply { setTextIsSelectable(true); background = round(card); setPadding(dp(14), dp(14), dp(14), dp(14)) })
        add(button("Compartir reporte / Share report", yellow, Color.BLACK) { share(r) })
        add(button("📷  Agregar foto / Add photo", red, Color.WHITE) { takePhoto(false) })
        if (r.lat != null && r.lng != null) add(button("Ver en Google Maps / Open in Google Maps", card, Color.WHITE) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.lat},${r.lng}?q=${r.lat},${r.lng}(Hoyo)")))
        })
        add(button("Mis reportes / My reports", card, Color.WHITE) { show("list") })
        add(button("Borrar / Delete", card, red) {
            AlertDialog.Builder(this).setMessage("¿Borrar este reporte? / Delete this report?")
                .setPositiveButton("Borrar") { _, _ -> ReportStore.delete(this, r.id); show("list") }
                .setNegativeButton("No", null).show()
        })
    }

    private fun share(r: Report) {
        val uris = ArrayList(r.photos.map { FileProvider.getUriForFile(this, "$packageName.files", File(ReportStore.photoDir(this), it)) })
        val i = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "image/jpeg"
            putExtra(Intent.EXTRA_SUBJECT, "Reporte de ${typeLabel(r.type)}")
            putExtra(Intent.EXTRA_TEXT, claimText(this@ReportActivity, r))
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            if (uris.isNotEmpty()) { clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        startActivity(Intent.createChooser(i, "Compartir reporte"))
    }

    // ---------- list ----------
    private fun renderList() {
        val list = ReportStore.all(this)
        title("Mis reportes")
        add(text("Di \"Hey Google, abre Hoyo Hoyo\" para reportar un hoyo. / Say \"Hey Google, open Hoyo Hoyo\" to report a hazard.", 14f, muted))
        add(button("Nuevo reporte / New report", red, Color.WHITE) { startReport() })
        if (list.isEmpty()) add(text("Todavía no hay reportes. / No reports yet.", 16f, Color.WHITE).apply { setPadding(0, dp(16), 0, dp(16)) })
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale("es", "PR"))
        for (r in list) {
            val line2 = r.address ?: if (r.lat != null) String.format(Locale.US, "%.5f, %.5f", r.lat, r.lng) else "Sin ubicación"
            add(text("${typeLabel(r.type).substringBefore(" /")}  ·  ${fmt.format(Date(r.createdAt))}${if (r.photos.isNotEmpty()) "  ·  📷 ${r.photos.size}" else ""}\n$line2", 15f, Color.WHITE).apply {
                background = round(card); setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { detailId = r.id; show("detail") }
            })
        }
        add(button("Mis datos para reportes / My details", card, Color.WHITE) { show("profile") })
    }

    // ---------- driver details ----------
    private fun renderProfile() {
        title("Mis datos")
        add(text("Se usan en tus reportes y no salen del teléfono. / Used in your reports; they stay on this phone.", 14f, muted))
        val fields = Profile.FIELDS.map { (k, label) ->
            add(text(label, 13f, muted).apply { setPadding(0, dp(10), 0, 0) })
            k to EditText(this).apply {
                setText(Profile.get(this@ReportActivity, k)); setTextColor(Color.WHITE); setHintTextColor(muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                if (k == "year") inputType = InputType.TYPE_CLASS_NUMBER
            }.also { add(it) }
        }
        add(button("Guardar / Save", yellow, Color.BLACK) { fields.forEach { (k, e) -> Profile.set(this, k, e.text.toString().trim()) }; show("list") })
    }

    // ---------- photos ----------
    private fun takePhoto(forFlow: Boolean) {
        val name = "${System.currentTimeMillis()}.jpg"
        val file = File(ReportStore.photoDir(this), name)
        photoFile = file; photoForFlow = forFlow
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION), 5)
        } catch (e: Exception) { photoFile = null; render() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != 5) return super.onActivityResult(requestCode, resultCode, data)
        val file = photoFile ?: return; photoFile = null
        val ok = resultCode == RESULT_OK && file.exists() && file.length() > 0
        if (!ok) { file.delete(); render(); return }
        if (photoForFlow) flow?.let { it.report.photos += file.name; it.saveNow(); return }
        ReportStore.get(this, detailId ?: "")?.let { r -> r.photos += file.name; ReportStore.save(this, r) }
        render()
    }

    // ---------- small view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun <T : View> add(v: T): T {
        root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return v
    }
    private fun title(s: String) = add(text(s, 28f, Color.WHITE, bold = true))
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(label: String, color: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 17f; setTextColor(fg); background = round(color)
        minHeight = dp(56); setOnClickListener { onClick() }
    }
}
