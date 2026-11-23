package dev.welles.notes.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppReleasesTest {
    private fun release(tag: String = "v7", asset: String = "notes-7.apk") = """
        {"tag_name": "$tag", "name": "Notes 7", "draft": false, "prerelease": false,
         "assets": [
           {"name": "other.txt", "browser_download_url": "https://github.com/x/other.txt", "size": 1},
           {"name": "$asset", "browser_download_url": "https://github.com/welles/notes-app/releases/download/$tag/$asset",
            "size": 12345, "digest": "sha256:ABCDEF01"}
         ]}
    """.trimIndent()

    @Test
    fun parsesVersionApkAndDigest() {
        assertEquals(
            AppRelease(7, "https://github.com/welles/notes-app/releases/download/v7/notes-7.apk", 12345, "abcdef01"),
            AppReleases.parse(release()),
        )
    }

    @Test
    fun ignoresReleasesWithoutMatchingApk() {
        assertNull(AppReleases.parse(release(asset = "notes-6.apk")))
        assertNull(AppReleases.parse(release(tag = "server-v7")))
        assertNull(AppReleases.parse(release().replace("\"prerelease\": false", "\"prerelease\": true")))
        assertNull(AppReleases.parse("<html>rate limited</html>"))
    }

    @Test
    fun digestIsOptional() {
        val text = release().replace(""", "digest": "sha256:ABCDEF01"""", "")
        assertNull(AppReleases.parse(text)?.sha256)
        assertEquals(7, AppReleases.parse(text)?.version)
    }
}
