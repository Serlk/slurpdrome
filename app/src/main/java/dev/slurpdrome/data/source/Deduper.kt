package dev.slurpdrome.data.source

import android.util.Log
import androidx.room.withTransaction
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.prefs.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Merges the same artist / album / recording found on several sources into one visible entry.
 *
 * Every row gets a mergeKey; within a group one row is the representative (isPrimary) chosen by
 * source priority, then quality. Lists show representatives only; album/artist pages gather the
 * whole group via groupAlbumId / groupArtistId; playback picks the best *available* copy.
 */
class Deduper(
    private val db: AppDatabase,
    private val settings: StateFlow<Settings>,
    private val sources: SourceRegistry,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val merge = settings.value.mergeDuplicates
        val rank = rankFn()
        val d = db.dedup()
        val artists = d.allArtists()
        val albums = d.allAlbums()
        val songs = d.allSongs()
        val splitImages = db.cueSplits().splitParents().toHashSet()   // replaced by their virtual tracks

        // ---- artists: by normalised name
        val artistKey = artists.associate { it.id to if (merge) "ar:" + norm(it.name) else "id:" + it.id }
        val artistRep = artists.groupBy { artistKey.getValue(it.id) }.mapValues { (_, g) -> g.minWith(compareBy({ rank(it.sourceId) }, { -it.albumCount })).id }
        val artistByName = artists.groupBy { norm(it.name) }.mapValues { (_, g) -> artistRep.getValue(artistKey.getValue(g.first().id)) }
        fun repArtistFor(id: String?, name: String): String? =
            id?.let { artistKey[it] }?.let(artistRep::get) ?: if (merge) artistByName[norm(name)] else id

        // ---- albums: by album artist + cleaned title
        val albumKey = albums.associate { it.id to if (merge) "al:" + norm(it.artist) + "|" + norm(cleanEdition(it.name)) else "id:" + it.id }
        val albumGroups = albums.groupBy { albumKey.getValue(it.id) }
        val albumRep = albumGroups.mapValues { (_, g) -> g.minWith(compareBy({ rank(it.sourceId) }, { -it.songCount })).id }

        // ---- songs: album group + disc/track/title (or artist/title/duration when untagged)
        fun songKey(s: SongEntity): String {
            if (!merge) return "id:" + s.id
            val ak = s.albumId?.let(albumKey::get)
            return if (ak != null && s.track > 0) "so:$ak|${s.disc}|${s.track}|${norm(cleanEdition(s.title))}"
            else "so:${norm(s.artist)}|${norm(cleanEdition(s.title))}|${s.duration / 10}"
        }
        val keys = songs.associate { it.id to songKey(it) }
        val songRep = songs.groupBy { keys.getValue(it.id) }.mapValues { (_, g) ->
            g.minWith(compareBy({ rank(it.sourceId) }, { -qualityScore(it) })).id
        }

        db.withTransaction {
            var changed = 0
            val primarySongsByAlbum = HashMap<String, MutableList<SongEntity>>()
            for (s in songs) {
                val key = keys.getValue(s.id)
                val primary = songRep[key] == s.id && s.id !in splitImages
                val gAlbum = s.albumId?.let { albumKey[it] }?.let(albumRep::get) ?: s.albumId
                val gArtist = repArtistFor(s.artistId, s.artist)
                if (primary && gAlbum != null) primarySongsByAlbum.getOrPut(gAlbum) { mutableListOf() } += s
                if (s.mergeKey != key || s.isPrimary != primary || s.groupAlbumId != gAlbum || s.groupArtistId != gArtist) {
                    d.markSong(s.id, key, primary, gAlbum, gArtist); changed++
                }
            }
            val primaryAlbumsByArtist = HashMap<String, Int>()
            for (a in albums) {
                val key = albumKey.getValue(a.id)
                val gArtist = repArtistFor(a.artistId, a.artist)
                val tracksOrNull = if (albumRep[key] == a.id) primarySongsByAlbum[a.id] else null
                // An album whose every file was a split image now has nothing of its own to show.
                val emptied = albumRep[key] == a.id && tracksOrNull == null && songs.any { it.albumId == a.id && it.id in splitImages }
                val primaryAlbum = albumRep[key] == a.id && !emptied
                val tracks = tracksOrNull
                val count = tracks?.size ?: a.songCount
                val duration = tracks?.sumOf { it.duration } ?: a.duration
                if (primaryAlbum && gArtist != null) primaryAlbumsByArtist.merge(gArtist, 1, Int::plus)
                if (a.mergeKey != key || a.isPrimary != primaryAlbum || a.groupArtistId != gArtist || a.songCount != count || a.duration != duration) {
                    d.markAlbum(a.id, key, primaryAlbum, gArtist, count, duration); changed++
                }
            }
            for (r in artists) {
                val key = artistKey.getValue(r.id)
                val primary = artistRep[key] == r.id
                val count = if (primary) primaryAlbumsByArtist[r.id] ?: r.albumCount else r.albumCount
                if (r.mergeKey != key || r.isPrimary != primary || r.albumCount != count) {
                    d.markArtist(r.id, key, primary, count); changed++
                }
            }
            Log.i("Dedup", "${songs.size} songs → ${songRep.size} unique, $changed rows updated in ${System.currentTimeMillis() - t0} ms")
        }
    }

    /** Lower = preferred. User order first; by default local files win (instant, offline). */
    private fun rankFn(): (String) -> Int {
        val order = settings.value.sourcePriority
        val all = sources.sources.value
        return { sid ->
            val i = order.indexOf(sid)
            when {
                i >= 0 -> i
                sid == Source.LOCAL_ID -> -1
                else -> 100 + all.indexOfFirst { it.id == sid }.coerceAtLeast(0)
            }
        }
    }

    companion object {
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf")
        private val editionWords = Regex(
            """\s*[(\[][^)\]]*\b(remaster(ed)?|deluxe|expanded|edition|anniversary|bonus|special|reissue|version\s+\d{4}|\d{4}\s+remaster)\b[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val nonWord = Regex("""[^\p{L}\p{N}]+""")
        private val marks = Regex("""\p{M}+""")

        fun qualityScore(s: SongEntity): Int =
            (if (s.suffix?.lowercase() in LOSSLESS) 10_000 else 0) + s.bitRate

        /** Drops "(Remastered 2011)", "[Deluxe Edition]" … but keeps "(Live)", "(Remix)". */
        fun cleanEdition(title: String) = title.replace(editionWords, "").trim()

        /** Case/diacritics/punctuation-insensitive form ("Björk" == "bjork", "ё" == "е"). */
        fun norm(s: String): String {
            val base = Normalizer.normalize(s.lowercase().replace('ё', 'е'), Normalizer.Form.NFKD).replace(marks, "")
            return base.replace("&", " and ").replace(nonWord, " ").trim().replace(Regex("^the "), "")
        }
    }
}
