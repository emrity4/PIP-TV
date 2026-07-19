package com.emrity.piptv

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object PlaylistParser {
    data class ParseResult(
        val channels: List<Channel>,
        val error: String? = null
    )

    fun load(context: Context, assetPath: String): ParseResult {
        return try {
            val lines = mutableListOf<String>()
            context.assets.open(assetPath).use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    reader.lines().forEach { lines.add(it) }
                }
            }
            val channels = parseM3U(lines)
            ParseResult(channels)
        } catch (e: Exception) {
            ParseResult(emptyList(), e.message ?: "Unknown error")
        }
    }

    private fun parseM3U(lines: List<String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                var name = ""
                var logo = ""
                var group = "General"

                val nameMatch = Regex(",([^,]*)$").find(line)
                if (nameMatch != null) name = nameMatch.groupValues[1].trim()

                val logoMatch = Regex("""tvg-logo="([^"]*)"""").find(line)
                if (logoMatch != null) logo = logoMatch.groupValues[1]

                val groupMatch = Regex("""group-title="([^"]*)"""").find(line)
                if (groupMatch != null) group = groupMatch.groupValues[1]

                if (i + 1 < lines.size) {
                    i++
                    val url = lines[i].trim()
                    if (url.isNotEmpty() && !url.startsWith("#")) {
                        channels.add(Channel(name, logo, url, group))
                    }
                }
            }
            i++
        }
        return channels
    }
}
