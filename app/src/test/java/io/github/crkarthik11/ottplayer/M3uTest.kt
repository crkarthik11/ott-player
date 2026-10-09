package io.github.crkarthik11.ottplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {

    @Test
    fun readsJioTvGoEntries() {
        val pl = M3u.parse(
            """
            #EXTM3U x-tvg-url="https://tv.lan/epg.xml.gz"
            #EXTINF:-1 tvg-id="144" tvg-name="Star Maa HD" tvg-logo="https://tv.lan/jtvimage/a.png" tvg-language="Telugu" tvg-type="Entertainment" group-title="Entertainment", Star Maa HD
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://tv.lan/live/key/144
            https://tv.lan/live/mpd/144
            #EXTINF:-1 tvg-id="5" tvg-name="DD News" tvg-logo="" tvg-language="Hindi" tvg-type="News" group-title="News", DD News
            https://tv.lan/live/5.m3u8
            """.trimIndent(),
        )
        assertEquals("https://tv.lan/epg.xml.gz", pl.guideUrl)
        assertEquals(2, pl.channels.size)
        val maa = pl.channels[0]
        assertEquals("144", maa.id)
        assertEquals("Star Maa HD", maa.name)
        assertEquals("Telugu", maa.language)
        assertEquals("https://tv.lan/live/key/144", maa.license)
        assertTrue(maa.dash)
        val dd = pl.channels[1]
        assertFalse(dd.dash)
        assertNull(dd.license)
        assertNull(dd.logo)
        assertEquals(2, dd.num)
    }

    @Test
    fun readsGenericPlaylist() {
        val pl = M3u.parse(
            """
            #EXTM3U url-tvg="http://epg.example/guide.xml,http://epg.example/other.xml"
            #EXTINF:-1 tvg-id="bbc1" group-title="UK",BBC One
            http://iptv.example/live/u/p/101.ts
            #EXTINF:-1 group-title="Films",Movie Channel
            http://iptv.example/stream/manifest.mpd?token=1
            """.trimIndent(),
        )
        assertEquals("http://epg.example/guide.xml", pl.guideUrl)
        val bbc = pl.channels[0]
        assertEquals("Other", bbc.language)
        assertEquals("UK", bbc.genre)
        assertFalse(bbc.dash)
        assertEquals("http://iptv.example/stream/manifest.mpd?token=1", pl.channels[1].id)
        assertTrue(pl.channels[1].dash)
    }

    @Test
    fun noHeaderMeansNoGuide() {
        assertNull(M3u.parse("#EXTINF:-1,A\nhttp://a/1.m3u8").guideUrl)
    }
}
