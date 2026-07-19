package com.emrity.piptv

data class Channel(
    val name: String,
    val logo: String = "",
    val url: String,
    val group: String = "General"
)
