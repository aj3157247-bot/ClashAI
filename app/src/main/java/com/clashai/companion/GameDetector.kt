package com.aiplayer.companion

object GameDetector {

    fun detect(
        packageName: String,
        screenWidth: Int,
        screenHeight: Int
    ): GameProfile {

        val name = packageName.lowercase()

        return when {
            "ludo" in name -> GameProfiles.profiles[0]
            "chess" in name -> GameProfiles.profiles[1]
            "puzzle" in name -> GameProfiles.profiles[2]

            else -> GameProfile(
                name = packageName,
                category = GameCategory.UNKNOWN,
                supported = false,
                description = "بازی هنوز شناسایی نشده است."
            )
        }
    }
}
