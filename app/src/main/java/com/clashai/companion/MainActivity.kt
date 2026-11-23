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
    var analysis by remember { mutableStateOf("تصویر مسابقه را می‌توانی برای مراجعه انتخاب کنی. فعلاً پیشنهاد حرکت با اطلاعاتی که خودت وارد می‌کنی ساخته می‌شود.") }
    var elixir by remember { mutableFloatStateOf(5f) }
    var threat by remember { mutableStateOf(ThreatType.NONE) }
    var hand by remember { mutableStateOf(listOf("Knight", "Archers", "Arrows", "Musketeer")) }
    var advice by remember {
        mutableStateOf(
            CoachAdvice(
                null,
                "آماده تحلیل",
                "اکسیر، چهار کارت فعلی و نوع تهدید حریف را مشخص کن؛ سپس دکمه پیشنهاد حرکت را بزن."
            )
        )
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
            analysis = "تصویر انتخاب شد. در این نسخه تصویر فقط برای مشاهده است و هنوز به‌صورت خودکار تشخیص داده نمی‌شود."
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Clash AI Coach · 0.2") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("مربی هوشمند کلش رویال", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "نسخه آزمایشی موتور تصمیم‌گیری: وضعیت را وارد کن تا یک پیشنهاد قابل توضیح دریافت کنی.",
                    style = MaterialTheme.typography.bodyMedium
                )

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("۱. اکسیر فعلی", style = MaterialTheme.typography.titleMedium)
                        Text("${elixir.toInt()} از ۱۰", style = MaterialTheme.typography.titleLarge)
                        Slider(
                            value = elixir,
                            onValueChange = { elixir = it },
                            valueRange = 0f..10f,
                            steps = 9
                        )
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("۲. چهار کارت فعلی دست", style = MaterialTheme.typography.titleMedium)
                        hand.forEachIndexed { index, cardName ->
                            CardPicker(
                                label = "کارت ${index + 1}",
                                selected = cardName,
                                onSelected = { newName ->
                                    if (newName !in hand || hand[index] == newName) {
                                        hand = hand.toMutableList().also { it[index] = newName }
                                    }
                                }
                            )
                        }
                        Text(
                            "اگر کارتی در دستت نیست، نزدیک‌ترین کارت واقعی را انتخاب نکن؛ ترکیب دست را مطابق بازی تنظیم کن.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("۳. تهدید حریف", style = MaterialTheme.typography.titleMedium)
                        ThreatPicker(selected = threat, onSelected = { threat = it })
                    }
                }

                Button(
                    onClick = { advice = ClashDecisionEngine.recommend(hand, elixir.toInt(), threat) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تحلیل وضعیت و پیشنهاد حرکت")
                }

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("نتیجه موتور تصمیم‌گیری", style = MaterialTheme.typography.titleMedium)
                        Text(advice.title, style = MaterialTheme.typography.titleLarge)
                        Text(advice.reason)
                        advice.card?.let {
                            Text("هزینه: ${it.elixir} اکسیر", style = MaterialTheme.typography.labelLarge)
                            Text(it.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("انتخاب تصویر مسابقه از گالری")
                }

                selectedUri?.let { uri ->
                    val bitmap = remember(uri) {
                        runCatching {
                            val source = ImageDecoder.createSource(context.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        }.getOrNull()
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "تصویر انتخاب‌شده از مسابقه",
                            modifier = Modifier.fillMaxWidth()
                        )
                    } ?: Text("خواندن تصویر ممکن نشد؛ یک تصویر دیگر انتخاب کن.")
                    Text(analysis, style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    "محدودیت نسخه 0.2: این موتور قانون‌محور و آزمایشی است؛ هنوز مدل یادگیری ماشین یا تشخیص خودکار کارت‌ها ندارد. برنامه هیچ لمس یا فرمانی به Clash Royale نمی‌فرستد.",
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
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ClashCardCatalog.cards.forEach { card ->
                DropdownMenuItem(
                    text = { Text("${card.name} · ${card.elixir} اکسیر") },
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
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected.title)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
