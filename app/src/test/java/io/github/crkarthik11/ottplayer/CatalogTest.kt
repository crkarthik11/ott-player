package io.github.crkarthik11.ottplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTest {
    private fun ch(id: String, name: String, lang: String = "Telugu", genre: String = "News") =
        Channel(id, name, lang, genre, "http://x/$id.m3u8", null, 0, null)

    @Test
    fun dropsTestFeedsAndSdCopies() {
        val pl = Playlist(listOf(ch("1", "Sony Max HD"), ch("2", "Sony Max SD"), ch("3", "TEST9 HD"), ch("4", "Sony Max", "Hindi")))
        assertEquals(listOf("1", "4"), Catalog.usable(pl).map { it.id })
    }

    @Test
    fun ordersDefaultLanguagesFirst() {
        val pl = Playlist(listOf(ch("1", "A", "Tamil"), ch("2", "B", "Tamil"), ch("3", "C", "Hindi")))
        assertEquals(listOf("Hindi", "Tamil"), Catalog.languageOrder(pl))
    }
}
