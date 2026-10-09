package io.github.crkarthik11.ottplayer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

class Programme(val start: Long, val stop: Long, val title: String)

/** Now/next lookup over an XMLTV guide, keeping only the playlist's channels. */
class Guide private constructor(private val byChannel: Map<String, List<Programme>>) {

    fun nowNext(channelId: String, now: Long): Pair<Programme?, Programme?> {
        val list = byChannel[channelId] ?: return null to null
        var lo = 0
        var hi = list.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= now) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null to list.firstOrNull()
        val cur = list[idx]
        return if (now < cur.stop) cur to list.getOrNull(idx + 1) else null to list.getOrNull(idx + 1)
    }

    /** Saves the trimmed guide in a compact form, so app starts skip the 30 MB XML. */
    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(byChannel.size)
            for ((id, list) in byChannel) {
                out.writeUTF(id)
                out.writeInt(list.size)
                for (p in list) {
                    out.writeLong(p.start)
                    out.writeLong(p.stop)
                    out.writeUTF(p.title.take(200))
                }
            }
        }
        tmp.renameTo(file)
    }

    companion object {
        private const val FORMAT = 1

        fun load(file: File): Guide? = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != FORMAT) return null
                val map = HashMap<String, List<Programme>>()
                repeat(input.readInt()) {
                    val id = input.readUTF()
                    map[id] = List(input.readInt()) { Programme(input.readLong(), input.readLong(), input.readUTF()) }
                }
                Guide(map)
            }
        }.getOrNull()

        /** True for a gzipped file, or one that starts like XML. */
        fun looksValid(file: File): Boolean = file.inputStream().use { input ->
            val head = ByteArray(64)
            val n = input.read(head)
            n >= 2 && ((head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) || String(head, 0, n).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("<"))
        }

        /** Streams an XMLTV file, gzipped or not; programmes outside [from, to) are dropped. */
        fun parse(input: InputStream, ids: Set<String>, from: Long, to: Long): Guide {
            val parser = Xml.newPullParser()
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(2)
            val gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            parser.setInput(if (gzipped) GZIPInputStream(buffered) else buffered, "UTF-8")
            val map = HashMap<String, ArrayList<Programme>>()
            var channel: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            channel = parser.getAttributeValue(null, "channel")?.takeIf { it in ids }
                            if (channel != null) {
                                start = parseTime(parser.getAttributeValue(null, "start"))
                                stop = parseTime(parser.getAttributeValue(null, "stop"))
                                title = null
                            }
                        }
                        "title" -> if (channel != null && title == null) title = parser.nextText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                    val ch = channel
                    if (ch != null && stop > from && start < to && stop > start) {
                        map.getOrPut(ch) { ArrayList() }.add(Programme(start, stop, title ?: ""))
                    }
                    channel = null
                }
                event = parser.next()
            }
            map.values.forEach { list -> list.sortBy { it.start } }
            return Guide(map)
        }

        /** XMLTV time, "yyyyMMddHHmmss +hhmm", to epoch milliseconds. */
        internal fun parseTime(s: String?): Long {
            if (s == null || s.length < 14) return 0
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val se = s.substring(12, 14).toInt()
            val tz = s.substring(14).trim()
            var offsetMin = 0
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val sign = if (tz[0] == '-') -1 else 1
                offsetMin = sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt())
            }
            val seconds = daysFromCivil(y, mo, d) * 86_400L + h * 3_600L + mi * 60L + se - offsetMin * 60L
            return seconds * 1_000L
        }

        // Howard Hinnant's days_from_civil: proleptic Gregorian date to days since 1970-01-01.
        private fun daysFromCivil(year: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097L + doe - 719_468L
        }
    }
}
