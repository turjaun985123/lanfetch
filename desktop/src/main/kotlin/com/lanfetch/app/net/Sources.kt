package com.lanfetch.app.net

import com.lanfetch.app.data.Source

/**
 * Default servers seeded from the LanFlix reverse-engineering findings:
 * the DHAKA-FLIX HTTP directory servers (private ISP-network addresses —
 * reachable only from inside that ISP's network, like the original app).
 * Users can add any other directory server in the app.
 */
object SeedSources {
    val defaults: List<Source> = listOf(
        "DHAKA-FLIX-7" to "http://172.16.50.7/DHAKA-FLIX-7/",
        "DHAKA-FLIX-8" to "http://172.16.50.8/DHAKA-FLIX-8/",
        "DHAKA-FLIX-10" to "http://172.16.50.10/DHAKA-FLIX-10/",
        "DHAKA-FLIX-12" to "http://172.16.50.12/DHAKA-FLIX-12/",
        "DHAKA-FLIX-14" to "http://172.16.50.14/DHAKA-FLIX-14/",
    ).map { (name, url) -> Source(name = name, rootUrl = url) }
}
