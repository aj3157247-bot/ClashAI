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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiSettings.init(this)
        GameRegistry.register(GrokGameModule())
        GameRegistry.register(LudoModule())
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
    var showKey by remember { mutableStateOf(false) }

    // Re-check the accessibility service every second, because the user enables it in another screen.
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = AutoPlayAccessibilityService.instance != null
            overlayOn = Settings.canDrawOverlays(context)
            agentOn = AgentLoop.isRunning()
            adviceText = AdviceLog.lastText
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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("AI Player", style = MaterialTheme.typography.headlineMedium)
                Text("بازیکن هوشمند برای بازی‌های آفلاین خودت")
                HorizontalDivider()

                Text("مرحله ۱: کپچر صفحه — ${if (captureOn) "فعال" else "غیرفعال"}")
                Button(
                    onClick = {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اجازه‌ی کپچر صفحه") }

                Text("مرحله ۲: سرویس دسترسی (برای لمس) — ${if (accessibilityOn) "فعال" else "غیرفعال"}")
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("باز کردن تنظیمات دسترسی") }

                Text("مرحله ۳: نمایش روی سایر برنامه‌ها — ${if (overlayOn) "مجاز" else "غیرمجاز"}")
                Button(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            val uri = Uri.parse("package:${context.packageName}")
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                            )
                        } else {
                            status = "مجوز نمایش روی برنامه‌ها از قبل داده شده است"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("اجازه‌ی نمایش روی برنامه‌ها") }

                Text("مرحله ۴: دکمه‌ی شناور")
                Button(
                    onClick = {
                        if (!accessibilityOn) {
                            status = "ابتدا سرویس دسترسی را فعال کن"
                        } else if (!captureOn) {
                            status = "ابتدا کپچر صفحه را فعال کن"
                        } else if (!overlayOn) {
                            status = "ابتدا اجازه‌ی نمایش روی برنامه‌ها را بده"
                        } else {
                            context.startService(Intent(context, FloatingControlService::class.java))
                            status = "دکمه‌ی شناور نمایش داده شد. از روی بازی شروع کن."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("نمایش دکمه‌ی شناور") }

                HorizontalDivider()
                Text("هوش مصنوعی Grok (برای بازی‌های عمومی)", style = MaterialTheme.typography.titleMedium)
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
                    value = modelText,
                    onValueChange = { modelText = it },
                    label = { Text("نام مدل (باید با docs.x.ai یکی باشد)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        AiSettings.apiKey = keyText
                        AiSettings.model = modelText
                        status = if (keyText.isBlank()) "کلید خالی است" else "تنظیمات Grok ذخیره شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ذخیره‌ی تنظیمات Grok") }
                Text("تصمیم هوش مصنوعی: $adviceText")

                HorizontalDivider()
                Text("وضعیت: $status")
                Text("ربات: ${if (agentOn) "در حال اجرا" else "متوقف"}")
                Text("ماژول‌ها: Grok (عمومی) و لودو (اولیه)")

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
