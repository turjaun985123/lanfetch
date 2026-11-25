package com.lanfetch.app.download

import com.lanfetch.app.data.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSinkUtilTest {

    @Test fun sanitizeKeepExtStripsIllegalCharsAndPreservesLongExtensions() {
        assertEquals("download.bin", sanitizeKeepExt("   "))
        assertEquals("Movie_Name_1080p_.mkv", sanitizeKeepExt("Movie:Name?1080p|.mkv"))

        val longName = "Very.Long.Release.Title." + "X".repeat(130) + ".2160p.WEB-DL.mkv"
        val sanitized = sanitizeKeepExt(longName)
        assertTrue(sanitized.length <= 120)
        assertTrue(sanitized.endsWith(".mkv"))
    }

    @Test fun legacyPartFileNameUsesHiddenDotPrefix() {
        assertEquals(".lanfetch_42_sample.mkv.part", LegacySink.partFileName(42L, "sample.mkv"))
    }

    @Test fun downloadUiComputesFractionAndEtaSafely() {
        val unknown = DownloadUi(
            id = 1L,
            title = "Test",
            fileName = "test.mkv",
            url = "http://172.16.50.10/test.mkv",
            totalBytes = 0L,
            downloadedBytes = 100L,
            state = DownloadState.RUNNING,
            speedBps = 0.0,
        )
        assertEquals(0f, unknown.fraction, 0.0001f)
        assertEquals(-1L, unknown.etaSeconds)

        val active = unknown.copy(
            totalBytes = 1_000L,
            downloadedBytes = 250L,
            speedBps = 250.0,
        )
        assertEquals(0.25f, active.fraction, 0.0001f)
        assertEquals(3L, active.etaSeconds)
    }

    @Test fun coordinatorMimeResolutionCoversCommonVideoContainers() {
        assertEquals("video/mp4", DownloadCoordinator.mimeFor("clip.MP4"))
        assertEquals("video/x-matroska", DownloadCoordinator.mimeFor("film.mkv"))
        assertEquals("video/webm", DownloadCoordinator.mimeFor("sample.webm"))
        assertEquals("video/mp2t", DownloadCoordinator.mimeFor("stream.ts"))
        assertEquals("video/*", DownloadCoordinator.mimeFor("archive.iso"))
    }
}
