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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

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

    // True once start SMS has been sent/attempted for the current ride — prevents
    // re-firing on every auto-pause → resume cycle
    private var startSmsSentForCurrentRide = false

    // Eagerly cached settings so they are ready before the first RideState event
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
        cachedSettings // trigger eager load

        try {
            webServer = WebConfigServer(8080, settingsRepo, scope).also { it.start() }
            Timber.i("WebConfigServer started on port 8080")
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
        Timber.d("RideState: $state (last: $lastState, startSmsSent: $startSmsSentForCurrentRide)")

        when (state) {
            is RideState.Recording -> {
                // Only fire on transition from Idle (true ride start), not from Paused (auto-pause resume)
                if (lastState is RideState.Idle || lastState == null) {
                    Timber.i("Ride started (from Idle)")
                    rideStartTimeMs = System.currentTimeMillis()
                    startSmsSentForCurrentRide = false
                }
                if (!startSmsSentForCurrentRide && settings.notifyOnStart) {
                    startSmsSentForCurrentRide = true
                    scope.launch {
                        val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }
                        val error = sendWithRetry { sender.sendStartResult(settings) }
                        val alertDetail = if (error == null) "Start SMS sent to $phone" else error.take(60)
                        showAlert("rb_start", alertDetail)
                    }
                }
            }
            is RideState.Idle -> {
                if (lastState is RideState.Recording || lastState is RideState.Paused) {
                    Timber.i("Ride stopped")
                    if (settings.notifyOnStop) {
                        val durationMin = rideStartTimeMs?.let {
                            ((System.currentTimeMillis() - it) / 60_000).toInt()
                        } ?: 0
                        scope.launch {
                            val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }
                            val error = sendWithRetry(delayMs = 0) { sender.sendStopResult(settings, distanceKm = 0.0, durationMin = durationMin) }
                            val alertDetail = if (error == null) "Stop SMS sent to $phone" else error.take(60)
                            showAlert("rb_stop", alertDetail)
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

    private fun showAlert(id: String, detail: String) {
        karooSystem.dispatch(
            InRideAlert(
                id = id,
                icon = R.drawable.ic_ridebeacon,
                title = "RideBeacon",
                detail = detail,
                autoDismissMs = 6_000L,
                backgroundColor = R.color.background,
                textColor = R.color.on_surface,
            )
        )
    }

    /**
     * Waits 60s before first attempt (gives BT tethering time to establish),
     * then retries up to 3x with 30s gaps on network errors.
     */
    private suspend fun sendWithRetry(delayMs: Long = 60_000L, block: suspend () -> String?): String? {
        delay(delayMs)
        repeat(3) { attempt ->
            val result = block()
            if (result == null) return null
            val isNetworkError = result.contains("resolve", ignoreCase = true)
                || result.contains("network", ignoreCase = true)
                || result.contains("connect", ignoreCase = true)
            if (isNetworkError) {
                Timber.w("Network not ready (attempt ${attempt + 1}), retrying in 30s")
                delay(30_000)
            } else {
                Timber.e("SMS failed: $result")
                return result
            }
        }
        return "No network"
    }

    override fun onDestroy() {
        webServer?.stop()
        consumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
