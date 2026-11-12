package dev.slurpdrome.data.source

import kotlinx.serialization.Serializable
import java.io.InputStream

/** One track of a CUE-split image. Times are in milliseconds from the start of the file. */
@Serializable
data class CueTrack(val number: Int, val title: String, val performer: String?, val startMs: Long, val endMs: Long)

@Serializable
data class CueAlbum(val title: String?, val performer: String?, val year: Int, val genre: String?, val tracks: List<CueTrack>)

/**
 * Reads the cue sheet embedded in a FLAC image: either the CUESHEET text tag (titles, performers)
 * or the native CUESHEET metadata block (offsets only). Reads metadata blocks only — the stream
 * is abandoned before any audio frame.
 */
object CueSheet {
    fun fromFlac(input: InputStream, totalMs: Long): CueAlbum? {
        val head = ByteArray(4)
        if (input.readFully(head) < 4 || String(head, Charsets.ISO_8859_1) != "fLaC") return null
        var sampleRate = 44100
        var textCue: String? = null
        var tags = mutableMapOf<String, String>()
        var nativeOffsets: List<Pair<Int, Long>>? = null    // (track number, sample offset)
        while (true) {
            val h = ByteArray(4)
            if (input.readFully(h) < 4) break
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            when (type) {
                0 -> { // STREAMINFO
                    val b = ByteArray(len); input.readFully(b)
                    sampleRate = ((b[10].toInt() and 0xFF) shl 12) or ((b[11].toInt() and 0xFF) shl 4) or ((b[12].toInt() and 0xF0) shr 4)
                }
                4 -> { // VORBIS_COMMENT (little endian)
                    val b = ByteArray(len); input.readFully(b)
                    var p = 0
                    fun le32(): Int { val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24); p += 4; return v }
                    val vendor = le32(); p += vendor
                    val n = le32()
                    repeat(n) {
                        if (p + 4 > b.size) return@repeat
                        val l = le32()
                        if (l < 0 || p + l > b.size) return@repeat
                        val kv = String(b, p, l, Charsets.UTF_8); p += l
                        val i = kv.indexOf('=')
                        if (i > 0) {
                            val k = kv.substring(0, i).uppercase(); val v = kv.substring(i + 1)
                            if (k == "CUESHEET") textCue = v else tags.putIfAbsent(k, v)
                        }
                    }
                }
                5 -> { // CUESHEET block (offsets in samples)
                    val b = ByteArray(len); input.readFully(b)
                    var p = 128 + 8 + 1 + 258
                    val count = b[p].toInt() and 0xFF; p += 1
                    val list = mutableListOf<Pair<Int, Long>>()
                    repeat(count) {
                        var off = 0L
                        for (k in 0 until 8) off = (off shl 8) or (b[p + k].toLong() and 0xFF)
                        p += 8
                        val num = b[p].toInt() and 0xFF; p += 1
                        p += 12 + 1 + 13
                        val nIdx = b[p].toInt() and 0xFF; p += 1
                        var idx1 = 0L
                        repeat(nIdx) {
                            var io = 0L
                            for (k in 0 until 8) io = (io shl 8) or (b[p + k].toLong() and 0xFF)
                            val inum = b[p + 8].toInt() and 0xFF
                            if (inum == 1) idx1 = io
                            p += 12
                        }
                        if (num in 1..99) list += num to (off + idx1)    // 170/255 = lead-out
                    }
                    nativeOffsets = list
                }
                else -> input.skipFully(len.toLong())
            }
            if (last) break
        }
        textCue?.let { parseText(it, totalMs, tags)?.let { a -> return a } }
        val offs = nativeOffsets ?: return null
        if (offs.size < 2) return null
        val starts = offs.map { (n, s) -> n to s * 1000 / sampleRate }
        return CueAlbum(tags["ALBUM"], tags["ARTIST"], tags["DATE"]?.take(4)?.toIntOrNull() ?: 0, tags["GENRE"], starts.mapIndexed { i, (n, ms) ->
            CueTrack(n, "Трек $n", null, ms, starts.getOrNull(i + 1)?.second ?: totalMs)
        })
    }

    /** Parses cue text (TITLE/PERFORMER/REM DATE + TRACK/INDEX 01 mm:ss:ff, 75 frames/s). */
    fun parseText(text: String, totalMs: Long, tags: Map<String, String> = emptyMap()): CueAlbum? {
        var albumTitle: String? = null; var albumPerformer: String? = null; var year = 0; var genre: String? = null
        data class T(val n: Int, var title: String? = null, var performer: String? = null, var start: Long = -1)
        val tracks = mutableListOf<T>()
        fun unq(s: String) = s.trim().removeSurrounding("\"")
        for (raw in text.lines()) {
            val line = raw.trim()
            val cur = tracks.lastOrNull()
            when {
                line.startsWith("TRACK ", true) -> line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()?.let { tracks += T(it) }
                line.startsWith("TITLE ", true) -> if (cur == null) albumTitle = unq(line.substring(6)) else cur.title = unq(line.substring(6))
                line.startsWith("PERFORMER ", true) -> if (cur == null) albumPerformer = unq(line.substring(10)) else cur.performer = unq(line.substring(10))
                line.startsWith("REM DATE ", true) -> year = line.substring(9).trim().take(4).toIntOrNull() ?: year
                line.startsWith("REM GENRE ", true) -> genre = unq(line.substring(10))
                line.startsWith("INDEX 01 ", true) && cur != null -> {
                    val (m, s, f) = line.substring(9).trim().split(':').map { it.toIntOrNull() ?: 0 }.let { Triple(it[0], it.getOrElse(1) { 0 }, it.getOrElse(2) { 0 }) }
                    cur.start = m * 60_000L + s * 1000L + f * 1000L / 75
                }
            }
        }
        val valid = tracks.filter { it.start >= 0 }.sortedBy { it.start }
        if (valid.size < 2) return null
        return CueAlbum(
            albumTitle ?: tags["ALBUM"], albumPerformer ?: tags["ARTIST"], if (year > 0) year else tags["DATE"]?.take(4)?.toIntOrNull() ?: 0,
            genre ?: tags["GENRE"],
            valid.mapIndexed { i, t -> CueTrack(t.n, t.title ?: "Трек ${t.n}", t.performer, t.start, valid.getOrNull(i + 1)?.start ?: totalMs) },
        )
    }

    private fun InputStream.readFully(b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }

    private fun InputStream.skipFully(len: Long) {
        var left = len
        while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) return; left-- } else left -= s }
    }
}
