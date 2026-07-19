package com.emrity.piptv

data class Channel(
    val name: String,
    val url: String,
    val group: String = "General"
) {
    val category: String
        get() = when {
            group.contains("News", ignoreCase = true) -> "News"
            group.contains("Religious", ignoreCase = true) || group.contains("Quran", ignoreCase = true) -> "Religious"
            group.contains("Sport", ignoreCase = true) -> "Sports"
            else -> "General"
        }
}
