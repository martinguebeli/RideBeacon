package io.martinguebeli.ridebeacon.sender

import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.model.LIVE_BASE_URL
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MessageSender {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun sendStart(settings: BeaconSettings) {
        sendStartResult(settings)
    }

    fun sendStop(settings: BeaconSettings, distanceKm: Double, durationMin: Int) {
        sendStopResult(settings, distanceKm, durationMin)
    }

    /** Returns null on success, or an error message on failure. */
    fun sendStartResult(settings: BeaconSettings): String? {
        val text = buildMessage(settings.startMessage, settings, distanceKm = null, duration = null)
        return dispatchResult(settings, text)
    }

    /** Returns null on success, or an error message on failure. */
    fun sendStopResult(settings: BeaconSettings, distanceKm: Double, durationMin: Int): String? {
        val dur = formatDuration(durationMin)
        val text = buildMessage(settings.stopMessage, settings, distanceKm, dur)
        return dispatchResult(settings, text)
    }

    /** Returns null on success, or an error message on failure. */
    fun sendTestSmsResult(settings: BeaconSettings): String? {
        return sendSmsResult(settings.smsPhone, settings.smsBeltKey, "RideBeacon Test SMS from ${settings.riderName}")
    }

    private fun buildMessage(
        template: String,
        settings: BeaconSettings,
        distanceKm: Double?,
        duration: String?,
    ): String {
        val liveLink = if (settings.karooLiveKey.isNotBlank())
            "$LIVE_BASE_URL${settings.karooLiveKey}" else ""
        return template
            .replace("{name}", settings.riderName)
            .replace("{livelink}", liveLink)
            .replace("{distance}", distanceKm?.let { "%.1f".format(it) } ?: "")
            .replace("{duration}", duration ?: "")
    }

    private fun dispatchResult(settings: BeaconSettings, text: String): String? {
        if (settings.whatsappEnabled && settings.whatsappPhone.isNotBlank() && settings.whatsappApiKey.isNotBlank()) {
            sendWhatsApp(settings.whatsappPhone, settings.whatsappApiKey, text)
        }
        if (settings.smsEnabled && settings.smsPhone.isNotBlank()) {
            return sendSmsResult(settings.smsPhone, settings.smsBeltKey, text)
        }
        return null
    }

    private fun sendWhatsApp(phone: String, apiKey: String, text: String) {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://api.callmebot.com/whatsapp.php?phone=$phone&apikey=$apiKey&text=$encoded"
        try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                Timber.i("WhatsApp sent: ${resp.code}")
            }
        } catch (e: Exception) {
            Timber.e(e, "WhatsApp send failed")
        }
    }

    private fun sendSms(phone: String, apiKey: String, text: String) {
        sendSmsResult(phone, apiKey, text)
    }

    private fun sendSmsResult(phone: String, apiKey: String, text: String): String? {
        if (phone.isBlank()) return "Phone number is empty"
        val body = FormBody.Builder()
            .add("phone", phone)
            .add("message", text)
            .add("key", apiKey.ifBlank { "textbelt" })
            .build()
        val req = Request.Builder()
            .url("https://textbelt.com/text")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                Timber.i("SMS response ${resp.code}: $bodyStr")
                if (resp.isSuccessful && bodyStr.contains("\"success\":true")) null
                else "Failed: $bodyStr"
            }
        } catch (e: Exception) {
            Timber.e(e, "SMS send failed")
            e.message ?: "Unknown error"
        }
    }

    fun sendTestSms(settings: BeaconSettings) {
        sendSms(settings.smsPhone, settings.smsBeltKey, "RideBeacon Test SMS from ${settings.riderName}")
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}
