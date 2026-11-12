package dev.slurpdrome.data.repo

import dev.slurpdrome.data.api.ApiAlbum
import dev.slurpdrome.data.api.ApiArtist
import dev.slurpdrome.data.api.ApiPlaylist
import dev.slurpdrome.data.api.ApiSong
import dev.slurpdrome.data.db.AlbumEntity
import dev.slurpdrome.data.db.ArtistEntity
import dev.slurpdrome.data.db.PlaylistEntity
import dev.slurpdrome.data.db.SongEntity
import dev.slurpdrome.data.source.Gid
import java.time.Instant
import java.time.OffsetDateTime

private val CP1251 = java.nio.charset.Charset.forName("windows-1251")

/**
 * Repairs Cyrillic tags that were written in CP1251 but read as Latin-1 ("Îìè ðîòÿò" → "Они ротят").
 * Only touches strings that have no Cyrillic, are mostly À–ÿ letters, and decode into mostly
 * Cyrillic — so real "Björk", "Café", "Motörhead" stay untouched.
 */
fun String.fixMojibake(): String {
    if (isEmpty() || any { it.code > 0xFF } || any { it in '\u0400'..'\u04FF' }) return this
    val high = count { it in '\u00C0'..'\u00FF' }
    val letters = count { it.isLetter() }
    if (high < 3 || high < letters * 0.6) return this
    val fixed = String(toByteArray(Charsets.ISO_8859_1), CP1251)
    val cyr = fixed.count { it in '\u0400'..'\u04FF' }
    return if (cyr >= high * 0.9) fixed else this
}

fun parseIso(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(s).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}

fun ApiSong.toEntity(gen: Long, sid: String) = SongEntity(
    id = Gid.of(sid, id),
    sourceId = sid,
    title = title.ifBlank { path?.substringAfterLast('/') ?: id }.fixMojibake(),
    album = album.orEmpty().fixMojibake(),
    albumId = Gid.ofOrNull(sid, albumId),
    artist = artist.orEmpty().fixMojibake(),
    artistId = Gid.ofOrNull(sid, artistId),
    track = track,
    disc = discNumber,
    year = year,
    genre = genre?.takeIf { it.isNotBlank() },
    duration = duration,
    bitRate = bitRate,
    suffix = suffix,
    contentType = contentType,
    size = size,
    coverArt = Gid.ofOrNull(sid, coverArt),
    created = parseIso(created),
    playCount = playCount,
    lastPlayed = parseIso(played),
    starred = starred != null,
    starredAt = parseIso(starred),
    rating = userRating,
    bpm = bpm,
    trackGain = replayGain?.trackGain,
    albumGain = replayGain?.albumGain,
    syncGen = gen,
)

fun ApiAlbum.toEntity(gen: Long, sid: String) = AlbumEntity(
    id = Gid.of(sid, id),
    sourceId = sid,
    name = name.fixMojibake(),
    artist = artist.orEmpty().fixMojibake(),
    artistId = Gid.ofOrNull(sid, artistId),
    coverArt = Gid.ofOrNull(sid, coverArt),
    songCount = songCount,
    duration = duration,
    year = year,
    genre = genre?.takeIf { it.isNotBlank() },
    created = parseIso(created),
    playCount = playCount,
    lastPlayed = parseIso(played),
    starred = starred != null,
    starredAt = parseIso(starred),
    rating = userRating,
    syncGen = gen,
)

fun ApiArtist.toEntity(gen: Long, sid: String) = ArtistEntity(
    id = Gid.of(sid, id),
    sourceId = sid,
    name = name.fixMojibake(),
    albumCount = albumCount,
    coverArt = Gid.ofOrNull(sid, coverArt),
    imageUrl = artistImageUrl,
    starred = starred != null,
    starredAt = parseIso(starred),
    rating = userRating,
    syncGen = gen,
)

fun ApiPlaylist.toEntity(gen: Long, sid: String) = PlaylistEntity(
    id = Gid.of(sid, id),
    sourceId = sid,
    name = name,
    comment = comment,
    owner = owner,
    songCount = songCount,
    duration = duration,
    coverArt = Gid.ofOrNull(sid, coverArt),
    changed = parseIso(changed),
    syncGen = gen,
)
