package com.clashai.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.ImageDecoder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClashAiApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashAiApp() {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var analysis by remember {
        mutableStateOf("برای شروع، تصویر صفحه نبرد را انتخاب کن.")
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not support persistable permissions.
            }
            selectedUri = uri
            analysis = "تصویر دریافت شد. موتور تشخیص کارت‌ها و وضعیت میدان در مرحله بعدی به این بخش وصل می‌شود."
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Clash AI Coach") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("مربی هوشمند کلش رویال", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "نسخه پایه اندروید؛ تصویر نبرد را وارد کن تا در نسخه‌های بعدی کارت‌ها، اکسیر و تهدیدها تحلیل شوند."
                )
                Button(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("انتخاب تصویر از گالری")
                }

                selectedUri?.let { uri ->
                    val bitmap = remember(uri) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "تصویر انتخاب‌شده از نبرد",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("وضعیت تحلیل", style = MaterialTheme.typography.titleMedium)
                        Text(analysis)
                    }
                }

                Text("برنامه توسعه", style = MaterialTheme.typography.titleMedium)
                Text("۱. تشخیص زمین، برج‌ها و کارت‌ها با بینایی کامپیوتری")
                Text("۲. تخمین اکسیر و تشخیص تهدیدهای حریف")
                Text("۳. پیشنهاد حرکت و توضیح دلیل آن")
                Text("۴. آموزش و ارزیابی در شبیه‌ساز آفلاین")
                Text(
                    "این نسخه هنوز بازی را کنترل نمی‌کند و تحلیل خودکار تصویر نیز در آن پیاده‌سازی نشده است.",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
