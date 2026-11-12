package dev.slurpdrome

import dev.slurpdrome.data.source.Deduper.Companion.cleanEdition
import dev.slurpdrome.data.source.Deduper.Companion.norm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupTest {
    private fun key(artist: String, album: String) = norm(artist) + "|" + norm(cleanEdition(album))

    @Test fun editionsAndSpellingMerge() {
        assertEquals(key("Björk", "Homogenic"), key("bjork", "Homogenic (Remastered 2015)"))
        assertEquals(key("Ёлка", "Город обмана"), key("Елка", "Город Обмана [Deluxe Edition]"))
        assertEquals(key("The Prodigy", "Fat of the Land"), key("Prodigy", "Fat Of The Land (20th Anniversary Edition)"))
        assertEquals(norm("AC/DC"), norm("AC DC"))
        assertEquals(norm("Simon & Garfunkel"), norm("Simon and Garfunkel"))
    }

    @Test fun differentVersionsStayApart() {
        assertNotEquals(norm(cleanEdition("Song")), norm(cleanEdition("Song (Live)")))
        assertNotEquals(norm(cleanEdition("Song")), norm(cleanEdition("Song (Acoustic Version)")))
        assertNotEquals(key("Artist", "Album"), key("Artist", "Album II"))
    }
}
