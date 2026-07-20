package com.example.silentalarm.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.silentalarm.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri
import kotlin.coroutines.cancellation.CancellationException

// ── Release Check State ────────────────────────────────────────────────────

private sealed class ReleaseStatus {
    data object Idle : ReleaseStatus()
    data object Loading : ReleaseStatus()
    data class UpToDate(val version: String) : ReleaseStatus()
    data class UpdateAvailable(val current: String, val latest: String) : ReleaseStatus()
    data class Error(val message: String) : ReleaseStatus()
}

/**
 * GitHub repository card shown on the dashboard.
 * Auto-checks the latest GitHub release and indicates whether an update is available.
 */
@Composable
fun GitHubRepoCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repoUrl = "https://github.com/izumisagirii/silent-alarm"
    val apiUrl = "https://api.github.com/repos/izumisagirii/silent-alarm/releases/latest"

    var releaseStatus by remember { mutableStateOf<ReleaseStatus>(ReleaseStatus.Idle) }

    // Auto-check latest release on first composition
    LaunchedEffect(Unit) {
        releaseStatus = ReleaseStatus.Loading
        releaseStatus = withContext(Dispatchers.IO) {
            fetchLatestRelease(ctx, apiUrl)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Like github UI
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.github_logo_svgrepo_com),
                    contentDescription = "GitHub",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Silent Alarm", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "izumisagirii/silent-alarm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // check update
            when (val s = releaseStatus) {
                is ReleaseStatus.Loading ->
                    Text(
                        "Checking for updates…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                is ReleaseStatus.UpdateAvailable -> {
                    Text(
                        "A new version is available!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "v${s.current}  →  v${s.latest}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is ReleaseStatus.UpToDate ->
                    Text(
                        "Already up to date  (v${s.version}), please add a star!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                is ReleaseStatus.Error ->
                    Text(
                        s.message,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                is ReleaseStatus.Idle -> { /* nothing until LaunchedEffect fires */ }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val i = Intent(Intent.ACTION_VIEW, repoUrl.toUri())
                        ctx.startActivity(i)
                    },
                ) {
                    Text("Check Repo")
                }
            }
        }
    }
}

// ── Network helper ──────────────────────────────────────────────────────────

private fun fetchLatestRelease(context: Context, apiUrl: String): ReleaseStatus {
    return try {
        // 将整个网络请求的配置和执行都放在 IO 线程中
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.apply {
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "SilentAlarm-App/1.0")
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
        }

        // get code
        val code = connection.responseCode
        if (code != 200) {
            connection.disconnect()
            return ReleaseStatus.Error("GitHub API error ($code)")
        }

        // read data
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "")
        if (tagName.isBlank()) {
            return ReleaseStatus.Error("No release tag found")
        }

        val latestVersion = stripVersionPrefix(tagName)
        val currentVersion = getAppVersion(context)

        if (compareVersions(latestVersion, currentVersion) > 0) {
            ReleaseStatus.UpdateAvailable(current = currentVersion, latest = latestVersion)
        } else {
            ReleaseStatus.UpToDate(version = currentVersion)
        }

    } catch (e: java.net.UnknownHostException) {
        ReleaseStatus.Error("No network connection")
    } catch (e: java.net.SocketTimeoutException) {
        ReleaseStatus.Error("Request timed out")
    } catch (e: CancellationException) {
        // 向上抛出异常
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        ReleaseStatus.Error("Unable to check updates: ${e.localizedMessage}")
    }
}
// ── Version helpers ─────────────────────────────────────────────────────────

/** Remove a leading "v" or "V" from a tag name. */
private fun stripVersionPrefix(tag: String): String =
    tag.trimStart().removePrefix("v").removePrefix("V")

/** Read the current app version from the package manager. */
private fun getAppVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: "0.0"

/**
 * Compare two dot-separated version strings.
 * Returns >0 if [a] is newer, <0 if [b] is newer, 0 if equal.
 * Handles different segment counts (e.g. 1.0 vs 1.0.0).
 */
private fun compareVersions(a: String, b: String): Int {
    val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
    val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(aParts.size, bParts.size)
    for (i in 0 until maxLen) {
        val av = aParts.getOrElse(i) { 0 }
        val bv = bParts.getOrElse(i) { 0 }
        if (av != bv) return av - bv
    }
    return 0
}
