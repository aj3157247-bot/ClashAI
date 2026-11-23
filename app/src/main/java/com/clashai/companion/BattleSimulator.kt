package com.clashai.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private data class SimCard(
    val name: String,
    val cost: Int,
    val attack: Int,
    val defense: Int,
    val air: Boolean = false,
    val splash: Boolean = false
)

private data class SimOutcome(
    val won: Boolean,
    val playerTower: Int,
    val rivalTower: Int,
    val rounds: Int
)

/**
 * Independent toy battle simulator.
 * It does not read the Clash Royale screen, send touches, or connect to Supercell.
 * The adaptive weights are an educational prototype, not a trained ML model.
 */
private object ClashBattleSimulator {
    private val deck = listOf(
        SimCard("Knight", 3, 5, 7),
        SimCard("Archers", 3, 5, 4, air = true),
        SimCard("Arrows", 3, 3, 5, splash = true),
        SimCard("Fireball", 4, 7, 3, splash = true),
        SimCard("Mini P.E.K.K.A", 4, 9, 6),
        SimCard("Musketeer", 4, 7, 5, air = true),
        SimCard("Valkyrie", 4, 5, 8, splash = true),
        SimCard("Tesla", 4, 4, 9, air = true),
        SimCard("Baby Dragon", 4, 6, 6, air = true, splash = true),
        SimCard("Giant", 5, 9, 4),
        SimCard("Zap", 2, 3, 3, splash = true),
        SimCard("Skeleton Army", 3, 7, 5)
    )

    // Tiny adaptive policy preferences, updated from batch outcomes in memory.
    private var aggressionWeight = 1.0
    private var defenseWeight = 1.0

    fun runBatch(count: Int): List<SimOutcome> {
        val random = Random(System.nanoTime())
        return List(count) { simulate(random) }.also { results ->
            val winRate = results.count { it.won }.toFloat() / results.size
            if (winRate < 0.5f) {
                defenseWeight = (defenseWeight + 0.04).coerceAtMost(1.8)
                aggressionWeight = (aggressionWeight - 0.02).coerceAtLeast(0.6)
            } else {
                aggressionWeight = (aggressionWeight + 0.03).coerceAtMost(1.8)
                defenseWeight = (defenseWeight - 0.01).coerceAtLeast(0.6)
            }
        }
    }

    fun policySummary(): String =
        "وزن حمله: ${"%.2f".format(aggressionWeight)} · وزن دفاع: ${"%.2f".format(defenseWeight)}"

    private fun simulate(random: Random): SimOutcome {
        var playerTower = 100
        var rivalTower = 100
        var playerElixir = 5
        var rivalElixir = 5
        var playerDamage = 0
        var rivalDamage = 0
        var rounds = 0

        repeat(60) { turn ->
            rounds = turn + 1
            playerElixir = (playerElixir + 1).coerceAtMost(10)
            rivalElixir = (rivalElixir + 1).coerceAtMost(10)

            val threatIsAir = random.nextInt(100) < 35
            val playerCard = chooseCard(random, playerElixir, threatIsAir, true)
            val rivalCard = chooseCard(random, rivalElixir, !threatIsAir, false)

            if (playerCard != null) {
                playerElixir -= playerCard.cost
                val counter = if (threatIsAir && playerCard.air) 2 else 0
                val pressure = (playerCard.attack * aggressionWeight).toInt()
                val defense = (playerCard.defense * defenseWeight).toInt()
                playerDamage += (pressure + counter - random.nextInt(0, 4)).coerceAtLeast(0)
                rivalDamage += (10 - defense / 2 - random.nextInt(0, 4)).coerceAtLeast(0)
            }
            if (rivalCard != null) {
                rivalElixir -= rivalCard.cost
                rivalDamage += (rivalCard.attack - random.nextInt(0, 4)).coerceAtLeast(0)
                playerDamage += (10 - rivalCard.defense / 2 - random.nextInt(0, 4)).coerceAtLeast(0)
            }

            if (playerDamage >= 100 || rivalDamage >= 100) return@repeat
        }

        rivalTower = (100 - playerDamage).coerceAtLeast(0)
        playerTower = (100 - rivalDamage).coerceAtLeast(0)
        val won = playerTower > rivalTower || (playerTower == rivalTower && random.nextBoolean())
        return SimOutcome(won, playerTower, rivalTower, rounds)
    }

    private fun chooseCard(
        random: Random,
        elixir: Int,
        enemyAir: Boolean,
        player: Boolean
    ): SimCard? {
        val affordable = deck.filter { it.cost <= elixir }
        if (affordable.isEmpty()) return null
        return affordable.maxByOrNull { card ->
            val airBonus = if (enemyAir && card.air) 5 else 0
            val splashBonus = if (random.nextInt(100) < 25 && card.splash) 2 else 0
            val roleBias = if (player) card.attack * aggressionWeight + card.defense * defenseWeight
                           else card.attack + card.defense
            roleBias + airBonus + splashBonus - card.cost * 0.7
        }
    }
}

@Composable
fun BattleLabPanel() {
    var games by remember { mutableStateOf(0) }
    var wins by remember { mutableStateOf(0) }
    var losses by remember { mutableStateOf(0) }
    var averagePlayerTower by remember { mutableStateOf(0) }
    var averageRivalTower by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("هنوز مسابقه‌ای اجرا نشده است.") }
    var policy by remember { mutableStateOf(ClashBattleSimulator.policySummary()) }

    val winRate = if (games == 0) 0f else wins.toFloat() / games

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("آزمایشگاه نبرد خودکار · مرحله ۳", style = MaterialTheme.typography.titleLarge)
            Text(
                "این شبیه‌ساز مستقل است؛ مسابقه‌های ساختگی را بدون اتصال به بازی اصلی اجرا می‌کند.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val result = ClashBattleSimulator.runBatch(10)
                    games += result.size
                    wins += result.count { it.won }
                    losses += result.count { !it.won }
                    averagePlayerTower = result.map { it.playerTower }.average().toInt()
                    averageRivalTower = result.map { it.rivalTower }.average().toInt()
                    status = "۱۰ مسابقه آزمایشی اجرا شد؛ ${result.count { it.won }} برد و ${result.count { !it.won }} باخت."
                    policy = ClashBattleSimulator.policySummary()
                }) { Text("اجرای ۱۰ مسابقه") }

                OutlinedButton(onClick = {
                    val result = ClashBattleSimulator.runBatch(50)
                    games += result.size
                    wins += result.count { it.won }
                    losses += result.count { !it.won }
                    averagePlayerTower = result.map { it.playerTower }.average().toInt()
                    averageRivalTower = result.map { it.rivalTower }.average().toInt()
                    status = "۵۰ مسابقه آزمایشی اجرا شد؛ ${result.count { it.won }} برد و ${result.count { !it.won }} باخت."
                    policy = ClashBattleSimulator.policySummary()
                }) { Text("اجرای ۵۰ مسابقه") }
            }

            Text("تعداد کل: $games مسابقه")
            Text("برد: $wins   ·   باخت: $losses")
            Text("درصد برد: ${(winRate * 100).toInt()}٪")
            LinearProgressIndicator(progress = { winRate }, modifier = Modifier.fillMaxWidth())
            Text("میانگین جان برج شما در آخرین دسته: $averagePlayerTower٪")
            Text("میانگین جان برج حریف در آخرین دسته: $averageRivalTower٪")
            Text(policy, style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text(
                "توجه: این محیط ساده و آموزشی است؛ نتیجه آن پیش‌بینی واقعی مسابقات Clash Royale نیست و هنوز یادگیری ماشین واقعی انجام نمی‌دهد.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
