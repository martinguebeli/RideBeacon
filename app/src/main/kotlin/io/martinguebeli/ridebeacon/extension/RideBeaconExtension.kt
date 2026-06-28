package io.martinguebeli.ridebeacon.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.martinguebeli.ridebeacon.R
import io.martinguebeli.ridebeacon.sender.MessageSender
import io.martinguebeli.ridebeacon.settings.SettingsRepository
import io.martinguebeli.ridebeacon.web.WebConfigServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

class RideBeaconExtension : KarooExtension("ridebeacon", "1.0.0") {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsRepo: SettingsRepository
    private val sender = MessageSender()
    private var webServer: WebConfigServer? = null

    private var rideStartTimeMs: Long? = null
    private var lastState: RideState? = null
    private var consumerId: String? = null
    private var startSmsSentForCurrentRide = false
    private var probeJob: Job? = null

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cachedSettings by lazy {
        settingsRepo.settingsFlow.stateIn(
            scope, SharingStarted.Eagerly,
            io.martinguebeli.ridebeacon.model.BeaconSettings()
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(this)
        settingsRepo = SettingsRepository(this)
        cachedSettings

        try {
            webServer = WebConfigServer(8080, settingsRepo, scope).also { it.start() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start WebConfigServer")
        }

        karooSystem.connect {
            Timber.i("RideBeacon connected to Karoo")
            consumerId = karooSystem.addConsumer<RideState> { state ->
                scope.launch { handleStateChange(state) }
            }
        }
    }

    private suspend fun handleStateChange(state: RideState) {
        val settings = cachedSettings.value
        Timber.d("RideState: $state (last: $lastState)")

        when (state) {
            is RideState.Recording -> {
                if (lastState is RideState.Idle || lastState == null) {
                    Timber.i("Ride started")
                    rideStartTimeMs = System.currentTimeMillis()
                    startSmsSentForCurrentRide = false
                    if (settings.notifyOnStart) {
                        startSmsSentForCurrentRide = true
                        probeJob?.cancel()
                        probeJob = scope.launch { probeAndSendStart(settings) }
                    }
                }
            }
            is RideState.Idle -> {
                if (lastState is RideState.Recording || lastState is RideState.Paused) {
                    Timber.i("Ride stopped")
                    probeJob?.cancel()
                    probeJob = null
                    if (settings.notifyOnStop) {
                        val durationMin = rideStartTimeMs?.let {
                            ((System.currentTimeMillis() - it) / 60_000).toInt()
                        } ?: 0
                        scope.launch {
                            val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }
                            val error = sender.sendStopResult(settings, distanceKm = 0.0, durationMin = durationMin)
                            val detail = if (error == null) "Stop SMS sent to $phone" else error.take(60)
                            showAlert("rb_stop", detail)
                        }
                    }
                    rideStartTimeMs = null
                    startSmsSentForCurrentRide = false
                }
            }
            else -> {}
        }
        lastState = state
    }

    /**
     * Probes network every 10 seconds for up to 90 seconds.
     * Shows each probe result on the Karoo screen.
     * Sends start SMS as soon as network is available.
     */
    private suspend fun probeAndSendStart(settings: io.martinguebeli.ridebeacon.model.BeaconSettings) {
        val maxChecks = 9   // 9 × 10s = 90 seconds
        val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }

        for (check in 1..maxChecks) {
            val ok = probeNetwork()
            Timber.i("Network probe $check/$maxChecks: ${if (ok) "OK" else "no network"}")
            showAlert(
                id = "rb_probe_$check",
                detail = "BT check $check/9: ${if (ok) "✓ connected" else "✗ no network"}"
            )

            if (ok) {
                // Network is up — send the SMS now
                val error = sender.sendStartResult(settings)
                val detail = if (error == null) "Start SMS sent to $phone" else error.take(60)
                showAlert("rb_start", detail)
                return
            }

            if (check < maxChecks) delay(10_000)
        }

        // 90 seconds passed, still no network
        showAlert("rb_start_fail", "No network after 90s — SMS not sent")
    }

    private fun probeNetwork(): Boolean {
        return try {
            val req = Request.Builder()
                .url("https://textbelt.com/")
                .head()
                .build()
            probeClient.newCall(req).execute().use { it.isSuccessful || it.code < 500 }
        } catch (e: Exception) {
            Timber.w("Probe failed: ${e.message}")
            false
        }
    }

    private fun showAlert(id: String, detail: String) {
        karooSystem.dispatch(
            InRideAlert(
                id = id,
                icon = R.drawable.ic_ridebeacon,
                title = "RideBeacon",
                detail = detail,
                autoDismissMs = 8_000L,
                backgroundColor = R.color.background,
                textColor = R.color.on_surface,
            )
        )
    }

    override fun onDestroy() {
        webServer?.stop()
        probeJob?.cancel()
        consumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
