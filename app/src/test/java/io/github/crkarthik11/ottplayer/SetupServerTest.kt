package io.github.crkarthik11.ottplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupServerTest {

    @Test
    fun parsesFormBody() {
        val f = SetupServer.parseForm("playlist=https%3A%2F%2Fa.lan%2Fp.m3u%3Fx%3D1%26y%3D2&guide=")
        assertEquals("https://a.lan/p.m3u?x=1&y=2", f["playlist"])
        assertEquals("", f["guide"])
    }

    @Test
    fun validatesUrls() {
        assertNull(SetupServer.validate(Source("http://192.168.1.5:5001/playlist.m3u", "")))
        assertNull(SetupServer.validate(Source("https://a.lan/p.m3u", "https://a.lan/epg.xml.gz")))
        assertNotNull(SetupServer.validate(Source("", "")))
        assertNotNull(SetupServer.validate(Source("ftp://a.lan/p.m3u", "")))
        assertNotNull(SetupServer.validate(Source("https://a.lan/p.m3u", "not a url")))
    }

    @Test
    fun escapesHtml() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;", SetupServer.esc("<a href=\"x\">&"))
    }
}

class SetupServerHttpTest {

    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val conn = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream).use { it.readBytes().decodeToString() }
        return code to text
    }

    @Test
    fun servesFormAndSaves() {
        val saved = java.util.concurrent.LinkedBlockingQueue<Source>()
        val server = SetupServer({ Source("https://old.lan/p.m3u", "") }, { saved.add(it) }, { it() })
        server.start(java.net.InetAddress.getLoopbackAddress())
        val address = server.address
        try {
            val (code, form) = request(address!!)
            assertEquals(200, code)
            assertTrue("value=\"https://old.lan/p.m3u\"" in form)

            val base = address.substringBeforeLast('/')
            assertEquals(404, request("$base/wrong").first)

            val (_, bad) = request(address, "playlist=ftp%3A%2F%2Fx&guide=")
            assertTrue("must start with http" in bad)
            assertNull(saved.poll())

            val (_, ok) = request(address, "playlist=http%3A%2F%2F192.168.1.5%3A5001%2Fplaylist.m3u&guide=")
            assertTrue("Saved" in ok)
            assertEquals(Source("http://192.168.1.5:5001/playlist.m3u", ""), saved.poll(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }
}
