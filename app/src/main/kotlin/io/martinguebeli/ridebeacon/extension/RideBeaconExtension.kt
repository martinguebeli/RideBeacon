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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

class RideBeaconExtension : KarooExtension("ridebeacon", "1.0.0") {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sender: MessageSender
    private var webServer: WebConfigServer? = null

    private var rideStartTimeMs: Long? = null
    private var lastState: RideState? = null
    private var consumerId: String? = null
    private var startSmsSentForCurrentRide = false

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
        sender = MessageSender(karooSystem)
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
                }
                if (!startSmsSentForCurrentRide && settings.notifyOnStart) {
                    startSmsSentForCurrentRide = true
                    scope.launch {
                        val phone = settings.smsPhone.ifBlank { settings.whatsappPhone }
                        val error = sender.sendStartResult(settings)
                        val detail = if (error == null) "Start SMS sent to $phone" else error.take(60)
                        showAlert("rb_start", detail)
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

    override fun onDestroy() {
        webServer?.stop()
        consumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
