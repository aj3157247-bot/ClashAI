package com.aiplayer.companion

enum class GameCategory {
    BOARD,
    PUZZLE,
    STRATEGY,
    RACING,
    PLATFORM,
    ACTION,
    UNKNOWN
}

data class GameProfile(
    val name: String,
    val category: GameCategory,
    val supported: Boolean,
    val description: String
)

object GameProfiles {

    val profiles = listOf(
        GameProfile(
            "Ludo",
            GameCategory.BOARD,
            true,
            "بازی تخته‌ای و حرکت مهره‌ها"
        ),
        GameProfile(
            "Chess",
            GameCategory.STRATEGY,
            true,
            "بازی شطرنج و تحلیل حرکت‌ها"
        ),
        GameProfile(
            "Puzzle",
            GameCategory.PUZZLE,
            true,
            "بازی‌های فکری و معمایی"
        ),
        GameProfile(
            "Racing",
            GameCategory.RACING,
            false,
            "بازی‌های مسابقه‌ای؛ نیازمند موتور اختصاصی"
        )
    )
}
