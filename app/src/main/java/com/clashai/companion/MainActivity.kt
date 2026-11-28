package com.aiplayer.companion

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        AppState.inFront = true
    }

    override fun onPause() {
        AppState.inFront = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiSettings.init(this)
        setContent {
            UniversalGameAI()
        }
    }
}

@Composable
fun UniversalGameAI() {
    val context = LocalContext.current

    var status by remember { mutableStateOf("آماده") }
    var captureOn by remember { mutableStateOf(false) }
    var accessibilityOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var agentOn by remember { mutableStateOf(false) }
    var adviceText by remember { mutableStateOf(AdviceLog.lastText) }
    var keyText by remember { mutableStateOf(AiSettings.apiKey) }
    var modelText by remember { mutableStateOf(AiSettings.model) }
    var bKeyText by remember { mutableStateOf(AiSettings.backupKey) }
    var bEndpointText by remember { mutableStateOf(AiSettings.backupEndpoint) }
    var bModelText by remember { mutableStateOf(AiSettings.backupModel) }
    var endpointText by remember { mutableStateOf(AiSettings.endpoint) }
    var showKey by remember { mutableStateOf(false) }
    var modeText by remember { mutableStateOf(AiSettings.mode) }
    var touchesOn by remember { mutableStateOf(AiSettings.showTouches) }
    var realtimeOn by remember { mutableStateOf(AiSettings.realtime) }
    var gridOn by remember { mutableStateOf(AiSettings.gridOverlay) }
    var gapText by remember { mutableStateOf((AiSettings.requestGapMs / 1000L).toString()) }
    var crashText by remember { mutableStateOf(CrashLog.last(context)) }

    // Re-check the accessibility service every second, because the user enables it in another screen.
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = AutoPlayAccessibilityService.instance != null
            overlayOn = Settings.canDrawOverlays(context)
            agentOn = AgentLoop.isRunning()
            adviceText = AdviceLog.lastText
            crashText = CrashLog.last(context)
            delay(1000)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
            captureOn = true
            status = "کپچر صفحه فعال شد"
        } else {
            status = "اجازه‌ی کپچر صفحه داده نشد"
        }
    }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("AI Player", style = MaterialTheme.typography.headlineMedium)
                Text("بازیکن هوشمند برای بازی‌های آفلاین خودت")
                HorizontalDivider()

                Text("مرحله ۱ (اختیاری، دیگر لازم نیست): کپچر صفحه — ${if (captureOn) "فعال" else "غیرفعال"}")
                Button(
                    onClick = {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اجازه‌ی کپچر صفحه") }

                Text("مرحله ۲: سرویس دسترسی (برای لمس) — ${if (accessibilityOn) "متصل" else "قطع؛ در تنظیمات خاموش و روشن کن"}")
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("باز کردن تنظیمات دسترسی") }
                OutlinedButton(
                    onClick = {
                        val details = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(details)
                        status = "در صفحه‌ی برنامه، از ⋮ گزینه‌ی «Allow restricted settings» را بزن"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("صفحه‌ی AI Player (برای مجاز کردن تنظیمات محدود)") }

                Text("مرحله ۳: پنل ربات (دکمه‌ی شروع/توقف و وضعیت زنده روی بازی؛ نیازی به مجوز نمایش روی برنامه‌ها نیست)")
                Button(
                    onClick = {
                        val svc = AutoPlayAccessibilityService.instance
                        if (svc == null) {
                            status = "ابتدا سرویس دسترسی را فعال کن"
                        } else {
                            svc.showHud()
                            status = "پنل ربات نمایش داده شد. بازی را باز کن و «شروع بازی» را بزن."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("نمایش پنل ربات") }

                HorizontalDivider()
                Text("حالت بازی", style = MaterialTheme.typography.titleMedium)
                Text(
                    "فعلی: " + when (modeText) {
                        AiSettings.MODE_ONLINE -> "آنلاین (هوش مصنوعی ابری برای همه‌ی بازی‌ها)"
                        AiSettings.MODE_AUTO -> "هوشمند (ربات محلی اگر بازی را بشناسد، وگرنه هوش مصنوعی ابری)"
                        else -> "آفلاین (ربات محلی، بدون اینترنت)"
                    }
                )
                Button(
                    onClick = {
                        AiSettings.mode = AiSettings.MODE_AUTO
                        modeText = AiSettings.MODE_AUTO
                        status = if (AiSettings.apiKey.isBlank()) "حالت هوشمند: برای بازی‌های غیر لودو کلید API لازم است" else "حالت هوشمند فعال شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("هوشمند: محلی + ابری (پیشنهادی)") }
                OutlinedButton(
                    onClick = {
                        AiSettings.mode = AiSettings.MODE_OFFLINE
                        modeText = AiSettings.MODE_OFFLINE
                        status = "حالت آفلاین: ربات محلی (لودو)"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("آفلاین: ربات محلی (لودو و شطرنج، بدون اینترنت)") }
                OutlinedButton(
                    onClick = {
                        AiSettings.mode = AiSettings.MODE_ONLINE
                        modeText = AiSettings.MODE_ONLINE
                        status = if (AiSettings.apiKey.isBlank()) "حالت آنلاین: ابتدا کلید API را ذخیره کن" else "حالت آنلاین: هوش مصنوعی ابری"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("آنلاین: هوش مصنوعی ابری (بازی‌های سخت‌تر)") }

                OutlinedButton(
                    onClick = {
                        AiSettings.gridOverlay = !AiSettings.gridOverlay
                        gridOn = AiSettings.gridOverlay
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("شبکه‌ی مختصات روی تصویر (دقت لمس): " + if (gridOn) "روشن" else "خاموش") }

                OutlinedButton(
                    onClick = {
                        AiSettings.showTouches = !AiSettings.showTouches
                        touchesOn = AiSettings.showTouches
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("نمایش جای لمس ربات روی صفحه: " + if (touchesOn) "روشن" else "خاموش") }

                OutlinedButton(
                    onClick = {
                        AiSettings.realtime = !AiSettings.realtime
                        realtimeOn = AiSettings.realtime
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("حالت بلادرنگ ابری (ماشین‌سواری/اکشن کند، تک‌نفره): " + if (realtimeOn) "روشن" else "خاموش") }

                HorizontalDivider()
                Text("هوش مصنوعی ابری (برای بازی‌های دیگر)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("کلید API") },
                    singleLine = true,
                    visualTransformation = if (showKey) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { showKey = !showKey },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (showKey) "پنهان کردن کلید" else "نمایش کلید") }
                OutlinedTextField(
                    value = endpointText,
                    onValueChange = { endpointText = it },
                    label = { Text("آدرس API (Groq پیش‌فرض است)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelText,
                    onValueChange = { modelText = it },
                    label = { Text("نام مدل (vision، از console.groq.com/docs/vision)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gapText,
                    onValueChange = { gapText = it.filter { ch -> ch.isDigit() }.take(2) },
                    label = { Text("فاصله‌ی درخواست‌ها به ثانیه (کمتر = سریع‌تر، ولی احتمال محدودیت API)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        AiSettings.requestGapMs = (gapText.toLongOrNull() ?: 3L) * 1000L
                        AiSettings.apiKey = keyText
                        AiSettings.model = modelText
                        AiSettings.endpoint = endpointText
                        endpointText = AiSettings.endpoint
                        status = if (keyText.isBlank()) "کلید خالی است" else "تنظیمات Grok ذخیره شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره‌ی تنظیمات Grok") }
                Text("هوش مصنوعی دوم (اختیاری): اگر اولی خطا داد یا محدود شد، این جایگزین می‌شود. هر API سازگار با OpenAI که تصویر می‌فهمد.")
                OutlinedTextField(
                    value = bKeyText,
                    onValueChange = { bKeyText = it },
                    label = { Text("کلید API دوم") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bEndpointText,
                    onValueChange = { bEndpointText = it },
                    label = { Text("آدرس API دوم (…/chat/completions)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bModelText,
                    onValueChange = { bModelText = it },
                    label = { Text("نام مدل دوم") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        AiSettings.backupKey = bKeyText
                        AiSettings.backupEndpoint = bEndpointText
                        AiSettings.backupModel = bModelText
                        bEndpointText = AiSettings.backupEndpoint
                        status = if (AiSettings.hasBackup()) "هوش مصنوعی دوم ذخیره شد" else "هوش مصنوعی دوم ناقص است (کلید، آدرس و مدل هر سه لازم است)"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره‌ی هوش مصنوعی دوم") }
                Text("تصمیم هوش مصنوعی: $adviceText")

                HorizontalDivider()
                Text("موتور یادگیری: خودت بازی کن تا ربات یاد بگیرد، بعد به‌جای تو بازی کند. دکمه‌های «یادگیری» و «بازی با یادگیری» روی پنل شناور هستند.")
                Button(
                    onClick = {
                        val svc = AutoPlayAccessibilityService.instance
                        if (svc == null) {
                            status = "سرویس دسترسی فعال نیست"
                            return@Button
                        }
                        svc.showHud()
                        status = "پنل شناور باز شد؛ به بازی برو و روی «یادگیری» بزن"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("نمایش پنل یادگیری") }
                OutlinedButton(
                    onClick = {
                        LearningCoach.clearAll()
                        status = "حافظه‌ی یادگیری پاک شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("پاک کردن حافظه‌ی یادگیری") }

                HorizontalDivider()
                Text("وضعیت: $status")
                Text("ربات: ${if (agentOn) "در حال اجرا" else "متوقف"}")
                Text("ماژول‌ها: لودو و شطرنج (محلی)؛ هوش مصنوعی ابری در حالت آنلاین و هوشمند")

                OutlinedButton(
                    onClick = {
                        val svc = AutoPlayAccessibilityService.instance
                        if (svc == null) {
                            status = "سرویس دسترسی فعال نیست"
                            return@OutlinedButton
                        }
                        status = "۳ ثانیه بعد به بازی برو؛ یک لمس وسط صفحه زده می‌شود"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            svc.perform(GameAction.Tap(0.5f, 0.5f))
                        }, 3000L)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تست لمس (وسط صفحه)") }

                OutlinedButton(
                    onClick = {
                        val svc = AutoPlayAccessibilityService.instance
                        if (svc == null) {
                            status = "سرویس دسترسی فعال نیست"
                            return@OutlinedButton
                        }
                        status = "در حال گرفتن اسکرین‌شات..."
                        svc.captureFrame { bmp ->
                            status = if (bmp == null) {
                                "اسکرین‌شات نشد؛ متن خطا را در بخش تصمیم هوش مصنوعی ببین"
                            } else {
                                "اسکرین‌شات گرفته شد: ${bmp.width} x ${bmp.height}"
                            }
                            bmp?.recycle()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("تست اسکرین‌شات") }

                HorizontalDivider()
                Text("آخرین خطای برنامه (اگر برنامه خودش بسته شد، این متن را بفرست):")
                Text(if (crashText.isBlank()) "هیچ خطایی ثبت نشده" else crashText)
                OutlinedButton(
                    onClick = {
                        CrashLog.clear(context)
                        crashText = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("پاک کردن خطا") }

                OutlinedButton(
                    onClick = {
                        AgentLoop.stop()
                        context.stopService(Intent(context, FloatingControlService::class.java))
                        val stop = Intent(context, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_STOP
                        }
                        context.startService(stop)
                        captureOn = false
                        status = "همه‌ی بخش‌ها متوقف شدند"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("توقف کامل") }
            }
        }
    }
}
