package io.github.crkarthik11.ottplayer

import android.content.SharedPreferences

/**
 * Which languages and genres are shown, and the categories that follow from that:
 * Favourites, then the music mix, then one category per language and genre
 * ("Telugu Movies", "Hindi News", ...).
 */
class Catalog(private val prefs: SharedPreferences) {

    var languages: MutableSet<String> = prefs.getString("languages", null)
        ?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet()
        ?: DEFAULT_LANGUAGES.toMutableSet()
        private set

    var genresOff: MutableSet<String> = prefs.getString("genresOff", "")!!
        .split(',').filter { it.isNotEmpty() }.toMutableSet()
        private set

    fun save(languages: Set<String>, genresOff: Set<String>) {
        this.languages = languages.toMutableSet()
        this.genresOff = genresOff.toMutableSet()
        prefs.edit()
            .putString("languages", languages.joinToString(","))
            .putString("genresOff", genresOff.joinToString(","))
            .apply()
    }

    fun shows(ch: Channel) = ch.language in languages && ch.genre !in genresOff

    fun build(full: Playlist, favourites: Collection<String>): List<Group> {
        val out = ArrayList<Group>()
        val favs = favourites.mapNotNull { full.byId[it] }
        if (favs.isNotEmpty()) out.add(Group("Favourites", favs, custom = true, label = "★ Favourites"))
        val usable = usable(full)
        // Every music channel in MUSIC_MIX_LANGUAGES, whatever the language settings say.
        // Its name comes from the musicListName build property.
        val music = usable.filter { it.genre == "Music" && it.language in MUSIC_MIX_LANGUAGES }
            .sortedBy { MUSIC_MIX_LANGUAGES.indexOf(it.language) }
        if (music.isNotEmpty()) out.add(Group("Music mix", music, custom = true, label = "★ " + BuildConfig.MUSIC_LIST_NAME))
        val shown = usable.filter(::shows)
        // "All channels": everything shown, then one list per language.
        if (shown.isNotEmpty()) {
            out.add(Group("All channels", shown, custom = false, language = ALL, label = "Everything"))
            for (lang in languageOrder(full)) {
                val list = shown.filter { it.language == lang }
                if (list.isNotEmpty()) out.add(Group("All $lang", list, custom = false, language = ALL, label = lang))
            }
        }
        val byPair = shown.groupBy { it.language to it.genre }
        for (lang in languageOrder(full)) {
            if (lang !in languages) continue
            for (genre in genreOrder(full)) {
                byPair[lang to genre]?.let { out.add(Group("$lang $genre", it, custom = false, language = lang, label = genre)) }
            }
        }
        return out
    }

    companion object {
        val DEFAULT_LANGUAGES = listOf("Telugu", "Hindi", "Kannada", "English")
        const val ALL = "All channels"
        const val FOR_YOU = "For you"
        private val MUSIC_MIX_LANGUAGES = listOf("Telugu", "Hindi", "English")
        private val GENRE_ORDER = listOf(
            "Entertainment", "Movies", "Music", "News", "Kids", "Sports", "Infotainment",
            "Lifestyle", "Devotional", "Business", "Educational", "Shopping",
        )
        private val TEST = Regex("^test", RegexOption.IGNORE_CASE)
        private val QUALITY = Regex("\\b(hd\\+?|sd)\\b", RegexOption.IGNORE_CASE)
        private val HD = Regex("\\bhd\\+?(\\s|$)", RegexOption.IGNORE_CASE)

        /**
         * The channels worth listing: Jio's test feeds ("TEST9 HD", "testa") are
         * dropped, and where a channel exists in HD and SD in the same language
         * only the HD one is kept ("Sony Max HD", not "Sony Max SD").
         */
        fun usable(pl: Playlist): List<Channel> {
            val real = pl.channels.filter { !TEST.containsMatchIn(it.name) }
            val key = { ch: Channel -> ch.language + "|" + ch.name.replace(QUALITY, "").lowercase().filter(Char::isLetterOrDigit) }
            val hasHd = real.filter { HD.containsMatchIn(it.name) }.map(key).toSet()
            return real.filter { HD.containsMatchIn(it.name) || key(it) !in hasHd }
        }

        /** The default languages first, then the rest by channel count. */
        fun languageOrder(pl: Playlist): List<String> {
            val counts = pl.channels.groupingBy { it.language }.eachCount()
            val rest = counts.keys.filter { it !in DEFAULT_LANGUAGES }.sortedByDescending { counts[it] }
            return DEFAULT_LANGUAGES.filter { it in counts } + rest
        }

        fun genreOrder(pl: Playlist): List<String> {
            val present = pl.channels.map { it.genre }.toSet()
            return GENRE_ORDER.filter { it in present } + (present - GENRE_ORDER.toSet()).sorted()
        }
    }
}
