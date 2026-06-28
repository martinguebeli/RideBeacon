package io.martinguebeli.ridebeacon.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.martinguebeli.ridebeacon.R
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.sender.MessageSender
import io.martinguebeli.ridebeacon.settings.SettingsRepository
import io.martinguebeli.ridebeacon.web.WebConfigServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
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

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(this)
        settingsRepo = SettingsRepository(this)

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
        val settings = settingsRepo.settingsFlow.firstOrNull() ?: BeaconSettings()

        when (state) {
            is RideState.Recording -> {
                if (lastState !is RideState.Recording) {
                    Timber.i("Ride started")
                    rideStartTimeMs = System.currentTimeMillis()
                    if (settings.notifyOnStart) {
                        scope.launch {
                            val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }
                            val error = sendWithRetry { sender.sendStartResult(settings) }
                            val alertDetail = if (error == null)
                                "Start SMS sent to $phone"
                            else
                                "SMS error — check settings"
                            karooSystem.dispatch(
                                InRideAlert(
                                    id = "rb_start",
                                    icon = R.drawable.ic_ridebeacon,
                                    title = "RideBeacon",
                                    detail = alertDetail,
                                    autoDismissMs = 6_000L,
                                    backgroundColor = R.color.background,
                                    textColor = R.color.on_surface,
                                )
                            )
                        }
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
                            val error = sendWithRetry { sender.sendStopResult(settings, distanceKm = 0.0, durationMin = durationMin) }
                            val alertDetail = if (error == null)
                                "Stop SMS sent to $phone"
                            else
                                "SMS error — check settings"
                            karooSystem.dispatch(
                                InRideAlert(
                                    id = "rb_stop",
                                    icon = R.drawable.ic_ridebeacon,
                                    title = "RideBeacon",
                                    detail = alertDetail,
                                    autoDismissMs = 6_000L,
                                    backgroundColor = R.color.background,
                                    textColor = R.color.on_surface,
                                )
                            )
                        }
                    }
                    rideStartTimeMs = null
                }
            }
            else -> {}
        }
        lastState = state
    }

    /**
     * Waits 30s before first attempt (network stabilises after ride start),
     * then retries up to 4 more times with 30s gaps on network errors.
     */
    private suspend fun sendWithRetry(block: suspend () -> String?): String? {
        delay(30_000)
        repeat(5) { attempt ->
            val result = block()
            if (result == null) return null
            val isNetworkError = result == "No network" || result.contains("resolve", ignoreCase = true) || result.contains("network", ignoreCase = true)
            if (isNetworkError) {
                Timber.w("Network not ready (attempt ${attempt + 1}), retrying in 30s")
                delay(30_000)
            } else {
                Timber.e("SMS failed: $result")
                return result
            }
        }
        return "No network after 5 attempts"
    }

    override fun onDestroy() {
        webServer?.stop()
        consumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
