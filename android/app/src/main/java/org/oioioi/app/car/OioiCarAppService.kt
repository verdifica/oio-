package org.oioioi.app.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.oioioi.app.ReportFlow
import java.util.Locale

/** Android Auto entry point. Opening OIOIOI on the car screen ("Hey Google, open OIOIOI") starts a report. */
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

    init { lifecycle.addObserver(this); newReport() }

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
        val f = flow!!
        val r = f.report
        val message = when (f.state) {
            ReportFlow.State.SAVED -> "Reporte guardado en el teléfono\n" + (r.address ?: r.lat?.let { String.format(Locale.US, "%.5f, %.5f", it, r.lng) } ?: "Sin ubicación")
            ReportFlow.State.CANCELLED -> "Reporte cancelado"
            ReportFlow.State.WAITING_GPS -> "Esperando la ubicación GPS…"
            else -> "Reportando hoyo. Se guarda en 5 segundos."
        }
        val b = MessageTemplate.Builder(message).setTitle("OIOIOI").setHeaderAction(Action.APP_ICON)
        when (f.state) {
            ReportFlow.State.SAVED, ReportFlow.State.CANCELLED ->
                b.addAction(Action.Builder().setTitle("Nuevo reporte").setOnClickListener { newReport() }.build())
            else -> {
                b.addAction(Action.Builder().setTitle("Guardar").setOnClickListener { f.saveNow() }.build())
                b.addAction(Action.Builder().setTitle("Cancelar").setOnClickListener { f.cancel() }.build())
            }
        }
        return b.build()
    }
}
