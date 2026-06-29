package io.martinguebeli.ridebeacon.model

import kotlinx.serialization.Serializable

@Serializable
data class BeaconSettings(
    val riderName: String = "Martin",
    val karooLiveKey: String = "",

    // WhatsApp via CallMeBot
    val whatsappEnabled: Boolean = false,
    val whatsappPhone: String = "",
    val whatsappApiKey: String = "",

    // SMS via TextBelt
    val smsEnabled: Boolean = true,
    val smsPhone: String = "",
    val smsBeltKey: String = "textbelt",

    // Messages
    val startMessage: String = "🚴 {name} started a ride! — Open the Hammerhead Dashboard and use the Livekey: {livekey}",
    val stopMessage: String = "✅ {name} finished the ride. {distance} km · {duration}",

    val notifyOnStart: Boolean = true,
    val notifyOnStop: Boolean = true,
)

const val LIVE_BASE_URL = "dashboard.hammerhead.io/live/"
