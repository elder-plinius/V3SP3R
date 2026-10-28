package com.vesper.flipper.update

import kotlinx.serialization.json.*
import java.net.URI

internal data class ParsedUpdateRelease(
    val repository: String, val channel: String, val packageName: String,
    val versionCode: Long, val minSdk: Int, val signerSha256: Set<String>,
    val capabilities: Set<String>, val versionName: String, val htmlUrl: String,
    val draft: Boolean, val prerelease: Boolean, val hasApkAsset: Boolean
)
internal data class ReleaseCatalog(val releases: List<ParsedUpdateRelease>, val ignoredReleases: Int)

internal object GithubReleaseCatalog {
    private const val MARKER = "```vesper-update-v1\n"

    fun parse(body: String, repository: String): ReleaseCatalog {
        require(body.length <= 1_048_576) { "Release response is too large." }
        val entries = Json.parseToJsonElement(body).jsonArray
        require(entries.size <= 100) { "Release response exceeds the supported page size." }
        var ignored = 0
        val releases = entries.mapNotNull { element ->
            try {
                val release = element.jsonObject
                val notes = release.text("body").replace("\r\n", "\n")
                val start = notes.indexOf(MARKER)
                require(start >= 0 && notes.indexOf(MARKER, start + 1) < 0)
                val end = notes.indexOf("```", start + MARKER.length)
                require(end > start)
                val metadata = Json.parseToJsonElement(notes.substring(start + MARKER.length, end)).jsonObject
                require(metadata.number("schemaVersion") == 1L)
                val apkName = metadata.text("apkAsset")
                require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,155}\\.apk").matches(apkName))
                val hasApk = release.getValue("assets").jsonArray.any { asset ->
                    val a = asset.jsonObject
                    a.text("name") == apkName && trustedAsset(a.text("browser_download_url"), repository, apkName)
                }
                ParsedUpdateRelease(
                    metadata.text("repository"), metadata.text("channel"), metadata.text("packageName"),
                    metadata.number("versionCode"), metadata.number("minSdk").also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt(),
                    metadata.strings("signerSha256"), metadata.strings("capabilities"),
                    metadata.text("versionName"), release.text("html_url"),
                    release.flag("draft"), release.flag("prerelease"), hasApk
                )
            } catch (_: IllegalArgumentException) {
                ignored++; null
            } catch (_: IllegalStateException) {
                ignored++; null
            } catch (_: NoSuchElementException) {
                ignored++; null
            }
        }
        return ReleaseCatalog(releases, ignored)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString && it.content.length <= if (key == "body") 100_000 else 1024)
        it.content
    }
    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
        require(!it.isString && Regex("[1-9][0-9]*").matches(it.content))
        requireNotNull(it.content.toLongOrNull())
    }
    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.let {
        require(!it.isString); requireNotNull(it.booleanOrNull)
    }
    private fun JsonObject.strings(key: String): Set<String> = getValue(key).jsonArray.let { values ->
        require(values.size <= 32)
        values.map { it.jsonPrimitive.let { value ->
            require(value.isString && value.content.length in 1..160); value.content
        } }.toSet()
    }
    private fun trustedAsset(value: String, repository: String, name: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawAuthority == "github.com" && uri.query == null && uri.fragment == null &&
            uri.normalize() == uri && uri.rawPath.startsWith("/$repository/releases/download/") &&
            uri.rawPath.endsWith("/$name") && !uri.rawPath.contains('%') && !uri.rawPath.contains('\\')
    } catch (_: Exception) { false }
}
