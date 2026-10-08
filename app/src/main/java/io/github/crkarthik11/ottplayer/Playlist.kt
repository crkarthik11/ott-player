package io.github.crkarthik11.ottplayer

/** One channel from an M3U. [num] is its 1-based position in the full playlist, used for number entry. */
class Channel(
    val id: String,
    val name: String,
    val language: String,
    val genre: String,
    val mpd: String,
    val license: String?,
    val num: Int,
    val logo: String?,
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

class Playlist(val channels: List<Channel>) {
    val byId: Map<String, Channel> = channels.associateBy { it.id }
}

object M3u {
    private val ATTR = Regex("""([A-Za-z0-9-]+)="([^"]*)"""")

    private fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    fun parse(text: String): Playlist {
        val out = ArrayList<Channel>()
        var info: Map<String, String>? = null
        var name = ""
        var license: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    info = attrs(line)
                    name = line.substringAfterLast(',').trim()
                    license = null
                }
                // Kodi's form may append "|headers|..." after the URL.
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") ->
                    license = line.substringAfter('=').substringBefore('|').ifBlank { null }
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
                        ),
                    )
                    info = null
                }
            }
        }
        return Playlist(out)
    }
}

private fun initialsOf(name: String): String {
    val words = name.replace(Regex("\\bHD\\b"), "").replace(Regex("[^A-Za-z0-9 ]"), " ")
        .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    return (if (words.size > 1) "${words[0][0]}${words[1][0]}" else words[0].take(2)).uppercase()
}
