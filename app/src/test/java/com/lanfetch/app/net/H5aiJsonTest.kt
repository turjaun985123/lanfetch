package com.lanfetch.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H5aiJsonTest {

    @Test fun parsesRealDhakaFlixSearchPayload() {
        val raw = """
            {"search":[{"href":"\/DHAKA-FLIX-14\/English%20Movies%20%281080p%29\/%282024%29%201080p\/Dune-Part%20Two%20%282024%29%201080p%20%5BDual%20Audio%5D\/","time":1745578906000,"size":null,"managed":true,"fetched":false},{"href":"\/DHAKA-FLIX-14\/English%20Movies%20%281080p%29\/%282024%29%201080p\/Dune-Part%20Two%20%282024%29%201080p%20%5BDual%20Audio%5D\/Dune%20Part%20Two.mkv","time":1745578983000,"size":3059623948}]}
        """.trimIndent()
        val items = H5aiJson.parseSearch(raw)
        assertNotNull(items)
        assertEquals(2, items!!.size)
        assertTrue(items[0].isDir)
        assertEquals("/DHAKA-FLIX-14/English%20Movies%20%281080p%29/%282024%29%201080p/Dune-Part%20Two%20%282024%29%201080p%20%5BDual%20Audio%5D/", items[0].href)
        assertEquals(-1L, items[0].sizeBytes)
        assertEquals(3_059_623_948L, items[1].sizeBytes)
        assertEquals(1_745_578_983_000L, items[1].timeMillis)
    }

    @Test fun returnsNullForNonJsonHtml() {
        assertNull(H5aiJson.parseSearch("<html><body>hello</body></html>"))
        assertNull(H5aiJson.parseItems("{}"))
    }

    @Test fun escapesQuotesInSearchRequest() {
        val req = H5aiJson.buildSearchRequest("/DHAKA-FLIX-14/", "spider\"man")
        assertTrue(req.contains("\"spider\\\"man\""))
    }

    @Test fun rejectsExcessiveNestingWithoutThrowing() {
        val nested = "[".repeat(40) + "0" + "]".repeat(40)
        assertNull(H5aiJson.parseSearch("{\"nested\":$nested,\"search\":[]}"))
    }

    @Test fun rejectsOversizedPayload() {
        val oversized = "{\"search\":[],\"padding\":\"${"x".repeat(2_000_001)}\"}"
        assertNull(H5aiJson.parseSearch(oversized))
    }

    @Test fun escapesAllJsonControlCharacters() {
        val req = H5aiJson.buildSearchRequest("/", "a\u0001b")
        assertTrue(req.contains("a\\u0001b"))
    }
}
