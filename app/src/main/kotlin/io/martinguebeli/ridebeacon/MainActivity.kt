package io.martinguebeli.ridebeacon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.martinguebeli.ridebeacon.sender.MessageSender
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.martinguebeli.ridebeacon.model.BeaconSettings
import io.martinguebeli.ridebeacon.settings.SettingsRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(this)

        setContent {
            var settings by remember { mutableStateOf(BeaconSettings()) }
            var saved by remember { mutableStateOf(false) }
            var smsTestStatus by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                repo.settingsFlow.collect { settings = it }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF6D00),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "RideBeacon",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF6D00)
                        )
                        Text(
                            "Notifies your people when you ride.",
                            fontSize = 12.sp,
                            color = Color(0xFF9E9E9E)
                        )

                        Divider(color = Color(0xFF2E2E2E))

                        // Rider info
                        SectionTitle("Rider")
                        BeaconTextField("Your name", settings.riderName) {
                            settings = settings.copy(riderName = it); saved = false
                        }
                        BeaconTextField("Hammerhead Live key (e.g. 3738Ag)", settings.karooLiveKey) {
                            settings = settings.copy(karooLiveKey = it); saved = false
                        }

                        Divider(color = Color(0xFF2E2E2E))

                        // WhatsApp
                        SectionTitle("WhatsApp (CallMeBot)")
                        BeaconSwitch("Enable WhatsApp", settings.whatsappEnabled) {
                            settings = settings.copy(whatsappEnabled = it); saved = false
                        }
                        if (settings.whatsappEnabled) {
                            BeaconTextField("Phone (+41791234567)", settings.whatsappPhone) {
                                settings = settings.copy(whatsappPhone = it); saved = false
                            }
                            BeaconTextField("CallMeBot API key", settings.whatsappApiKey) {
                                settings = settings.copy(whatsappApiKey = it); saved = false
                            }
                        }

                        Divider(color = Color(0xFF2E2E2E))

                        // SMS
                        SectionTitle("SMS (TextBelt)")
                        BeaconSwitch("Enable SMS", settings.smsEnabled) {
                            settings = settings.copy(smsEnabled = it); saved = false
                        }
                        if (settings.smsEnabled) {
                            BeaconTextField("Phone (+41791234567)", settings.smsPhone) {
                                settings = settings.copy(smsPhone = it); saved = false; smsTestStatus = ""
                            }
                            BeaconTextField("TextBelt key (leave 'textbelt' for free)", settings.smsBeltKey) {
                                settings = settings.copy(smsBeltKey = it); saved = false; smsTestStatus = ""
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        smsTestStatus = "Sending…"
                                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                MessageSender().sendTestSms(settings)
                                                smsTestStatus = "✓ SMS sent!"
                                            } catch (e: Exception) {
                                                smsTestStatus = "✗ Failed: ${e.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Send test SMS", color = Color.White, fontSize = 12.sp)
                                }
                                if (smsTestStatus.isNotEmpty()) {
                                    Text(
                                        smsTestStatus,
                                        fontSize = 11.sp,
                                        color = if (smsTestStatus.startsWith("✓")) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        Divider(color = Color(0xFF2E2E2E))

                        // Messages
                        SectionTitle("Messages")
                        Text(
                            "Placeholders: {name}  {livelink}  {distance}  {duration}",
                            fontSize = 10.sp,
                            color = Color(0xFF757575)
                        )
                        BeaconSwitch("Send on ride start", settings.notifyOnStart) {
                            settings = settings.copy(notifyOnStart = it); saved = false
                        }
                        if (settings.notifyOnStart) {
                            BeaconTextField("Start message", settings.startMessage, singleLine = false) {
                                settings = settings.copy(startMessage = it); saved = false
                            }
                        }
                        BeaconSwitch("Send on ride stop", settings.notifyOnStop) {
                            settings = settings.copy(notifyOnStop = it); saved = false
                        }
                        if (settings.notifyOnStop) {
                            BeaconTextField("Stop message", settings.stopMessage, singleLine = false) {
                                settings = settings.copy(stopMessage = it); saved = false
                            }
                        }

                        Divider(color = Color(0xFF2E2E2E))

                        // Save button
                        Button(
                            onClick = {
                                lifecycleScope.launch {
                                    repo.save(settings)
                                    saved = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                        ) {
                            Text(if (saved) "✓ Saved" else "Save Settings", color = Color.White)
                        }

                        Text(
                            "v1.0.0 · RideBeacon",
                            fontSize = 9.sp,
                            color = Color(0xFF424242),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF6D00))
}

@Composable
private fun BeaconTextField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFFFF6D00),
            unfocusedBorderColor = Color(0xFF424242),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color(0xFFCCCCCC),
            focusedLabelColor = Color(0xFFFF6D00),
            unfocusedLabelColor = Color(0xFF757575),
            cursorColor = Color(0xFFFF6D00),
        )
    )
}

@Composable
private fun BeaconSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFFEEEEEE))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
        )
    }
}
