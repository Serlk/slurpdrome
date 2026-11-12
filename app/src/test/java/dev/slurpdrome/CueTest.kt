package dev.slurpdrome

import dev.slurpdrome.data.source.CueSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CueTest {
    @Test fun readsCueEmbeddedByTheServerScript() {
        val stream = javaClass.classLoader!!.getResourceAsStream("cue_test.flac")
        val album = CueSheet.fromFlac(stream, totalMs = 6000)
        assertNotNull(album); album!!
        assertEquals("Тестовый альбом", album.title)
        assertEquals("Ария", album.performer)
        assertEquals(1999, album.year)
        assertEquals(listOf("Первая", "Вторая"), album.tracks.map { it.title })
        assertEquals(listOf(0L to 3000L, 3000L to 6000L), album.tracks.map { it.startMs to it.endMs })
    }

    @Test fun parsesFramesAt75PerSecond() {
        val a = CueSheet.parseText("TRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 01:02:37\n", 120_000)!!
        assertEquals(62_493L, a.tracks[1].startMs)   // 1:02 + 37/75 s
    }
}
