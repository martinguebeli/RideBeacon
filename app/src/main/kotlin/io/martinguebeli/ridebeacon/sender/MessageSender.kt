package io.martinguebeli.ridebeacon.sender

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.model.LIVE_BASE_URL
import io.martinguebeli.ridebeacon.model.NotificationChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.net.URLEncoder

class MessageSender(private val karooSystem: KarooSystemService) {

    suspend fun sendStartResult(settings: BeaconSettings): String? {
        val text = buildMessage(settings.startMessage, settings, distanceKm = null, duration = null)
        return dispatch(settings, text)
    }

    suspend fun sendStopResult(settings: BeaconSettings, distanceKm: Double, durationMin: Int): String? {
        val text = buildMessage(settings.stopMessage, settings, distanceKm, formatDuration(durationMin))
        return dispatch(settings, text)
    }

    suspend fun sendTestResult(settings: BeaconSettings): String? {
        val text = "RideBeacon Test from ${settings.riderName}"
        return dispatch(settings, text)
    }

    private fun buildMessage(template: String, settings: BeaconSettings, distanceKm: Double?, duration: String?): String {
        val liveLink = if (settings.karooLiveKey.isNotBlank()) "$LIVE_BASE_URL${settings.karooLiveKey}" else ""
        return template
            .replace("{name}", settings.riderName)
            .replace("{livelink}", liveLink)
            .replace("{livekey}", settings.karooLiveKey)
            .replace("{distance}", distanceKm?.let { "%.1f".format(it) } ?: "")
            .replace("{duration}", duration ?: "")
    }

    private suspend fun dispatch(settings: BeaconSettings, text: String): String? {
        return when (NotificationChannel.valueOf(settings.channel)) {
            NotificationChannel.SMS -> sendSms(settings.smsPhone, settings.smsBeltKey, text)
            NotificationChannel.TELEGRAM -> sendTelegram(settings.telegramBotToken, settings.telegramChatId, text)
            NotificationChannel.WHATSAPP -> sendWhatsApp(settings, text)
        }
    }

    private suspend fun sendSms(phone: String, apiKey: String, text: String): String? {
        if (phone.isBlank()) return "Phone number is empty"
        val body = "phone=${encode(phone)}&message=${encode(text)}&key=${encode(apiKey.ifBlank { "textbelt" })}"
        return try {
            val resp = karooSystem.makeHttpRequest(
                method = "POST",
                url = "https://textbelt.com/text",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = body.toByteArray()
            ).first()
            val bodyStr = resp.body?.let { String(it) } ?: ""
            Timber.i("SMS response ${resp.statusCode}: $bodyStr")
            when {
                bodyStr.contains("\"success\":true") -> null
                bodyStr.contains("\"error\":\"") -> bodyStr.substringAfter("\"error\":\"").substringBefore("\"").ifBlank { "Unknown error" }
                else -> "HTTP ${resp.statusCode}"
            }
        } catch (e: Exception) {
            Timber.e(e, "SMS send failed")
            "No network"
        }
    }

    private suspend fun sendTelegram(botToken: String, chatId: String, text: String): String? {
        if (botToken.isBlank()) return "Telegram bot token is empty"
        if (chatId.isBlank()) return "Telegram chat ID is empty"
        val url = "https://api.telegram.org/bot${encode(botToken)}/sendMessage?chat_id=${encode(chatId)}&text=${encode(text)}"
        return try {
            val resp = karooSystem.makeHttpRequest(method = "GET", url = url).first()
            val bodyStr = resp.body?.let { String(it) } ?: ""
            Timber.i("Telegram response ${resp.statusCode}: $bodyStr")
            when {
                bodyStr.contains("\"ok\":true") -> null
                bodyStr.contains("\"description\":\"") -> bodyStr.substringAfter("\"description\":\"").substringBefore("\"").ifBlank { "Unknown error" }
                else -> "HTTP ${resp.statusCode}"
            }
        } catch (e: Exception) {
            Timber.e(e, "Telegram send failed")
            "No network"
        }
    }

    private suspend fun sendWhatsApp(settings: BeaconSettings, text: String): String? {
        if (settings.whatsappPhone.isBlank()) return "Recipient phone is empty"
        if (settings.greenApiUrl.isBlank()) return "GREEN-API URL is empty"
        if (settings.greenApiInstanceId.isBlank()) return "GREEN-API instance ID is empty"
        if (settings.greenApiToken.isBlank()) return "GREEN-API token is empty"

        // Format: international number + @c.us
        val digits = settings.whatsappPhone.filter { it.isDigit() }
        val chatId = "$digits@c.us"

        val url = "${settings.greenApiUrl.trimEnd('/')}/waInstance${settings.greenApiInstanceId}/sendMessage/${settings.greenApiToken}"
        val escapedText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val body = """{"chatId":"$chatId","message":"$escapedText"}"""
        return try {
            val resp = karooSystem.makeHttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("Content-Type" to "application/json"),
                body = body.toByteArray()
            ).first()
            val bodyStr = resp.body?.let { String(it) } ?: ""
            Timber.i("WhatsApp GREEN-API response ${resp.statusCode}: $bodyStr")
            when {
                bodyStr.contains("\"idMessage\"") -> null
                bodyStr.contains("\"message\":\"") -> bodyStr.substringAfter("\"message\":\"").substringBefore("\"").ifBlank { "Unknown error" }
                else -> "HTTP ${resp.statusCode}"
            }
        } catch (e: Exception) {
            Timber.e(e, "WhatsApp send failed")
            "No network"
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60; val m = minutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}

private fun KarooSystemService.makeHttpRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
) = callbackFlow {
    val listenerId = addConsumer(
        OnHttpResponse.MakeHttpRequest(method = method, url = url, waitForConnection = false, headers = headers, body = body),
        onEvent = { event: OnHttpResponse ->
            if (event.state is HttpResponseState.Complete) {
                trySendBlocking(event.state as HttpResponseState.Complete)
                close()
            }
        },
        onError = { err: String -> close(IllegalStateException(err)); Unit }
    )
    awaitClose { removeConsumer(listenerId) }
}
