package com.clashai.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.random.Random

data class SimCard(
    val name: String,
    val cost: Int,
    val attack: Float,
    val defense: Float
)

data class SimResult(
    val winner: Int,
    val playerTower: Float,
    val rivalTower: Float,
    val turns: Int,
    val playerDamage: Float,
    val rivalDamage: Float
)

data class TournamentResult(
    val games: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val winRate: Float,
    val averageTower: Float,
    val last: SimResult
)

object ClashSimulationEngine {
    private val cards = listOf(
        SimCard("Knight", 3, 5.0f, 5.0f),
        SimCard("Archers", 3, 5.5f, 3.0f),
        SimCard("Arrows", 3, 3.5f, 4.5f),
        SimCard("Fireball", 4, 7.0f, 3.0f),
        SimCard("Mini P.E.K.K.A", 4, 8.0f, 4.0f),
        SimCard("Musketeer", 4, 6.0f, 5.0f),
        SimCard("Valkyrie", 4, 5.0f, 7.0f),
        SimCard("Tesla", 4, 2.5f, 8.0f),
        SimCard("Baby Dragon", 4, 6.0f, 5.5f),
        SimCard("Giant", 5, 9.0f, 2.0f),
        SimCard("Zap", 2, 2.5f, 3.5f),
        SimCard("Skeleton Army", 3, 4.0f, 7.0f)
    )

    fun tournament(games: Int, aggression: Float): TournamentResult {
        var wins = 0
        var losses = 0
        var draws = 0
        var towerTotal = 0f
        var last = simulateOne(aggression, Random.nextInt())
        repeat(games) {
            last = simulateOne(aggression, Random.nextInt())
            when (last.winner) {
                1 -> wins++
                -1 -> losses++
                else -> draws++
            }
            towerTotal += last.playerTower
        }
        val rate = if (games == 0) 0f else wins.toFloat() / games
        return TournamentResult(
            games, wins, losses, draws, rate,
            if (games == 0) 0f else towerTotal / games,
            last
        )
    }

    private fun simulateOne(aggression: Float, seed: Int): SimResult {
        val random = Random(seed)
        var playerElixir = 5f
        var rivalElixir = 5f
        var playerTower = 1000f
        var rivalTower = 1000f
        var playerPressure = 0f
        var rivalPressure = 0f
        var playerDamage = 0f
        var rivalDamage = 0f
        var turns = 0

        while (turns < 90 && playerTower > 0f && rivalTower > 0f) {
            turns++
            playerElixir = (playerElixir + 1f).coerceAtMost(10f)
            rivalElixir = (rivalElixir + 1f).coerceAtMost(10f)

            val playerCard = chooseCard(playerElixir, aggression, rivalPressure, random)
            val rivalCard = chooseCard(rivalElixir, 0.58f, playerPressure, random)

            if (playerCard != null) {
                playerElixir -= playerCard.cost
                playerPressure += playerCard.attack * 0.9f
                rivalPressure = (rivalPressure - playerCard.defense * 0.10f).coerceAtLeast(0f)
            }
            if (rivalCard != null) {
                rivalElixir -= rivalCard.cost
                rivalPressure += rivalCard.attack * 0.9f
                playerPressure = (playerPressure - rivalCard.defense * 0.10f).coerceAtLeast(0f)
            }

            val incomingToPlayer = (rivalPressure * 0.22f).coerceAtMost(22f)
            val incomingToRival = (playerPressure * 0.22f).coerceAtMost(22f)
            playerTower = (playerTower - incomingToPlayer).coerceAtLeast(0f)
            rivalTower = (rivalTower - incomingToRival).coerceAtLeast(0f)
            playerDamage += incomingToRival
            rivalDamage += incomingToPlayer

            playerPressure *= 0.91f
            rivalPressure *= 0.91f
        }

        val winner = when {
            playerTower > rivalTower + 1f -> 1
            rivalTower > playerTower + 1f -> -1
            else -> 0
        }
        return SimResult(winner, playerTower, rivalTower, turns, playerDamage, rivalDamage)
    }

    private fun chooseCard(
        elixir: Float,
        aggression: Float,
        incomingPressure: Float,
        random: Random
    ): SimCard? {
        val affordable = cards.filter { it.cost <= elixir.toInt() }
        if (affordable.isEmpty()) return null

        return affordable.maxByOrNull { card ->
            val attackWeight = 0.45f + aggression
            val defenseWeight = 1.25f - aggression
            val pressureBonus = if (incomingPressure > 18f) card.defense * 0.45f else 0f
            val efficiency = (card.attack + card.defense * 0.65f) / card.cost
            card.attack * attackWeight +
                card.defense * defenseWeight +
                pressureBonus +
                efficiency +
                random.nextFloat() * 0.8f
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashSimulatorApp() {
    var aggression by remember { mutableStateOf(0.58f) }
    var result by remember { mutableStateOf<TournamentResult?>(null) }
    var runningLabel by remember { mutableStateOf("هنوز مسابقه‌ای اجرا نشده است.") }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ClashAI · شبیه‌ساز مستقل") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("آزمایشگاه نبرد هوش مصنوعی", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "دو ربات در یک محیط آزمایشی مستقل با هم مسابقه می‌دهند. هیچ اتصالی به بازی اصلی یا حساب Supercell وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("سبک بازی ربات شما", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (aggression < 0.35f) "دفاعی" else if (aggression > 0.72f) "تهاجمی" else "متعادل",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Slider(
                            value = aggression,
                            onValueChange = { aggression = it },
                            valueRange = 0.1f..0.9f
                        )
                        Text("کمتر = دفاع بیشتر · بیشتر = حمله بیشتر", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("اجرای مسابقات آزمایشی", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            result = ClashSimulationEngine.tournament(10, aggression)
                            runningLabel = "۱۰ مسابقه تکمیل شد."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("۱۰ بازی") }
                    OutlinedButton(
                        onClick = {
                            result = ClashSimulationEngine.tournament(50, aggression)
                            runningLabel = "۵۰ مسابقه تکمیل شد."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("۵۰ بازی") }
                    Button(
                        onClick = {
                            result = ClashSimulationEngine.tournament(100, aggression)
                            runningLabel = "۱۰۰ مسابقه تکمیل شد."
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("۱۰۰ بازی") }
                }

                Text(runningLabel, style = MaterialTheme.typography.bodySmall)

                result?.let { stats ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("آمار عملکرد ربات", style = MaterialTheme.typography.titleLarge)
                            Text("درصد برد: ${(stats.winRate * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
                            LinearProgressIndicator(
                                progress = { stats.winRate },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("برد: ${stats.wins}   باخت: ${stats.losses}   مساوی: ${stats.draws}")
                            Text("تعداد مسابقه: ${stats.games}")
                            Text("میانگین جان برج باقی‌مانده شما: ${stats.averageTower.toInt()} از ۱۰۰۰")
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("آخرین مسابقه", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (stats.last.winner) {
                                    1 -> "ربات شما برنده شد"
                                    -1 -> "ربات حریف برنده شد"
                                    else -> "مسابقه مساوی شد"
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text("جان برج شما: ${stats.last.playerTower.toInt()}")
                            Text("جان برج حریف: ${stats.last.rivalTower.toInt()}")
                            Text("تعداد نوبت‌ها: ${stats.last.turns}")
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "این نسخه یک شبیه‌ساز ساده با تصمیم‌گیری قانون‌محور است؛ هنوز نسخه کامل Clash Royale، یادگیری ماشین یا آموزش واقعی مدل نیست. نتایج فقط مربوط به همین محیط آزمایشی‌اند.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
