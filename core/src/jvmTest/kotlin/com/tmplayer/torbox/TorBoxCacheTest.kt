package com.tmplayer.torbox

import kotlin.test.*

class TorBoxCacheTest {
    private val source = TorBoxSource.Usenet
    private val page = TorBoxPage(listOf(
        TorBoxFile(7, 0, "Example.S01E01.1080p.mkv", 5000, "2026-11-01", true, source),
        TorBoxFile(7, 1, "Example.S01E02.1080p.mkv", 6000, "2026-11-01", true, source),
    ), 100)

    @Test fun aSnapshotPayloadRetainsFileIdentityAndContinuation() {
        val text = TorBoxApi.cachePage(page, "owner", source)
        assertEquals(page, TorBoxApi.cachedPage(text, "owner", source))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("api_key"))
    }

    @Test fun transferNameAndFileMetadataSurviveSynchronization() {
        val raw = """{"data":[{"id":7,"name":"Example.2022.S01.1080p","download_finished":true,"files":[
          {"id":0,"name":"S01E01.mkv","size":5000,"tmdb_id":42,"media_type":"tv"}
        ]}]}"""
        val library = TorBoxApi.parsePage(com.tmplayer.json.JSONObject(raw), 0, source)!!
        val snapshot = TorBoxApi.cachePage(library, "owner", source)
        val restored = TorBoxApi.cachedPage(snapshot, "owner", source)!!.files.single()
        assertEquals("Example.2022.S01.1080p", restored.transferName)
        val query = restored.toMediaItem().metaQuery!!
        assertEquals("Example", query.title)
        assertEquals(42, query.tmdbId)
        assertEquals(com.tmplayer.online.MetaKind.Show, query.explicitKind)
        assertEquals(1, query.episode)
    }

    @Test fun aCacheFromAnotherAccountOrProviderIsNeverShown() {
        val text = TorBoxApi.cachePage(page, "owner", source)
        assertNull(TorBoxApi.cachedPage(text, "another-owner", source))
        assertNull(TorBoxApi.cachedPage(text, "owner", TorBoxSource.Web))
        assertNull(TorBoxApi.cachedPage(text.replace("\"version\":1", "\"version\":99"), "owner", source))
    }

    @Test fun malformedContinuationAndTruncatedSnapshotsAreRejected() {
        val text = TorBoxApi.cachePage(page, "owner", source)
        assertNull(TorBoxApi.cachedPage(text.dropLast(5), "owner", source))
        assertNull(TorBoxApi.cachedPage(text.replace("\"next_offset\":100", "\"next_offset\":123"), "owner", source))
        assertNull(TorBoxApi.cachedPage(text.replace("\"data\":[", "\"data\":{\"bad\":["), "owner", source))
    }
}
