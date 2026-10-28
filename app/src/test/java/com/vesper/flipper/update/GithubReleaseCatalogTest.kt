package com.vesper.flipper.update

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GithubReleaseCatalogTest {
    private val metadata = """{"schemaVersion":1,"repository":"psigho/V3SP3R","channel":"psigho-preview","packageName":"com.vesper.flipper","versionCode":4,"versionName":"1.0.3","minSdk":26,"signerSha256":["${"a".repeat(64)}"],"capabilities":["multi-provider-v1","exact-model-id-v1","safe-update-check-v1"],"apkAsset":"vesper.apk"}"""

    private fun release(body: String) = buildJsonObject {
        put("body", body)
        put("html_url", "https://github.com/psigho/V3SP3R/releases/tag/v1.0.3")
        put("draft", false)
        put("prerelease", false)
        put("assets", buildJsonArray {
            add(buildJsonObject {
                put("name", "vesper.apk")
                put("browser_download_url", "https://github.com/psigho/V3SP3R/releases/download/v1.0.3/vesper.apk")
            })
        })
    }

    @Test fun `missing metadata is ignored rather than guessing from a tag`() {
        val result = GithubReleaseCatalog.parse(buildJsonArray { add(release("v99.0 latest release")) }.toString(), "psigho/V3SP3R")
        assertTrue(result.releases.isEmpty())
        assertEquals(1, result.ignoredReleases)
    }

    @Test fun `quoted numeric version code is rejected`() {
        val bad = metadata.replace("\"versionCode\":4", "\"versionCode\":\"4\"")
        val result = GithubReleaseCatalog.parse(buildJsonArray { add(release("```vesper-update-v1\n$bad\n```")) }.toString(), "psigho/V3SP3R")
        assertTrue(result.releases.isEmpty())
        assertEquals(1, result.ignoredReleases)
    }

    @Test fun `leading zero numeric metadata is rejected`() {
        assertMalformedNumbersRejected { "0$it" }
    }

    @Test fun `numeric metadata with incomplete exponent is rejected`() {
        assertMalformedNumbersRejected { "${it}e" }
    }

    @Test fun `numeric metadata with incomplete positive exponent is rejected`() {
        assertMalformedNumbersRejected { "${it}e+" }
    }

    @Test fun `numeric metadata with incomplete negative exponent is rejected`() {
        assertMalformedNumbersRejected { "${it}e-" }
    }

    private fun assertMalformedNumbersRejected(malformed: (String) -> String) {
        val response = buildJsonArray {
            for ((key, value) in mapOf("schemaVersion" to "1", "versionCode" to "4", "minSdk" to "26")) {
                val bad = metadata.replace("\"$key\":$value", "\"$key\":${malformed(value)}")
                add(release("```vesper-update-v1\n$bad\n```"))
            }
        }
        val result = GithubReleaseCatalog.parse(response.toString(), "psigho/V3SP3R")
        assertTrue("Malformed numeric metadata was accepted: ${result.releases}", result.releases.isEmpty())
        assertEquals(3, result.ignoredReleases)
    }

    @Test fun `two metadata blocks are ambiguous and ignored`() {
        val block = "```vesper-update-v1\n$metadata\n```"
        val result = GithubReleaseCatalog.parse(buildJsonArray { add(release("$block\n$block")) }.toString(), "psigho/V3SP3R")
        assertTrue(result.releases.isEmpty())
    }

    @Test fun `APK link outside pinned repository is not accepted`() {
        val response = buildJsonArray { add(release("```vesper-update-v1\n$metadata\n```")) }.toString()
            .replace("https://github.com/psigho/V3SP3R/releases/download/", "https://github.com/another/repo/releases/download/")
        val result = GithubReleaseCatalog.parse(response, "psigho/V3SP3R")
        assertFalse(result.releases.single().hasApkAsset)
    }

    @Test fun `unknown schema does not become an update`() {
        val bad = metadata.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertTrue(GithubReleaseCatalog.parse(buildJsonArray { add(release("```vesper-update-v1\n$bad\n```")) }.toString(), "psigho/V3SP3R").releases.isEmpty())
    }

    @Test fun `missing draft declaration is rejected`() {
        val original = release("```vesper-update-v1\n$metadata\n```")
        val missing = JsonObject(original.filterKeys { it != "draft" })
        assertTrue(GithubReleaseCatalog.parse(buildJsonArray { add(missing) }.toString(), "psigho/V3SP3R").releases.isEmpty())
    }

    @Test fun `API error object is not a successful empty catalog`() {
        assertThrows(IllegalArgumentException::class.java) { GithubReleaseCatalog.parse("{\"message\":\"error\"}", "psigho/V3SP3R") }
    }

    @Test fun `explicit release metadata preserves Android version identity`() {
        val body = "Release notes\n```vesper-update-v1\n$metadata\n```"
        val result = GithubReleaseCatalog.parse(buildJsonArray { add(release(body)) }.toString(), "psigho/V3SP3R")
        assertEquals(1, result.releases.size)
        assertEquals(4L, result.releases.single().versionCode)
        assertEquals(26, result.releases.single().minSdk)
        assertEquals("psigho-preview", result.releases.single().channel)
        assertTrue(result.releases.single().hasApkAsset)
        assertEquals(0, result.ignoredReleases)
    }
}
