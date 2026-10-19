package org.oioioi.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import org.oioioi.app.Api
import org.oioioi.app.DriveService
import org.oioioi.app.Geo
import org.oioioi.app.HazardStore

/** Android Auto entry point. */
class OioiCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Test builds accept any Android Auto host (needed for sideloading with developer mode).
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = HazardScreen(carContext)
    }
}

/** The car screen: a map of nearby hazards with Flag hazard and Listen buttons. */
@Suppress("DEPRECATION") // PlaceListMapTemplate works on every Android Auto version, unlike its replacement.
class HazardScreen(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var lastDraw = 0L
    private val onChange: () -> Unit = { redraw() }
    private val tick = object : Runnable {
        override fun run() { HazardStore.refreshAsync { handler.post { redraw() } }; handler.postDelayed(this, 30000) }
    }

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) { DriveService.listeners += onChange; handler.post(tick) }
    override fun onStop(owner: LifecycleOwner) { DriveService.listeners -= onChange; handler.removeCallbacks(tick) }

    // Android Auto limits how often a screen may refresh, so redraws are spaced out.
    private fun redraw() {
        val wait = 4000 - (System.currentTimeMillis() - lastDraw)
        if (wait <= 0) { lastDraw = System.currentTimeMillis(); invalidate() }
        else { handler.removeCallbacks(redrawLater); handler.postDelayed(redrawLater, wait) }
    }
    private val redrawLater = Runnable { lastDraw = System.currentTimeMillis(); invalidate() }

    private fun granted() = carContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun here(): Location? = DriveService.instance?.location ?: if (!granted()) null else {
        val lm = carContext.getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }
    }

    override fun onGetTemplate(): Template {
        val service = DriveService.instance
        val loc = here()
        val list = ItemList.Builder()
        val near = HazardStore.hazards.filter { it.active }
            .map { it to (if (loc != null) Geo.distance(loc.latitude, loc.longitude, it.lat, it.lng) else 0.0) }
            .sortedBy { it.second }.take(6)
        if (near.isEmpty()) list.setNoItemsMessage(if (HazardStore.lastError != null) "Can't reach the server" else "No hazards flagged nearby")
        for ((h, d) in near) {
            val dist = if (loc == null) "" else if (d < 1000) "${Math.round(d / 10) * 10} m" else String.format("%.1f km", d / 1000)
            list.addItem(Row.Builder()
                .setTitle(Geo.typeEs(h.type))
                .addText(listOf(dist, "${h.reporters} driver${if (h.reporters == 1) "" else "s"}").filter { it.isNotEmpty() }.joinToString(" · "))
                .setMetadata(Metadata.Builder().setPlace(Place.Builder(CarLocation.create(h.lat, h.lng)).setMarker(PlaceMarker.Builder().build()).build()).build())
                .build())
        }
        val flag = Action.Builder().setTitle(if (service?.flagging == true) "Save now" else "Flag hazard").setOnClickListener { flag() }.build()
        val listen = Action.Builder().setTitle(if (service != null) "Stop" else "Drive").setOnClickListener { toggleDrive() }.build()
        val title = when {
            service?.flagging == true -> "Saving hazard in 5 s"
            service != null -> service.status
            else -> "OIOIOI"
        }
        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setItemList(list.build())
            .setCurrentLocationEnabled(granted())
            .setActionStrip(ActionStrip.Builder().addAction(flag).addAction(listen).build())
            .build()
    }

    private fun flag() {
        val service = DriveService.instance
        if (service != null) { service.flag(); return }
        // Not in Drive mode: save straight away at the last known position.
        val loc = here() ?: run { CarToast.makeText(carContext, "No location yet", CarToast.LENGTH_LONG).show(); return }
        CarToast.makeText(carContext, "Hazard flagged", CarToast.LENGTH_SHORT).show()
        Api.io.execute {
            try {
                val n = HazardStore.nearestActive(loc.latitude, loc.longitude)
                if (n != null && n.second <= 25) Api.confirm(carContext, n.first.id)
                else Api.flag(carContext, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, if (loc.hasBearing()) loc.bearing else null)
                HazardStore.refresh()
                handler.post { redraw() }
            } catch (e: Exception) {
                handler.post { CarToast.makeText(carContext, "Could not save: no connection", CarToast.LENGTH_LONG).show() }
            }
        }
    }

    private fun toggleDrive() {
        if (DriveService.instance != null) { DriveService.stop(carContext); return }
        val prefs = carContext.getSharedPreferences("oioioi", 0)
        try { DriveService.start(carContext, prefs.getString("settings", null) ?: JSONObject().toString()) }
        catch (e: Exception) { CarToast.makeText(carContext, "Open OIOIOI on the phone and tap Drive", CarToast.LENGTH_LONG).show() }
    }
}
