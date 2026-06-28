package io.martinguebeli.ridebeacon.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.RideState
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.sender.MessageSender
import io.martinguebeli.ridebeacon.settings.SettingsRepository
import io.martinguebeli.ridebeacon.web.WebConfigServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        karooSystem = KarooSystemService(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)

        // Start web config server on port 8080
        try {
            webServer = WebConfigServer(8080, settingsRepo, scope).also { it.start() }
            Timber.i("WebConfigServer started on port 8080")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start WebConfigServer")
        }

        karooSystem.connect { connected ->
            if (connected) {
                Timber.i("RideBeacon connected to Karoo")
                observeRideState()
            }
        }
    }

    private fun observeRideState() {
        consumerId = karooSystem.addConsumer<RideState> { state ->
            scope.launch { handleStateChange(state) }
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
                        scope.launch { sender.sendStart(settings) }
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
                            sender.sendStop(settings, distanceKm = 0.0, durationMin = durationMin)
                        }
                    }
                    rideStartTimeMs = null
                }
            }
            else -> {}
        }
        lastState = state
    }

    override fun onDestroy() {
        webServer?.stop()
        consumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        job.cancel()
        super.onDestroy()
    }
}
