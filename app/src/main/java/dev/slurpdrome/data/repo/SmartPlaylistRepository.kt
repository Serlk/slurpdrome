package dev.slurpdrome.data.repo

import androidx.annotation.StringRes
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.slurpdrome.R
import dev.slurpdrome.data.db.AppDatabase
import dev.slurpdrome.data.db.SmartPlaylistEntity
import dev.slurpdrome.data.db.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class FieldType { TEXT, NUMBER, BOOL, DAYS }

enum class SmartField(@StringRes val labelRes: Int, val type: FieldType, val column: String) {
    TITLE(R.string.smart_field_title, FieldType.TEXT, "title"),
    ARTIST(R.string.smart_field_artist, FieldType.TEXT, "artist"),
    ALBUM(R.string.smart_field_album, FieldType.TEXT, "album"),
    GENRE(R.string.smart_field_genre, FieldType.TEXT, "genre"),
    FORMAT(R.string.smart_field_format, FieldType.TEXT, "suffix"),
    YEAR(R.string.smart_field_year, FieldType.NUMBER, "year"),
    RATING(R.string.smart_field_rating, FieldType.NUMBER, "rating"),
    PLAY_COUNT(R.string.smart_field_play_count, FieldType.NUMBER, "playCount"),
    DURATION(R.string.smart_field_duration, FieldType.NUMBER, "duration"),
    BITRATE(R.string.smart_field_bitrate, FieldType.NUMBER, "bitRate"),
    BPM(R.string.smart_field_bpm, FieldType.NUMBER, "bpm"),
    STARRED(R.string.smart_field_starred, FieldType.BOOL, "starred"),
    DOWNLOADED(R.string.smart_field_downloaded, FieldType.BOOL, "__downloaded"),
    ADDED(R.string.smart_field_added, FieldType.DAYS, "created"),
    PLAYED(R.string.smart_field_played, FieldType.DAYS, "lastPlayed"),
}

enum class SmartOp(@StringRes val labelRes: Int, val types: Set<FieldType>) {
    CONTAINS(R.string.smart_op_contains, setOf(FieldType.TEXT)),
    NOT_CONTAINS(R.string.smart_op_not_contains, setOf(FieldType.TEXT)),
    IS(R.string.smart_op_is, setOf(FieldType.TEXT, FieldType.NUMBER)),
    IS_NOT(R.string.smart_op_is_not, setOf(FieldType.TEXT, FieldType.NUMBER)),
    STARTS_WITH(R.string.smart_op_starts_with, setOf(FieldType.TEXT)),
    GT(R.string.smart_op_gt, setOf(FieldType.NUMBER)),
    LT(R.string.smart_op_lt, setOf(FieldType.NUMBER)),
    BETWEEN(R.string.smart_op_between, setOf(FieldType.NUMBER)),
    TRUE(R.string.smart_op_true, setOf(FieldType.BOOL)),
    FALSE(R.string.smart_op_false, setOf(FieldType.BOOL)),
    IN_LAST(R.string.smart_op_in_last, setOf(FieldType.DAYS)),
    NOT_IN_LAST(R.string.smart_op_not_in_last, setOf(FieldType.DAYS)),
    NEVER(R.string.smart_op_never, setOf(FieldType.DAYS)),
    ;
    companion object { fun forType(t: FieldType) = entries.filter { t in it.types } }
}

@Serializable
data class SmartRule(val field: SmartField = SmartField.GENRE, val op: SmartOp = SmartOp.CONTAINS, val value: String = "")

@Serializable
data class SmartSpec(
    val matchAll: Boolean = true,
    val rules: List<SmartRule> = emptyList(),
    val sort: String = "RANDOM",     // SongSort name or RANDOM
    val limit: Int = 100,
)

class SmartPlaylistRepository(private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all() = db.smartPlaylists().observeAll()
    fun byId(id: Long) = db.smartPlaylists().observeById(id)

    fun decode(e: SmartPlaylistEntity): SmartSpec =
        runCatching { json.decodeFromString(SmartSpec.serializer(), e.specJson) }.getOrDefault(SmartSpec())

    fun encode(s: SmartSpec): String = json.encodeToString(SmartSpec.serializer(), s)

    suspend fun save(id: Long?, name: String, spec: SmartSpec): Long {
        val entity = SmartPlaylistEntity(id = id ?: 0, name = name, specJson = encode(spec))
        val r = db.smartPlaylists().upsert(entity)
        return if (id != null && id != 0L) id else r
    }

    suspend fun delete(id: Long) = db.smartPlaylists().delete(id)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeSongs(id: Long): Flow<List<SongEntity>> = byId(id).flatMapLatest { e ->
        if (e == null) flowOf(emptyList()) else observe(decode(e))
    }

    fun observe(spec: SmartSpec): Flow<List<SongEntity>> = db.songs().observeRaw(SmartQuery.build(spec))

    suspend fun evaluate(spec: SmartSpec): List<SongEntity> = db.songs().queryRaw(SmartQuery.build(spec))

    suspend fun evaluateIds(id: Long): List<String> {
        val e = db.smartPlaylists().byId(id) ?: return emptyList()
        // Random order would make offline sets churn on every reconcile; pin a stable order.
        val spec = decode(e).let { if (it.sort == "RANDOM") it.copy(sort = SongSort.PLAYS.name) else it }
        return evaluate(spec).map { it.id }
    }

    companion object {
        /** Ready-made templates shown when creating a smart playlist. */
        /** Name is a @StringRes id. */
        val templates: List<Pair<Int, SmartSpec>> = listOf(
            R.string.smart_tpl_loved_forgotten to SmartSpec(
                rules = listOf(SmartRule(SmartField.STARRED, SmartOp.TRUE), SmartRule(SmartField.PLAYED, SmartOp.NOT_IN_LAST, "60")),
                sort = "RANDOM", limit = 100,
            ),
            R.string.smart_tpl_never_played to SmartSpec(rules = listOf(SmartRule(SmartField.PLAYED, SmartOp.NEVER)), sort = "RANDOM", limit = 100),
            R.string.smart_tpl_fresh to SmartSpec(rules = listOf(SmartRule(SmartField.ADDED, SmartOp.IN_LAST, "30")), sort = SongSort.ADDED.name, limit = 200),
            R.string.smart_tpl_top_rated to SmartSpec(rules = listOf(SmartRule(SmartField.RATING, SmartOp.GT, "3")), sort = "RANDOM", limit = 200),
            R.string.smart_tpl_lossless to SmartSpec(
                matchAll = false,
                rules = listOf(
                    SmartRule(SmartField.FORMAT, SmartOp.IS, "flac"),
                    SmartRule(SmartField.FORMAT, SmartOp.IS, "alac"),
                    SmartRule(SmartField.FORMAT, SmartOp.IS, "wav"),
                ),
                sort = SongSort.ARTIST.name, limit = 0,
            ),
            R.string.smart_tpl_2000s to SmartSpec(rules = listOf(SmartRule(SmartField.YEAR, SmartOp.BETWEEN, "2000..2009")), sort = "RANDOM", limit = 100),
        )
    }
}

/** Pure rule → SQL compiler (no DB access, unit-testable). Values are always bound, never concatenated. */
object SmartQuery {
    fun build(spec: SmartSpec, now: Long = System.currentTimeMillis()): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val clauses = spec.rules.mapNotNull { r -> clause(r, args, now) }
        val rules = if (clauses.isEmpty()) "" else " AND (" + clauses.joinToString(if (spec.matchAll) " AND " else " OR ") { "($it)" } + ")"
        val where = "WHERE isPrimary = 1$rules"
        val order = if (spec.sort == "RANDOM") "RANDOM()" else (SongSort.entries.firstOrNull { it.name == spec.sort } ?: SongSort.TITLE).sql
        val limit = if (spec.limit > 0) "LIMIT ${spec.limit}" else ""
        return SimpleSQLiteQuery("SELECT * FROM songs $where ORDER BY $order $limit".trim(), args.toTypedArray())
    }

    private fun clause(r: SmartRule, args: MutableList<Any>, now: Long): String? {
        val col = r.field.column
        val v = r.value.trim()
        return when (r.field.type) {
            FieldType.TEXT -> when (r.op) {
                SmartOp.CONTAINS -> { args += "%$v%"; "IFNULL($col,'') LIKE ?" }
                SmartOp.NOT_CONTAINS -> { args += "%$v%"; "IFNULL($col,'') NOT LIKE ?" }
                SmartOp.IS -> { args += v; "IFNULL($col,'') = ? COLLATE NOCASE" }
                SmartOp.IS_NOT -> { args += v; "IFNULL($col,'') != ? COLLATE NOCASE" }
                SmartOp.STARTS_WITH -> { args += "$v%"; "IFNULL($col,'') LIKE ?" }
                else -> null
            }
            FieldType.NUMBER -> {
                if (r.op == SmartOp.BETWEEN) {
                    val parts = v.split("..", "-", " ").mapNotNull { it.trim().toDoubleOrNull() }
                    if (parts.size < 2) return null
                    args += parts[0]; args += parts[1]
                    "$col BETWEEN ? AND ?"
                } else {
                    val n = v.toDoubleOrNull() ?: return null
                    val sql = when (r.op) {
                        SmartOp.IS -> "$col = ?"
                        SmartOp.IS_NOT -> "$col != ?"
                        SmartOp.GT -> "$col > ?"
                        SmartOp.LT -> "$col < ?"
                        else -> return null
                    }
                    args += n
                    sql
                }
            }
            FieldType.BOOL -> {
                val truth = r.op == SmartOp.TRUE
                if (r.field == SmartField.DOWNLOADED) {
                    "id ${if (truth) "IN" else "NOT IN"} (SELECT songId FROM downloads WHERE state = 2)"
                } else "$col = ${if (truth) 1 else 0}"
            }
            FieldType.DAYS -> when (r.op) {
                SmartOp.NEVER -> "$col = 0"
                SmartOp.IN_LAST, SmartOp.NOT_IN_LAST -> {
                    val days = v.toLongOrNull() ?: return null
                    args += now - days * 86_400_000L
                    if (r.op == SmartOp.IN_LAST) "$col >= ?" else "$col < ?"
                }
                else -> null
            }
        }
    }
}
