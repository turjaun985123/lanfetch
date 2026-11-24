package com.lanfetch.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PosterPickTest {

    private fun e(name: String, dir: Boolean = false) =
        DirEntry(name = name, url = "http://x/m/$name", isDir = dir)

    @Test fun prefersPosterNamedImage() {
        val entries = listOf(
            e("Movie.2024.mkv"), e("cover.jpg"), e("poster.jpg"), e("fanart.jpg"),
        )
        assertEquals("http://x/m/poster.jpg", Scraper.pickPoster(entries))
    }

    @Test fun fallsBackToFolderOrCoverThenAnyImage() {
        assertEquals("http://x/m/folder.jpg", Scraper.pickPoster(listOf(e("a.png"), e("folder.jpg"))))
        assertEquals("http://x/m/a.png", Scraper.pickPoster(listOf(e("a.png"), e("Movie.mkv"))))
    }

    @Test fun noImageNoPoster() {
        assertNull(Scraper.pickPoster(listOf(e("Movie.mkv"), e("Subs", dir = true))))
    }
}
