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
    val smsEnabled: Boolean = false,
    val smsPhone: String = "",
    val smsBeltKey: String = "textbelt",

    // Messages
    val startMessage: String = "🚴 {name} is on a ride! Follow live: {livelink}",
    val stopMessage: String = "✅ {name} finished the ride. {distance} km · {duration}",

    val notifyOnStart: Boolean = true,
    val notifyOnStop: Boolean = true,
)

const val LIVE_BASE_URL = "https://dashboard.hammerhead.io/live/"
