package dev.slurpdrome.data.repo

import dev.slurpdrome.R
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.slurpdrome.data.source.Gid
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.PlayEventEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

enum class MixKind { MY_MIX, DISCOVERY, FORGOTTEN, GENRE, ARTIST, RANDOM }

data class Mix(
    val key: String,
    val kind: MixKind,
    val title: String,
    val subtitle: String,
    val songIds: List<String>,
    val coverArts: List<String>,
)

/** What an instant mix / radio is seeded from. */
sealed interface MixSeed {
    data class Song(val id: String) : MixSeed
    data class Album(val id: String) : MixSeed
    data class Artist(val id: String) : MixSeed
    data class Genre(val name: String) : MixSeed
}

/**
 * Personal recommendation engine running entirely on-device over the local listening log.
 *
 * Model, in short:
 *  - every play event is an implicit rating: completed ≈ +1, early skip ≈ −0.8, partial ≈ +0.3,
 *    exponentially decayed with a 21-day half-life (recent taste matters more);
 *  - explicit signals: favourite (+2), star rating (centered on 3);
 *  - per-artist and per-genre affinities are aggregated from those song signals;
 *  - Navidrome's getSimilarSongs2 (Last.fm-backed) adds a collaborative "people who like X" signal;
 *  - candidates are drawn by weighted reservoir sampling, so mixes vary but stay on-taste;
 *  - finally a diversity pass prevents the same artist appearing within 3 slots.
 */
class MixEngine(
    private val ctx: android.content.Context,
    private val db: AppDatabase,
    private val sources: dev.slurpdrome.data.source.SourceRegistry,
    private val settings: SettingsStore,
    private val library: LibraryRepository,
) {
    private val cacheMutex = Mutex()
    private var cached: Pair<Long, List<Mix>>? = null

    private class Profile(
        val songs: List<SongEntity>,
        val songScore: Map<String, Double>,
        val artist: Map<String, Double>,
        val genre: Map<String, Double>,
        val playedIds: Set<String>,
        val recentIds: Set<String>,
        val lastHeard: Map<String, Long>,
    )

    suspend fun mixes(force: Boolean = false): List<Mix> = cacheMutex.withLock {
        val c = cached
        if (!force && c != null && System.currentTimeMillis() - c.first < 30 * 60_000L) return c.second
        val result = withContext(Dispatchers.Default) { buildMixes() }
        cached = System.currentTimeMillis() to result
        result
    }

    fun invalidate() { cached = null }

    private suspend fun buildProfile(): Profile {
        val s = settings.current()
        val now = System.currentTimeMillis()
        val songs = db.songs().queryRaw(SimpleSQLiteQuery("SELECT * FROM songs WHERE isPrimary = 1"))
        val events = db.playEvents().since(now - 180L * DAY)
        val byId = songs.associateBy { it.id }

        val songScore = HashMap<String, Double>(songs.size)
        val lastHeard = HashMap<String, Long>()
        for (e in events) {
            val age = (now - e.startedAt).toDouble() / DAY
            val decay = 0.5.pow(age / HALF_LIFE_DAYS)
            songScore.merge(e.songId, signal(e) * decay, Double::plus)
            lastHeard.merge(e.songId, e.startedAt, ::maxOf)
        }
        // Explicit + server-side history (covers plays from other clients before we existed).
        for (song in songs) {
            var explicit = 0.0
            if (song.starred) explicit += 2.0
            if (song.rating > 0) explicit += (song.rating - 3) * 0.8
            if (song.playCount > 0) explicit += ln1p(song.playCount.toDouble()) * 0.35
            if (explicit != 0.0) songScore.merge(song.id, explicit, Double::plus)
            if (song.lastPlayed > 0) lastHeard.merge(song.id, song.lastPlayed, ::maxOf)
        }

        val artist = HashMap<String, Double>()
        val genre = HashMap<String, Double>()
        for ((id, score) in songScore) {
            val song = byId[id] ?: continue
            song.artistId?.let { artist.merge(it, score, Double::plus) }
            song.genre?.let { genre.merge(it.lowercase(), score, Double::plus) }
        }
        db.artists().byIds(artist.keys.toList().take(900)).filter { it.starred }.forEach { artist.merge(it.id, 3.0, Double::plus) }

        // Squash to roughly [-1, 1] so artist/genre weights are comparable regardless of history size.
        fun squash(m: Map<String, Double>): Map<String, Double> {
            val scale = m.values.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
            return m.mapValues { it.value / scale }
        }

        val recentCut = now - s.mixExcludeRecentHours * 3_600_000L
        val recent = lastHeard.filterValues { it >= recentCut }.keys
        val played = songs.filter { it.playCount > 0 || it.lastPlayed > 0 }.map { it.id }.toSet() + events.map { it.songId }
        return Profile(songs, songScore, squash(artist), squash(genre), played, recent, lastHeard)
    }

    private fun signal(e: PlayEventEntity): Double {
        val frac = if (e.durationMs > 0) e.listenedMs.toDouble() / e.durationMs else 0.0
        return when {
            e.completed || frac >= 0.8 -> 1.0
            e.skipped && (e.listenedMs < 30_000 || frac < 0.25) -> -0.8
            frac >= 0.5 -> 0.6
            else -> 0.3
        }
    }

    private fun affinity(p: Profile, song: SongEntity): Double =
        (p.artist[song.artistId] ?: 0.0) * 0.6 + (p.genre[song.genre?.lowercase()] ?: 0.0) * 0.4

    private suspend fun buildMixes(): List<Mix> {
        val s = settings.current()
        val p = buildProfile()
        if (p.songs.isEmpty()) return emptyList()
        val size = s.mixSize
        val out = mutableListOf<Mix>()

        val collab = collaborativeBoost(p)

        out += mix("my", MixKind.MY_MIX, ctx.getString(R.string.mix_my_title), ctx.getString(R.string.mix_my_subtitle),
            personal(p, size, s.mixDiscovery.toDouble(), collab), p)

        val discovery = sample(
            p.songs.filter { it.id !in p.playedIds }, size,
        ) { 0.15 + max(0.0, affinity(p, it)) + (collab[it.id] ?: 0.0) }
        out += mix("discovery", MixKind.DISCOVERY, ctx.getString(R.string.mix_discovery_title), ctx.getString(R.string.mix_discovery_subtitle), diversify(discovery), p)

        val now = System.currentTimeMillis()
        val forgotten = sample(
            p.songs.filter { (p.songScore[it.id] ?: 0.0) > 0.8 && (p.lastHeard[it.id] ?: 0L) < now - 45 * DAY },
            size,
        ) { p.songScore[it.id] ?: 0.0 }
        if (forgotten.size >= 10) out += mix("forgotten", MixKind.FORGOTTEN, ctx.getString(R.string.mix_forgotten_title), ctx.getString(R.string.mix_forgotten_subtitle), diversify(forgotten), p)

        p.genre.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3).forEach { (g, _) ->
            val pool = p.songs.filter { it.genre?.lowercase() == g && it.id !in p.recentIds }
            val picked = sample(pool, size) { 0.2 + max(0.0, (p.songScore[it.id] ?: 0.0)) * 0.5 + max(0.0, p.artist[it.artistId] ?: 0.0) }
            val name = pool.firstOrNull()?.genre ?: g
            if (picked.size >= 10) out += mix("genre:$g", MixKind.GENRE, ctx.getString(R.string.mix_genre_title, name), ctx.getString(R.string.mix_genre_subtitle), diversify(picked), p)
        }

        p.artist.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(2).forEach { (artistId, _) ->
            val name = p.songs.firstOrNull { it.artistId == artistId }?.artist ?: return@forEach
            val ids = instantMixIds(MixSeed.Artist(artistId), size, p)
            if (ids.size >= 10) out += mix("artist:$artistId", MixKind.ARTIST, ctx.getString(R.string.mix_artist_title, name), ctx.getString(R.string.mix_artist_subtitle, name), ids, p)
        }

        out += mix("random", MixKind.RANDOM, ctx.getString(R.string.mix_random_title), ctx.getString(R.string.mix_random_subtitle),
            p.songs.shuffled().take(size * 2).map { it.id }, p)
        return out.filter { it.songIds.isNotEmpty() }
    }

    private fun mix(key: String, kind: MixKind, title: String, subtitle: String, ids: List<String>, p: Profile): Mix {
        val byId = p.songs.associateBy { it.id }
        val covers = ids.asSequence().mapNotNull { byId[it]?.coverArt }.distinct().take(4).toList()
        return Mix(key, kind, title, subtitle, ids, covers)
    }

    private fun personal(p: Profile, size: Int, discoveryShare: Double, collab: Map<String, Double>): List<String> {
        val nDiscovery = (size * discoveryShare).toInt()
        val familiar = sample(
            p.songs.filter { it.id in p.playedIds && it.id !in p.recentIds && (p.songScore[it.id] ?: 0.0) > 0 },
            size - nDiscovery,
        ) { (p.songScore[it.id] ?: 0.0) + max(0.0, affinity(p, it)) }
        val fresh = sample(
            p.songs.filter { it.id !in p.playedIds },
            nDiscovery + (size - nDiscovery - familiar.size).coerceAtLeast(0),
        ) { 0.05 + max(0.0, affinity(p, it)) * 2 + (collab[it.id] ?: 0.0) * 2 }
        // Interleave so discoveries are spread out, not bunched at the end.
        val merged = mutableListOf<SongEntity>()
        val f = familiar.iterator(); val d = fresh.iterator()
        val every = if (fresh.isEmpty()) Int.MAX_VALUE else max(1, (familiar.size + fresh.size) / max(1, fresh.size))
        var i = 0
        while (f.hasNext() || d.hasNext()) {
            if ((i % every == every - 1 && d.hasNext()) || !f.hasNext()) merged += d.next() else merged += f.next()
            i++
        }
        return diversify(merged)
    }

    /** Server similarity for the user's top artists (Last.fm via Navidrome). Empty when offline. */
    private suspend fun collaborativeBoost(p: Profile): Map<String, Double> {
        val top = p.artist.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4).map { it.key }
        if (top.isEmpty()) return emptyMap()
        val result = withTimeoutOrNull(6_000) {
            coroutineScope {
                top.map { id ->
                    async {
                        runCatching { sources.apiFor(id)?.getSimilarSongsByArtist(Gid.raw(id), 40).orEmpty().map { Gid.of(Gid.source(id), it.id) } }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
        } ?: return emptyMap()
        val local = p.songs.associateBy { it.id }
        val boost = HashMap<String, Double>()
        result.forEachIndexed { rank, list ->
            list.forEachIndexed { pos, gid ->
                if (gid in local) boost.merge(gid, 0.6 / (1 + rank) * (1.0 - pos / 60.0), Double::plus)
            }
        }
        return boost
    }

    /**
     * More of the same mix for an endless queue: the mix is sampled again over songs that weren't
     * in the queue yet ([exclude]), so it never repeats within a session. Empty when exhausted.
     */
    suspend fun extend(key: String, exclude: Set<String>, size: Int = 25): List<String> = withContext(Dispatchers.Default) {
        val s = settings.current()
        val full = buildProfile()
        val p = Profile(full.songs.filter { it.id !in exclude }, full.songScore, full.artist, full.genre, full.playedIds, full.recentIds, full.lastHeard)
        if (p.songs.isEmpty()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        when {
            key == "my" -> personal(p, size, s.mixDiscovery.toDouble(), collaborativeBoost(p))
            key == "discovery" -> diversify(sample(p.songs.filter { it.id !in p.playedIds }, size) { 0.15 + max(0.0, affinity(p, it)) })
            key == "forgotten" -> diversify(sample(p.songs.filter { (p.songScore[it.id] ?: 0.0) > 0.8 && (p.lastHeard[it.id] ?: 0L) < now - 45 * DAY }, size) { p.songScore[it.id] ?: 0.0 })
            key.startsWith("genre:") -> {
                val g = key.removePrefix("genre:")
                diversify(sample(p.songs.filter { it.genre?.lowercase() == g }, size) { 0.2 + max(0.0, (p.songScore[it.id] ?: 0.0)) * 0.5 + max(0.0, p.artist[it.artistId] ?: 0.0) })
            }
            key.startsWith("artist:") -> instantMixIds(MixSeed.Artist(key.removePrefix("artist:")), size, p, exclude = exclude)
            else -> p.songs.shuffled().take(size).map { it.id }
        }
    }

    // -------------------- instant mix / radio --------------------

    suspend fun instantMix(seed: MixSeed, size: Int = 60, avoidArtists: Set<String> = emptySet(), exclude: Set<String> = emptySet()): List<String> =
        withContext(Dispatchers.Default) { instantMixIds(seed, size, buildProfile(), avoidArtists, exclude) }

    private suspend fun instantMixIds(
        seed: MixSeed,
        size: Int,
        p: Profile,
        avoidArtists: Set<String> = emptySet(),
        exclude: Set<String> = emptySet(),
    ): List<String> {
        val byId = p.songs.associateBy { it.id }
        val seedSongs: List<SongEntity> = when (seed) {
            is MixSeed.Song -> listOfNotNull(byId[seed.id])
            is MixSeed.Album -> p.songs.filter { it.albumId == seed.id }
            is MixSeed.Artist -> p.songs.filter { it.artistId == seed.id }
            is MixSeed.Genre -> p.songs.filter { it.genre.equals(seed.name, ignoreCase = true) }
        }
        val seedArtists = seedSongs.mapNotNull { it.artistId }.toSet()
        val seedGenres = seedSongs.mapNotNull { it.genre?.lowercase() }.groupingBy { it }.eachCount()
        val seedYear = seedSongs.map { it.year }.filter { it > 0 }.average().takeIf { !it.isNaN() }

        // Collaborative neighbours from the server (song / album / artist id all accepted by getSimilarSongs).
        val serverSimilar: List<String> = withTimeoutOrNull(6_000) {
            runCatching {
                val seedId = when (seed) {
                    is MixSeed.Song -> seed.id
                    is MixSeed.Album -> seed.id
                    is MixSeed.Artist -> seed.id
                    is MixSeed.Genre -> null
                }
                val api = seedId?.let { sources.apiFor(it) }
                if (seedId == null || api == null) emptyList()
                else when (seed) {
                    is MixSeed.Artist -> api.getSimilarSongsByArtist(Gid.serverRaw(seedId), size)
                    else -> api.getSimilarSongs(Gid.serverRaw(seedId), size)
                }.map { Gid.of(Gid.source(seedId), it.id) }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        val similarArtistIds: Set<String> = if (seedArtists.size in 1..3) withTimeoutOrNull(4_000) {
            seedArtists.flatMap { id ->
                runCatching { sources.apiFor(id)?.getArtistInfo(Gid.raw(id))?.similarArtist?.map { Gid.of(Gid.source(id), it.id) } }.getOrNull().orEmpty()
            }.toSet()
        } ?: emptySet() else emptySet()

        val serverRank = serverSimilar.withIndex().associate { it.value to it.index }
        val genreTotal = seedGenres.values.sum().coerceAtLeast(1)

        val pool = p.songs.filter { it.id !in exclude && it.artistId !in avoidArtists }
        val scored = sample(pool, size) { song ->
            var w = 0.0
            serverRank[song.id]?.let { w += 3.0 * (1.0 - it.toDouble() / (serverSimilar.size + 1)) }
            if (song.artistId in seedArtists) w += if (seed is MixSeed.Artist) 1.5 else 0.8
            if (song.artistId in similarArtistIds) w += 1.4
            song.genre?.lowercase()?.let { g -> seedGenres[g]?.let { w += 1.2 * it / genreTotal } }
            if (seedYear != null && song.year > 0) w += 0.4 * exp(-kotlin.math.abs(song.year - seedYear) / 6.0)
            if (w <= 0.0) return@sample 0.0
            // Personal taste tilts the radio but never dominates the seed's character.
            w += max(-0.5, (p.songScore[song.id] ?: 0.0) * 0.25) + max(0.0, affinity(p, song)) * 0.4
            if (song.id in p.recentIds) w *= 0.4
            w
        }
        val head = (seed as? MixSeed.Song)?.let { byId[it.id] }
        val list = diversify(scored.filter { it.id != head?.id })
        return if (head != null) listOf(head.id) + list else list
    }

    /**
     * Endless radio continuation: seeded by what was *actually enjoyed* this session,
     * steering away from artists the user skipped.
     */
    suspend fun continueRadio(history: List<Pair<String, Boolean>>, queued: Set<String>, size: Int = 25): List<String> {
        val liked = history.filter { !it.second }.map { it.first }
        val skipped = history.filter { it.second }.map { it.first }
        val skippedArtists = library.songsByIds(skipped).groupingBy { it.artistId }.eachCount()
            .filter { it.value >= 2 }.keys.filterNotNull().toSet()
        val seedId = liked.lastOrNull() ?: history.lastOrNull()?.first ?: return emptyList()
        return instantMix(MixSeed.Song(seedId), size + 1, skippedArtists, queued).drop(1)
    }

    // -------------------- sampling helpers --------------------

    /** Weighted sampling without replacement (Efraimidis–Spirakis): key = ln(u) / w. */
    private fun sample(pool: List<SongEntity>, n: Int, weight: (SongEntity) -> Double): List<SongEntity> {
        if (n <= 0 || pool.isEmpty()) return emptyList()
        val rnd = Random(System.nanoTime())
        return pool.asSequence()
            .mapNotNull { s -> val w = weight(s); if (w > 0) s to (ln(rnd.nextDouble().coerceAtLeast(1e-12)) / w) else null }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
            .toList()
    }

    /** Re-orders so the same artist never appears within [gap] slots, when possible. */
    private fun diversify(songs: List<SongEntity>, gap: Int = 3): List<String> {
        val pending = songs.toMutableList()
        val out = mutableListOf<SongEntity>()
        while (pending.isNotEmpty()) {
            val recent = out.takeLast(gap).map { it.artistId ?: it.artist }.toSet()
            val idx = pending.indexOfFirst { (it.artistId ?: it.artist) !in recent }.takeIf { it >= 0 } ?: 0
            out += pending.removeAt(idx)
        }
        return out.map { it.id }
    }

    companion object {
        private const val DAY = 86_400_000L
        private const val HALF_LIFE_DAYS = 21.0
    }
}
