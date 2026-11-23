package com.clashai.companion

enum class ThreatType(val title: String) {
    NONE("تهدید مشخصی نیست"),
    AIR("نیروهای هوایی"),
    SWARM("تعداد زیاد نیروهای ضعیف"),
    TANK("نیروی سنگین و پُر جان"),
    BUILDING_TARGETER("نیروی حمله‌کننده به برج")
}

enum class CardRole {
    ANTI_AIR,
    SPLASH,
    SPELL,
    TANK_KILLER,
    BUILDING,
    WIN_CONDITION,
    SUPPORT,
    SWARM
}

data class ClashCard(
    val name: String,
    val elixir: Int,
    val roles: Set<CardRole>,
    val explanation: String
)

data class CoachAdvice(
    val card: ClashCard?,
    val title: String,
    val reason: String
)

/**
 * A transparent, rule-based starter engine.
 * It does not control Clash Royale or interact with the game client.
 */
object ClashCardCatalog {
    val cards = listOf(
        ClashCard("Knight", 3, setOf(CardRole.SUPPORT, CardRole.TANK_KILLER), "مدافع زمینی متعادل"),
        ClashCard("Archers", 3, setOf(CardRole.ANTI_AIR, CardRole.SUPPORT), "پشتیبانی زمینی و هوایی"),
        ClashCard("Arrows", 3, setOf(CardRole.SPELL, CardRole.SPLASH), "مقابله با گروهی از نیروهای ضعیف"),
        ClashCard("Fireball", 4, setOf(CardRole.SPELL, CardRole.SPLASH), "آسیب ناحیه‌ای به نیروهای متوسط و متراکم"),
        ClashCard("Mini P.E.K.K.A", 4, setOf(CardRole.TANK_KILLER), "آسیب زیاد به هدف‌های تک‌نفره و سنگین"),
        ClashCard("Musketeer", 4, setOf(CardRole.ANTI_AIR, CardRole.SUPPORT), "دفاع دوربرد در برابر هوا و زمین"),
        ClashCard("Valkyrie", 4, setOf(CardRole.SPLASH, CardRole.SUPPORT), "دفاع ناحیه‌ای در برابر نیروهای زمینی"),
        ClashCard("Tesla", 4, setOf(CardRole.BUILDING, CardRole.ANTI_AIR), "ساختمان دفاعی برای منحرف‌کردن و هدف‌گرفتن نیروها"),
        ClashCard("Baby Dragon", 4, setOf(CardRole.ANTI_AIR, CardRole.SPLASH), "پشتیبانی هوایی با آسیب ناحیه‌ای"),
        ClashCard("Giant", 5, setOf(CardRole.WIN_CONDITION), "شرط برد برای فشار به برج"),
        ClashCard("Zap", 2, setOf(CardRole.SPELL, CardRole.SPLASH), "طلسم ارزان برای واکنش سریع"),
        ClashCard("Skeleton Army", 3, setOf(CardRole.SWARM, CardRole.TANK_KILLER), "دفاع گروهی؛ آسیب‌پذیر در برابر طلسم‌ها")
    )

    fun find(name: String): ClashCard? = cards.firstOrNull { it.name == name }
}

object ClashDecisionEngine {
    fun recommend(handNames: List<String>, availableElixir: Int, threat: ThreatType): CoachAdvice {
        val hand = handNames.distinct().mapNotNull(ClashCardCatalog::find)
        if (hand.isEmpty()) {
            return CoachAdvice(
                null,
                "کارت در دسترس مشخص نیست",
                "چهار کارت فعلی دستت را انتخاب کن تا پیشنهاد دقیق‌تری دریافت کنی."
            )
        }

        val affordable = hand.filter { it.elixir <= availableElixir }
        if (affordable.isEmpty()) {
            val cheapest = hand.minByOrNull { it.elixir }
            return CoachAdvice(
                cheapest,
                "فعلاً اکسیر را ذخیره کن",
                "هیچ‌یک از کارت‌های انتخاب‌شده با $availableElixir اکسیر قابل استفاده نیست. تا جمع‌شدن اکسیر صبر کن و مراقب فشار حریف باش."
            )
        }

        fun score(card: ClashCard): Int {
            var value = 0
            when (threat) {
                ThreatType.NONE -> {
                    if (CardRole.WIN_CONDITION in card.roles) value += 5
                    if (CardRole.SUPPORT in card.roles) value += 3
                    if (CardRole.BUILDING in card.roles) value += 1
                }
                ThreatType.AIR -> {
                    if (CardRole.ANTI_AIR in card.roles) value += 8
                    if (CardRole.SPLASH in card.roles) value += 2
                }
                ThreatType.SWARM -> {
                    if (CardRole.SPLASH in card.roles) value += 7
                    if (CardRole.SPELL in card.roles) value += 6
                }
                ThreatType.TANK -> {
                    if (CardRole.TANK_KILLER in card.roles) value += 8
                    if (CardRole.BUILDING in card.roles) value += 3
                }
                ThreatType.BUILDING_TARGETER -> {
                    if (CardRole.BUILDING in card.roles) value += 7
                    if (CardRole.TANK_KILLER in card.roles) value += 2
                }
            }
            // Prefer an efficient response, but don't reward an unaffordable card.
            value += (5 - card.elixir).coerceAtLeast(0)
            if (card.elixir == availableElixir && availableElixir >= 7) value += 1
            return value
        }

        val chosen = affordable.maxWithOrNull(
            compareBy<ClashCard> { score(it) }.thenBy { -it.elixir }
        ) ?: return CoachAdvice(null, "پیشنهادی موجود نیست", "دوباره وضعیت را انتخاب کن.")

        val reason = when (threat) {
            ThreatType.NONE -> "تهدید مشخصی ثبت نشده؛ این کارت با توجه به نقش پشتیبانی و فشار متعادل انتخاب شده است."
            ThreatType.AIR -> "برای مقابله با نیروهای هوایی، کارت دارای قابلیت ضد هوایی در اولویت قرار گرفت."
            ThreatType.SWARM -> "برای کنترل چند نیروی هم‌زمان، آسیب ناحیه‌ای یا طلسم انتخاب بهتری است."
            ThreatType.TANK -> "برای متوقف‌کردن نیروی سنگین، کارت با توان آسیب زیاد به هدف تکی در اولویت است."
            ThreatType.BUILDING_TARGETER -> "برای منحرف‌کردن نیرویی که برج را هدف می‌گیرد، ساختمان دفاعی در اولویت قرار گرفت."
        } + " هزینه این کارت ${chosen.elixir} اکسیر است. این پیشنهاد آزمایشی است و جای تحلیل کامل میدان را نمی‌گیرد."

        return CoachAdvice(chosen, "پیشنهاد: ${chosen.name}", reason)
    }
}
