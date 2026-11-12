package dev.slurpdrome

import dev.slurpdrome.data.repo.LyricsRepository
import dev.slurpdrome.data.repo.SmartField
import dev.slurpdrome.data.repo.SmartOp
import dev.slurpdrome.data.repo.SmartRule
import dev.slurpdrome.data.repo.SmartSpec
import dev.slurpdrome.data.repo.parseIso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.slurpdrome.data.repo.fixMojibake

class LogicTest {
    @Test fun lrcParsesMultipleStampsAndFractions() {
        val l = LyricsRepository.parseLrc(
            """
            [ar:Someone]
            [00:01.5]first
            [00:12.34][01:02.345]chorus
            [00:20]third
            """.trimIndent()
        )
        assertNotNull(l); l!!
        assertTrue(l.synced)
        assertEquals(listOf(1500L, 12340L, 20000L, 62345L), l.lines.map { it.t })
        assertEquals("chorus", l.lines[1].v)
        assertEquals(-1, l.activeIndex(100))
        assertEquals(1, l.activeIndex(15_000))
        assertEquals(3, l.activeIndex(99_000))
    }

    @Test fun plainTextIsNotLrc() {
        assertNull(LyricsRepository.parseLrc("just\nwords\nhere"))
    }

    @Test fun isoDatesWithNanosParse() {
        assertTrue(parseIso("2026-11-11T16:12:20.352109002Z") > 0)
        assertTrue(parseIso("2025-03-26T22:26:35+03:00") > 0)
        assertEquals(0L, parseIso(null))
        assertEquals(0L, parseIso("garbage"))
    }

    @Test fun smartSqlBindsValuesAndJoinsRules() {
        val spec = SmartSpec(
            matchAll = true,
            rules = listOf(
                SmartRule(SmartField.GENRE, SmartOp.CONTAINS, "rock'; DROP TABLE songs;--"),
                SmartRule(SmartField.YEAR, SmartOp.BETWEEN, "1990..1999"),
                SmartRule(SmartField.STARRED, SmartOp.TRUE),
                SmartRule(SmartField.PLAYED, SmartOp.NEVER),
                SmartRule(SmartField.RATING, SmartOp.GT, "not-a-number"),   // invalid → ignored
            ),
            sort = "PLAYS", limit = 50,
        )
        val q = dev.slurpdrome.data.repo.SmartQuery.build(spec)
        val sql = q.sql
        assertFalse("value must not be inlined", sql.contains("DROP TABLE"))
        assertTrue(sql.contains("IFNULL(genre,'') LIKE ?"))
        assertTrue(sql.contains("year BETWEEN ? AND ?"))
        assertTrue(sql.contains("starred = 1"))
        assertTrue(sql.contains("lastPlayed = 0"))
        assertFalse(sql.contains("rating"))
        assertTrue(sql.contains(" AND "))
        assertTrue(sql.endsWith("ORDER BY playCount DESC LIMIT 50"))
        assertEquals(3, q.argCount)
    }

    @Test fun repairsCp1251ReadAsLatin1() {
        fun broken(s: String) = String(s.toByteArray(java.nio.charset.Charset.forName("windows-1251")), Charsets.ISO_8859_1)
        assertEquals("Они ротят жить", broken("Они ротят жить").fixMojibake())
        assertEquals("ПОРНОФИЛЬМЫ", broken("ПОРНОФИЛЬМЫ").fixMojibake())
        // Real Latin text and already-correct Cyrillic are left alone.
        for (ok in listOf("Björk", "Café del Mar", "Motörhead", "Sigur Rós", "Ægis", "Это пройдёт", "Rammstein")) {
            assertEquals(ok, ok.fixMojibake())
        }
    }
}
