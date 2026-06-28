package io.martinguebeli.ridebeacon.sender

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.model.LIVE_BASE_URL
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
        val dur = formatDuration(durationMin)
        val text = buildMessage(settings.stopMessage, settings, distanceKm, dur)
        return dispatch(settings, text)
    }

    suspend fun sendTestSmsResult(settings: BeaconSettings): String? {
        return sendSms(settings.smsPhone, settings.smsBeltKey, "RideBeacon Test SMS from ${settings.riderName}")
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
        if (settings.whatsappEnabled && settings.whatsappPhone.isNotBlank() && settings.whatsappApiKey.isNotBlank()) {
            sendWhatsApp(settings.whatsappPhone, settings.whatsappApiKey, text)
        }
        if (settings.smsEnabled && settings.smsPhone.isNotBlank()) {
            return sendSms(settings.smsPhone, settings.smsBeltKey, text)
        }
        return null
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

    private suspend fun sendWhatsApp(phone: String, apiKey: String, text: String) {
        val url = "https://api.callmebot.com/whatsapp.php?phone=${encode(phone)}&apikey=${encode(apiKey)}&text=${encode(text)}"
        try {
            val resp = karooSystem.makeHttpRequest(method = "GET", url = url).first()
            Timber.i("WhatsApp sent: ${resp.statusCode}")
        } catch (e: Exception) {
            Timber.e(e, "WhatsApp send failed")
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
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
        onError = { err: String ->
            close(IllegalStateException(err))
            Unit
        }
    )
    awaitClose { removeConsumer(listenerId) }
}
