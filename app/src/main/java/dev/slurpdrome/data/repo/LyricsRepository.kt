package dev.slurpdrome.data.repo

import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.LyricsEntity
import dev.slurpdrome.data.db.SongEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class LyricLineUi(val t: Long? = null, val v: String = "")

data class Lyrics(val synced: Boolean, val lines: List<LyricLineUi>) {
    val isEmpty get() = lines.isEmpty()

    /** Index of the line active at [positionMs], or -1 before the first line. */
    fun activeIndex(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0; var hi = lines.lastIndex; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if ((lines[mid].t ?: 0) <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

class LyricsRepository(private val db: AppDatabase, private val sources: dev.slurpdrome.data.source.SourceRegistry) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LyricLineUi.serializer())

    suspend fun get(song: SongEntity, forceRefresh: Boolean = false): Lyrics? {
        val cached = db.lyrics().byId(song.id)
        val fresh = cached != null && (cached.linesJson != "[]" || System.currentTimeMillis() - cached.fetchedAt < 3 * 86_400_000L)
        if (cached != null && fresh && !forceRefresh) return decode(cached)

        val fetched = runCatching { fetch(song) }.getOrElse { return cached?.let(::decode) }
        db.lyrics().upsert(
            LyricsEntity(song.id, fetched?.synced ?: false, json.encodeToString(serializer, fetched?.lines.orEmpty()), System.currentTimeMillis())
        )
        return fetched
    }

    private fun decode(e: LyricsEntity): Lyrics? {
        val lines = runCatching { json.decodeFromString(serializer, e.linesJson) }.getOrDefault(emptyList())
        return if (lines.isEmpty()) null else Lyrics(e.synced, lines)
    }

    private suspend fun fetch(song: SongEntity): Lyrics? {
        // OpenSubsonic structured lyrics (Navidrome ≥ 0.53): embedded tags or .lrc sidecar files.
        // Local files: ask any server that might know this song by artist/title.
        val api = sources.apiFor(song.id) ?: sources.servers.firstOrNull()?.let { sources.api(it.id) } ?: return null
        val structured = if (Gid.isLocal(song.id) || Gid.isVirtual(song.id)) emptyList()
            else runCatching { api.getLyricsBySongId(Gid.raw(song.id)) }.getOrDefault(emptyList())
        val best = structured.firstOrNull { it.synced && it.line.isNotEmpty() } ?: structured.firstOrNull { it.line.isNotEmpty() }
        if (best != null) {
            return Lyrics(best.synced, best.line.map { LyricLineUi(it.start?.plus(best.offset), it.value) })
        }
        val plain = api.getLyrics(song.artist, song.title)?.value?.takeIf { it.isNotBlank() } ?: return null
        return parseLrc(plain) ?: Lyrics(false, plain.lines().map { LyricLineUi(null, it) })
    }

    companion object {
        private val TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        /** Parses LRC text (`[mm:ss.xx] line`, multiple stamps per line allowed). */
        fun parseLrc(text: String): Lyrics? {
            val out = mutableListOf<LyricLineUi>()
            for (raw in text.lines()) {
                val stamps = TAG.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val content = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (mm, ss, frac) = m.destructured
                    val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                    out += LyricLineUi(mm.toLong() * 60_000 + ss.toLong() * 1000 + ms, content)
                }
            }
            return if (out.size >= 2) Lyrics(true, out.sortedBy { it.t }) else null
        }
    }
}
