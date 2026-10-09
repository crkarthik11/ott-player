package io.github.crkarthik11.ottplayer

/**
 * One channel from an M3U. [num] is its 1-based position in the full playlist, used for
 * number entry. [mpd] is the stream URL, whatever its format; [dash] says it is DASH
 * (from a Kodi manifest_type line, a licence key, or a ".mpd" path).
 */
class Channel(
    val id: String,
    val name: String,
    val language: String,
    val genre: String,
    val mpd: String,
    val license: String?,
    val num: Int,
    val logo: String?,
    val dash: Boolean = false,
) {
    val initials: String = initialsOf(name)
}

/**
 * A category. [language] is its language ("Telugu"), or "For you" for Favourites
 * and the music mix; [label] is the short name shown next to it ("Movies").
 */
class Group(
    val name: String,
    val channels: List<Channel>,
    val custom: Boolean,
    val language: String = "For you",
    val label: String = name,
)

/** [guideUrl] is the guide named in the playlist's header (x-tvg-url or url-tvg), if any. */
class Playlist(val channels: List<Channel>, val guideUrl: String? = null) {
    val byId: Map<String, Channel> = channels.associateBy { it.id }
}

object M3u {
    private val ATTR = Regex("""([A-Za-z0-9-]+)="([^"]*)"""")

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    fun parse(text: String): Playlist {
        val out = ArrayList<Channel>()
        var guideUrl: String? = null
        var info: Map<String, String>? = null
        var name = ""
        var license: String? = null
        var manifest: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                // Some playlists list several guides, comma-separated; the first is used.
                line.startsWith("#EXTM3U") -> attrs(line).let { a ->
                    guideUrl = (a["x-tvg-url"] ?: a["url-tvg"])?.substringBefore(',')?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF") -> {
                    info = attrs(line)
                    name = line.substringAfterLast(',').trim()
                    license = null
                    manifest = null
                }
                // Kodi's form may append "|headers|..." after the URL.
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") ->
                    license = line.substringAfter('=').substringBefore('|').ifBlank { null }
                line.startsWith("#KODIPROP:inputstream.adaptive.manifest_type=") ->
                    manifest = line.substringAfter('=').trim().lowercase()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> info?.let { a ->
                    out.add(
                        Channel(
                            id = a["tvg-id"] ?: line,
                            name = name,
                            language = a["tvg-language"]?.ifBlank { null } ?: "Other",
                            genre = a["tvg-type"]?.ifBlank { null } ?: a["group-title"]?.ifBlank { null } ?: "Other",
                            mpd = line,
                            license = license,
                            num = out.size + 1,
                            logo = a["tvg-logo"]?.ifBlank { null },
                            dash = manifest == "mpd" || license != null || line.substringBefore('?').endsWith(".mpd"),
                        ),
                    )
                    info = null
                }
            }
        }
        return Playlist(out, guideUrl)
    }
}

private fun initialsOf(name: String): String {
    val words = name.replace(Regex("\\bHD\\b"), "").replace(Regex("[^A-Za-z0-9 ]"), " ")
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    return (if (words.size > 1) "${words[0][0]}${words[1][0]}" else words[0].take(2)).uppercase()
}
