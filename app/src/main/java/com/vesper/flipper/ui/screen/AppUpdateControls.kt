package com.vesper.flipper.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.vesper.flipper.BuildConfig
import com.vesper.flipper.update.GithubUpdateClient
import com.vesper.flipper.update.UpdatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Advisory release check only. No background polling, APK download, or installation. */
@Composable
internal fun AppUpdateControls() {
    val context = LocalContext.current.applicationContext
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val client = remember { GithubUpdateClient() }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var releaseUrl by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Update source: ${BuildConfig.UPDATE_REPOSITORY}", style = MaterialTheme.typography.bodySmall)
        Text("Channel: ${BuildConfig.UPDATE_CHANNEL} · build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodySmall)
        Text("Your build stays on this channel. An upstream PR merge does not switch it or replace your added features.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !checking, onClick = {
            checking = true
            message = null
            error = false
            releaseUrl = null
            scope.launch {
                try {
                    val installed = installedUpdateProfile(context)
                    val catalog = client.fetch(BuildConfig.UPDATE_REPOSITORY)
                    val releases = catalog.releases.map { r ->
                        UpdatePolicy.Release(r.repository, r.channel, r.packageName, r.versionCode, r.minSdk,
                            r.signerSha256, r.capabilities, r.versionName, r.htmlUrl, r.draft, r.prerelease, r.hasApkAsset)
                    }
                    val decision = UpdatePolicy.evaluate(installed, releases)
                    message = if (releases.isEmpty() && catalog.ignoredReleases > 0)
                        "Releases exist, but none contain usable update metadata. No update offered."
                    else decision.message + if (catalog.ignoredReleases > 0)
                        " ${catalog.ignoredReleases} release(s) without usable metadata were ignored." else ""
                    error = decision.status == UpdatePolicy.Status.INCOMPATIBLE || catalog.ignoredReleases > 0
                    releaseUrl = decision.releaseUrl.takeIf { decision.status == UpdatePolicy.Status.AVAILABLE }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = true
                    message = if (e is java.io.IOException) e.message else
                        "Could not verify the installed app or release metadata. No update offered."
                } finally {
                    checking = false
                }
            }
        }) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (checking) "Checking…" else "Check for updates")
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        releaseUrl?.let { url ->
            OutlinedButton(onClick = {
                try { browser.openUri(url) }
                catch (_: Exception) { error = true; message = "No browser is available to open the release." }
            }) { Text("View update release") }
        }
        Text("Only newer builds with matching declared identity, signing certificates and required features are offered. " +
            "Android still checks the downloaded APK signature. Never uninstall to resolve an update conflict.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Suppress("DEPRECATION")
private fun installedUpdateProfile(context: Context): UpdatePolicy.Installed {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(context.packageName, flags)
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    require(!signatures.isNullOrEmpty()) { "Installed signing identity unavailable." }
    val fingerprints = signatures.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }.toSet()
    return UpdatePolicy.Installed(BuildConfig.UPDATE_REPOSITORY, BuildConfig.UPDATE_CHANNEL,
        context.packageName, PackageInfoCompat.getLongVersionCode(info), Build.VERSION.SDK_INT,
        fingerprints, BuildConfig.UPDATE_CAPABILITIES.split(',').toSet())
}
