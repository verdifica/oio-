package org.oioioi.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.oioioi.app.ReportFlow
import org.oioioi.app.ReportStore
import org.oioioi.app.typeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android Auto entry point. Opening OIOIOI on the car screen ("Hey Google, abre Hoyo Hoyo") starts a report. */
class OioiCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Test builds accept any Android Auto host (needed for sideloading with developer mode).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        private var screen: ReportScreen? = null
        override fun onCreateScreen(intent: Intent): Screen = ReportScreen(carContext).also { screen = it }
        override fun onNewIntent(intent: Intent) { screen?.newReport() }
    }
}

/** The car screen: "Reporting a hazard, saving in 5 s" with Save now / Cancel, then "Report saved". */
class ReportScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var flow: ReportFlow? = null
    private var shown: ReportFlow.State? = null
    private var address: String? = null

    init {
        lifecycle.addObserver(this)
        // On the car's own system the app asks for location itself, the first time.
        if (carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) newReport()
        else try {
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { _, _ -> newReport() }
        } catch (e: Exception) { newReport() }
    }

    override fun onDestroy(owner: LifecycleOwner) { flow?.release() }

    fun newReport() {
        val busy = flow?.state.let { it == ReportFlow.State.COUNTING || it == ReportFlow.State.WAITING_GPS }
        if (busy) return
        flow?.release()
        flow = ReportFlow(carContext) { handler.post { changed() } }.also { it.start() }
    }

    // Android Auto limits how often a screen may change, so it redraws only when the step changes
    // (and once more when the street address arrives), not every second of the countdown.
    private fun changed() {
        val f = flow ?: return
        if (f.state != shown || f.report.address != address) { shown = f.state; address = f.report.address; invalidate() }
    }

    override fun onGetTemplate(): Template {
        val f = flow ?: return MessageTemplate.Builder("Permite la ubicación para reportar hoyos.").setTitle("Hoyo Hoyo").setHeaderAction(Action.APP_ICON).build()
        val r = f.report
        val message = when (f.state) {
            ReportFlow.State.SAVED -> "Reporte guardado en el teléfono\n" + (r.address ?: r.lat?.let { String.format(Locale.US, "%.5f, %.5f", it, r.lng) } ?: "Sin ubicación")
            ReportFlow.State.CANCELLED -> "Reporte cancelado"
            ReportFlow.State.WAITING_GPS -> "Esperando la ubicación GPS…"
            else -> "Reportando hoyo. Se guarda en 5 segundos."
        }
        val b = MessageTemplate.Builder(message).setTitle("Hoyo Hoyo").setHeaderAction(Action.APP_ICON)
        when (f.state) {
            ReportFlow.State.SAVED, ReportFlow.State.CANCELLED ->
                b.addAction(Action.Builder().setTitle("Nuevo reporte").setOnClickListener { newReport() }.build())
                b.addAction(Action.Builder().setTitle("Mis reportes").setOnClickListener { screenManager.push(ReportsScreen(carContext)) }.build())
            else -> {
                b.addAction(Action.Builder().setTitle("Guardar").setOnClickListener { f.saveNow() }.build())
                b.addAction(Action.Builder().setTitle("Cancelar").setOnClickListener { f.cancel() }.build())
            }
        }
        return b.build()
    }
}

/** The reports saved on this device, newest first. */
class ReportsScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val reports = ReportStore.all(carContext).take(6) // the car shows only a few rows while driving
        if (reports.isEmpty()) list.setNoItemsMessage("Todavía no hay reportes")
        val fmt = SimpleDateFormat("d MMM, h:mm a", Locale("es", "PR"))
        for (r in reports) list.addItem(Row.Builder()
            .setTitle("${typeLabel(r.type).substringBefore(" /")} · ${fmt.format(Date(r.createdAt))}")
            .addText(r.address ?: r.lat?.let { String.format(Locale.US, "%.5f, %.5f", it, r.lng) } ?: "Sin ubicación")
            .build())
        return ListTemplate.Builder().setTitle("Mis reportes").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
