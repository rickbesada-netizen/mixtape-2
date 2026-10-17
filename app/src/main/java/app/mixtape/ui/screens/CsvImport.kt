package app.mixtape.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.mixtape.core.model.SourceType
import app.mixtape.core.model.Track
import app.mixtape.download.DownloadController
import java.io.IOException

/**
 * Reads playlist exports (Exportify, TuneMyMusic, Soundiiz...). Rows become Spotify-type tracks so
 * they go through the same YouTube matcher; a real Spotify id is used when the file has one.
 */
object CsvImport {
    data class Parsed(val name: String, val tracks: List<Track>)

    fun read(context: Context, uri: Uri): Parsed {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open file")
        val rows = parse(text.removePrefix("﻿"))
        if (rows.size < 2) throw IOException("The file has no rows")
        val header = rows.first().map { it.trim().lowercase() }

        fun col(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOfFirst { it == n }.takeIf { it >= 0 } }
                ?: names.firstNotNullOfOrNull { n -> header.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
                ?: -1

        val titleCol = col("track name", "title", "name", "song")
        val artistCol = col("artist name(s)", "artist name", "artist", "artists")
        val albumCol = col("album name", "album")
        val durationCol = col("duration (ms)", "duration_ms", "duration")
        val uriCol = col("track uri", "spotify uri", "uri")
        val isrcCol = col("isrc")
        val imageCol = col("album image url", "image url", "artwork")
        if (titleCol < 0) throw IOException("Couldn't find a track name column")

        val tracks = rows.drop(1).mapNotNull { r ->
            fun cell(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val title = cell(titleCol).ifEmpty { return@mapNotNull null }
            val artist = cell(artistCol).replace(";", ", ")
            val spotifyId = Regex("track[:/]([A-Za-z0-9]{22})").find(cell(uriCol))?.groupValues?.get(1)
            val id = spotifyId ?: ("csv-" + DownloadController.sha1("$artist|$title").take(16))
            val durRaw = cell(durationCol)
            val duration = durRaw.toLongOrNull()?.let { if (it > 10_000) it / 1000 else it }
                ?: parseClock(durRaw)
            Track(
                source = SourceType.SPOTIFY,
                sourceId = id,
                title = title,
                artist = artist,
                album = cell(albumCol).ifEmpty { null },
                durationSec = duration,
                artworkUrl = cell(imageCol).ifEmpty { null },
                isrc = cell(isrcCol).ifEmpty { null },
            )
        }
        return Parsed(displayName(context, uri), tracks)
    }

    private fun parseClock(s: String): Long {
        val parts = s.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return name?.substringBeforeLast('.')?.replace('_', ' ')?.ifBlank { null } ?: "Imported playlist"
    }

    /** RFC 4180-ish parser: quoted fields, escaped quotes, commas and newlines inside quotes. */
    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else inQuotes = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> {
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }
}
