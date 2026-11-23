package com.clashai.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            UniversalGameAI()
        }
    }
}

@Composable
fun UniversalGameAI() {

    var selectedGame by remember {
        mutableStateOf("تشخیص خودکار")
    }

    var status by remember {
        mutableStateOf("آماده")
    }

    var automaticMode by remember {
        mutableStateOf(false)
    }

    MaterialTheme {
        Scaffold { padding ->

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Text(
                    "Universal Game AI",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text("هوش مصنوعی عمومی بازی‌های اندروید")

                HorizontalDivider()

                Text("بازی انتخاب‌شده: $selectedGame")

                Button(
                    onClick = {
                        selectedGame = "تشخیص در حال توسعه"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تشخیص بازی")
                }

                Text("وضعیت: $status")

                Button(
                    onClick = {
                        automaticMode = true
                        status = "حالت تحلیل فعال شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("شروع تحلیل")
                }

                OutlinedButton(
                    onClick = {
                        automaticMode = false
                        status = "متوقف شد"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("توقف")
                }

                Text(
                    if (automaticMode)
                        "هوش مصنوعی در حالت تحلیل است."
                    else
                        "هوش مصنوعی متوقف است."
                )

                Text(
                    "پشتیبانی از بازی‌های جدید به‌صورت مرحله‌ای اضافه می‌شود."
                )
            }
        }
    }
}
