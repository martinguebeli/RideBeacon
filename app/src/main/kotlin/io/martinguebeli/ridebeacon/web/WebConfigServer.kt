package io.martinguebeli.ridebeacon.web

import fi.iki.elonen.NanoHTTPD
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class WebConfigServer(
    port: Int,
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveForm()
            session.method == Method.POST && session.uri == "/save" -> handleSave(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveForm(): Response {
        val settings = runBlocking { repo.settingsFlow.firstOrNull() ?: BeaconSettings() }
        val html = buildHtml(settings)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleSave(session: IHTTPSession): Response {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val params = session.parameters

        fun param(key: String) = params[key]?.firstOrNull()?.trim() ?: ""
        fun bool(key: String) = params[key]?.firstOrNull() == "on"

        val updated = BeaconSettings(
            riderName        = param("riderName"),
            karooLiveKey     = param("karooLiveKey"),
            whatsappEnabled  = bool("whatsappEnabled"),
            whatsappPhone    = param("whatsappPhone"),
            whatsappApiKey   = param("whatsappApiKey"),
            smsEnabled       = bool("smsEnabled"),
            smsPhone         = param("smsPhone"),
            smsBeltKey       = param("smsBeltKey"),
            startMessage     = param("startMessage"),
            stopMessage      = param("stopMessage"),
            notifyOnStart    = bool("notifyOnStart"),
            notifyOnStop     = bool("notifyOnStop"),
        )

        scope.launch { repo.save(updated) }
        Timber.i("WebConfigServer: settings saved via web")

        val html = """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>RideBeacon</title>
            <style>body{font-family:sans-serif;background:#121212;color:#eee;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;}
            .box{background:#1e1e1e;border-radius:12px;padding:32px 40px;text-align:center;}
            h2{color:#FF6D00;margin-bottom:8px;}p{color:#9e9e9e;}
            a{color:#FF6D00;text-decoration:none;font-weight:bold;}</style>
            <meta http-equiv="refresh" content="2;url=/">
            </head><body><div class="box">
            <h2>✅ Saved!</h2>
            <p>Settings updated on your Karoo.<br>Returning to settings in 2 seconds…</p>
            <a href="/">← Back</a>
            </div></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun buildHtml(s: BeaconSettings): String {
        fun checked(b: Boolean) = if (b) "checked" else ""
        fun v(value: String) = value.replace("\"", "&quot;")

        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>RideBeacon Config</title>
  <style>
    * { box-sizing: border-box; }
    body { font-family: -apple-system, sans-serif; background: #121212; color: #eee; margin: 0; padding: 16px; }
    h1 { color: #FF6D00; font-size: 22px; margin-bottom: 4px; }
    .subtitle { color: #9e9e9e; font-size: 13px; margin-bottom: 24px; }
    .card { background: #1e1e1e; border-radius: 10px; padding: 16px; margin-bottom: 16px; }
    h2 { color: #FF6D00; font-size: 14px; font-weight: 600; margin: 0 0 12px 0; text-transform: uppercase; letter-spacing: 0.5px; }
    label { display: block; font-size: 12px; color: #9e9e9e; margin-bottom: 4px; margin-top: 10px; }
    input[type=text], textarea {
      width: 100%; padding: 10px 12px; background: #2a2a2a; border: 1px solid #444;
      border-radius: 6px; color: #fff; font-size: 14px; font-family: inherit;
    }
    input[type=text]:focus, textarea:focus { outline: none; border-color: #FF6D00; }
    textarea { min-height: 70px; resize: vertical; }
    .toggle-row { display: flex; align-items: center; justify-content: space-between; margin: 8px 0; }
    .toggle-label { font-size: 13px; color: #eee; }
    .toggle { position: relative; display: inline-block; width: 44px; height: 24px; }
    .toggle input { opacity: 0; width: 0; height: 0; }
    .slider { position: absolute; cursor: pointer; inset: 0; background: #444; border-radius: 24px; transition: .2s; }
    .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background: white; border-radius: 50%; transition: .2s; }
    input:checked + .slider { background: #FF6D00; }
    input:checked + .slider:before { transform: translateX(20px); }
    .hint { font-size: 11px; color: #666; margin-top: 6px; }
    .placeholder-hint { font-size: 11px; color: #555; margin: 4px 0 8px 0; font-family: monospace; }
    button[type=submit] {
      width: 100%; padding: 14px; background: #FF6D00; color: white; font-size: 16px;
      font-weight: 600; border: none; border-radius: 8px; cursor: pointer; margin-top: 8px;
    }
    button[type=submit]:active { background: #e65100; }
    .version { text-align: center; font-size: 10px; color: #333; margin-top: 16px; }
  </style>
</head>
<body>
<h1>🚴 RideBeacon</h1>
<p class="subtitle">Configure your ride notifications</p>

<form method="POST" action="/save">

  <div class="card">
    <h2>Rider</h2>
    <label>Your name</label>
    <input type="text" name="riderName" value="${v(s.riderName)}" placeholder="Martin">
    <label>Hammerhead Live key</label>
    <input type="text" name="karooLiveKey" value="${v(s.karooLiveKey)}" placeholder="e.g. 3738Ag">
    <p class="hint">Find it in your Hammerhead app under Live Track. Only the code at the end of the URL.</p>
  </div>

  <div class="card">
    <h2>WhatsApp (CallMeBot)</h2>
    <div class="toggle-row">
      <span class="toggle-label">Enable WhatsApp</span>
      <label class="toggle"><input type="checkbox" id="whatsappEnabled" name="whatsappEnabled" ${checked(s.whatsappEnabled)} onchange="if(this.checked)document.getElementById('smsEnabled').checked=false"><span class="slider"></span></label>
    </div>
    <label>Phone number</label>
    <input type="text" name="whatsappPhone" value="${v(s.whatsappPhone)}" placeholder="+41791234567">
    <label>CallMeBot API key</label>
    <input type="text" name="whatsappApiKey" value="${v(s.whatsappApiKey)}" placeholder="123456">
  </div>

  <div class="card">
    <h2>SMS (TextBelt)</h2>
    <div class="toggle-row">
      <span class="toggle-label">Enable SMS</span>
      <label class="toggle"><input type="checkbox" id="smsEnabled" name="smsEnabled" ${checked(s.smsEnabled)} onchange="if(this.checked)document.getElementById('whatsappEnabled').checked=false"><span class="slider"></span></label>
    </div>
    <label>Phone number</label>
    <input type="text" name="smsPhone" value="${v(s.smsPhone)}" placeholder="+41791234567">
    <label>TextBelt API key</label>
    <input type="text" name="smsBeltKey" value="${v(s.smsBeltKey)}" placeholder="textbelt or your paid key">
    <p class="hint">Leave as <strong>textbelt</strong> for 1 free SMS/day. Paste your paid key here.</p>
  </div>

  <div class="card">
    <h2>Messages</h2>
    <p class="placeholder-hint">Placeholders: {name} &nbsp; {livelink} &nbsp; {distance} &nbsp; {duration}</p>
    <div class="toggle-row">
      <span class="toggle-label">Send on ride start</span>
      <label class="toggle"><input type="checkbox" name="notifyOnStart" ${checked(s.notifyOnStart)}><span class="slider"></span></label>
    </div>
    <label>Start message</label>
    <textarea name="startMessage">${v(s.startMessage)}</textarea>
    <div class="toggle-row" style="margin-top:12px;">
      <span class="toggle-label">Send on ride stop</span>
      <label class="toggle"><input type="checkbox" name="notifyOnStop" ${checked(s.notifyOnStop)}><span class="slider"></span></label>
    </div>
    <label>Stop message</label>
    <textarea name="stopMessage">${v(s.stopMessage)}</textarea>
  </div>

  <button type="submit">💾 Save Settings</button>
</form>

<p class="version">v1.1.0 · RideBeacon</p>
</body>
</html>
        """.trimIndent()
    }
}
