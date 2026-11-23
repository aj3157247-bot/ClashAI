package com.clashai.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val captureLogs = mutableStateListOf<String>()

    private var captureDescription by mutableStateOf(
        "مشاهده صفحه هنوز شروع نشده است."
    )

    private var brightness by mutableStateOf(0)
    private var red by mutableStateOf(0)
    private var green by mutableStateOf(0)
    private var blue by mutableStateOf(0)

    private var receiverRegistered = false

    private val frameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_FRAME) {
                return
            }

            brightness = intent.getIntExtra(
                ScreenCaptureService.EXTRA_BRIGHTNESS, 0
            )

            red = intent.getIntExtra(
                ScreenCaptureService.EXTRA_RED, 0
            )

            green = intent.getIntExtra(
                ScreenCaptureService.EXTRA_GREEN, 0
            )

            blue = intent.getIntExtra(
                ScreenCaptureService.EXTRA_BLUE, 0
            )

            captureDescription = intent.getStringExtra(
                ScreenCaptureService.EXTRA_DESCRIPTION
            ) ?: "فریم دریافت شد."

            val log =
                "$captureDescription | روشنایی: $brightness | RGB: $red,$green,$blue"

            captureLogs.add(0, log)

            while (captureLogs.size > 30) {
                captureLogs.removeAt(captureLogs.lastIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ClashAiApp(
                startCapture = { requestScreenCapture() },
                stopCapture = { stopScreenCapture() },
                captureDescription = { captureDescription },
                brightness = { brightness },
                red = { red },
                green = { green },
                blue = { blue },
                logs = { captureLogs.toList() }
            )
        }
    }

    override fun onStart() {
        super.onStart()

        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_FRAME)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    frameReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(frameReceiver, filter)
            }

            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(frameReceiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        screenCaptureLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_START

                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        ScreenCaptureService.EXTRA_DATA,
                        result.data
                    )
                }

                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                captureDescription =
                    "اجازه دریافت شد؛ مشاهده صفحه در حال شروع است."
            } else {
                captureDescription =
                    "اجازه مشاهده صفحه داده نشد."
            }
        }

    private fun stopScreenCapture() {
        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        startService(stopIntent)

        captureDescription = "مشاهده صفحه متوقف شد."
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ClashAiApp(
    startCapture: () -> Unit,
    stopCapture: () -> Unit,
    captureDescription: () -> String,
    brightness: () -> Int,
    red: () -> Int,
    green: () -> Int,
    blue: () -> Int,
    logs: () -> List<String>
) {
    var elixir by remember { mutableFloatStateOf(5f) }

    var threat by remember {
        mutableStateOf(ThreatType.NONE)
    }

    var hand by remember {
        mutableStateOf(
            listOf("Knight", "Archers", "Arrows", "Musketeer")
        )
    }

    var advice by remember {
        mutableStateOf(
            CoachAdvice(
                null,
                "آماده تحلیل",
                "اکسیر، کارت‌ها و تهدید حریف را مشخص کن."
            )
        )
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("ClashAI · مربی هوشمند")
                    }
                )
            }
        ) { padding ->

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                Text(
                    "مربی هوشمند Clash Royale",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    "صفحه را با اجازه خودت مشاهده کن، وضعیت را تحلیل کن و پیشنهاد حرکت بگیر."
                )

                // SCREEN OBSERVER

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            "۱. مشاهده زنده صفحه",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(captureDescription())

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = startCapture,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("شروع مشاهده")
                            }

                            OutlinedButton(
                                onClick = stopCapture,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("توقف")
                            }
                        }

                        Text("روشنایی نمونه: ${brightness()}")

                        Text(
                            "رنگ نمونه RGB: ${red()}, ${green()}, ${blue()}"
                        )

                        Text(
                            "این اطلاعات فقط نمونه‌برداری رنگ و روشنایی صفحه است؛ هنوز تشخیص کارت، برج یا اکسیر انجام نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ELEXIR

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "۲. اکسیر فعلی",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Text(
                            "${elixir.toInt()} از ۱۰",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Slider(
                            value = elixir,
                            onValueChange = { elixir = it },
                            valueRange = 0f..10f,
                            steps = 9
                        )
                    }
                }

                // HAND

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "۳. چهار کارت فعلی دست",
                            style = MaterialTheme.typography.titleMedium
                        )

                        hand.forEachIndexed { index, cardName ->
                            CardPicker(
                                label = "کارت ${index + 1}",
                                selected = cardName,
                                onSelected = { newName ->
                                    if (
                                        newName !in hand ||
                                        hand[index] == newName
                                    ) {
                                        hand = hand.toMutableList().also {
                                            it[index] = newName
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // THREAT

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "۴. تهدید حریف",
                            style = MaterialTheme.typography.titleMedium
                        )

                        ThreatPicker(
                            selected = threat,
                            onSelected = { threat = it }
                        )
                    }
                }

                // DECISION ENGINE

                Button(
                    onClick = {
                        advice = ClashDecisionEngine.recommend(
                            hand,
                            elixir.toInt(),
                            threat
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تحلیل و پیشنهاد حرکت")
                }

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "۵. نتیجه تحلیل",
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(
                            advice.title,
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Text(advice.reason)

                        advice.card?.let { card ->
                            Text("کارت پیشنهادی: ${card.name}")
                            Text("هزینه: ${card.elixir} اکسیر")
                            Text(card.explanation)
                        }
                    }
                }

                // DECISION LOGS

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "۶. گزارش مشاهده صفحه",
                            style = MaterialTheme.typography.titleLarge
                        )

                        val currentLogs = logs()

                        if (currentLogs.isEmpty()) {
                            Text("هنوز گزارشی ثبت نشده است.")
                        } else {
                            currentLogs.take(12).forEachIndexed { index, item ->
                                Text("${index + 1}. $item")
                            }
                        }
                    }
                }

                // INDEPENDENT SIMULATOR

                Text(
                    "۷. آزمایشگاه مستقل",
                    style = MaterialTheme.typography.titleLarge
                )

                BattleLabPanel()

                Text(
                    "نکته: مشاهده صفحه با اجازه اندروید انجام می‌شود. تحلیل رنگی فعلی تشخیص هوشمند کارت‌ها نیست. پیشنهاد حرکت به اطلاعات دستی متکی است. هیچ لمس یا فرمانی به Clash Royale فرستاده نمی‌شود.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CardPicker(
    label: String,
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label: $selected")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ClashCardCatalog.cards.forEach { card ->
                DropdownMenuItem(
                    text = {
                        Text("${card.name} · ${card.elixir} اکسیر")
                    },
                    onClick = {
                        onSelected(card.name)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreatPicker(
    selected: ThreatType,
    onSelected: (ThreatType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected.title)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ThreatType.values().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
