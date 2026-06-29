package io.martinguebeli.ridebeacon.model

import kotlinx.serialization.Serializable

enum class NotificationChannel { SMS, TELEGRAM, WHATSAPP }

@Serializable
data class BeaconSettings(
    val riderName: String = "Martin",
    val karooLiveKey: String = "",

    val channel: String = NotificationChannel.SMS.name,

    // SMS via TextBelt
    val smsPhone: String = "",
    val smsBeltKey: String = "textbelt",

    // Telegram
    val telegramBotToken: String = "",
    val telegramChatId: String = "",

    // WhatsApp via GREEN-API
    val whatsappPhone: String = "",
    val greenApiUrl: String = "",
    val greenApiInstanceId: String = "",
    val greenApiToken: String = "",

    // Messages
    val startMessage: String = "🚴 {name} started a ride! — {livelink}",
    val stopMessage: String = "✅ {name} finished the ride. {distance} km · {duration}",

    val notifyOnStart: Boolean = true,
    val notifyOnStop: Boolean = true,
)

const val LIVE_BASE_URL = "https://dashboard.hammerhead.io/live/"
