package com.electrowiz.silentalarm.ui.components

import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri
import kotlin.coroutines.cancellation.CancellationException

// ── Release Check State ────────────────────────────────────────────────────

private sealed class ReleaseStatus {
    data object Loading : ReleaseStatus()
    data class UpToDate(val version: String) : ReleaseStatus()
    data class UpdateAvailable(val current: String, val latest: String) : ReleaseStatus()
    data class Error(val message: String) : ReleaseStatus()
}

/**
 * Keeps the release check process-wide so recomposition or scrolling can't
 * trigger a second GitHub API call.
 */
private object ReleaseCheckManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingCheck: Deferred<ReleaseStatus>? = null

    @Synchronized
    fun checkOnce(fetch: () -> ReleaseStatus): Deferred<ReleaseStatus> {
        pendingCheck?.let { return it }
        return scope.async { fetch() }.also { pendingCheck = it }
    }
}

/**
 * GitHub repository card shown on the dashboard.
 * Auto-checks the latest GitHub release and indicates whether an update is available.
 */
@Composable
fun GitHubRepoCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    val repoUrl = "https://github.com/izumisagirii/silent-alarm"
    val apiUrl = "https://api.github.com/repos/izumisagirii/silent-alarm/releases/latest"

    val releaseCheck = remember {
        ReleaseCheckManager.checkOnce {
            fetchLatestRelease(ctx, apiUrl)
        }
    }
    var releaseStatus by remember { mutableStateOf<ReleaseStatus>(ReleaseStatus.Loading) }

    LaunchedEffect(releaseCheck) {
        releaseStatus = releaseCheck.await()
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Like github UI
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.github_logo_svgrepo_com),
                    contentDescription = stringResource(R.string.github),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.github_repo_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.github_repo_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (val s = releaseStatus) {
                is ReleaseStatus.Loading ->
                    Text(
                        stringResource(R.string.checking_updates),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                is ReleaseStatus.UpdateAvailable -> {
                    Text(
                        stringResource(R.string.update_available),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        stringResource(R.string.update_version_format, s.current, s.latest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is ReleaseStatus.UpToDate ->
                    Text(
                        stringResource(R.string.up_to_date_format, s.version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                is ReleaseStatus.Error ->
                    Text(
                        s.message,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val i = Intent(Intent.ACTION_VIEW, repoUrl.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                },
            ) {
                Text(stringResource(R.string.check_repo))
            }
        }
    }
}

// ── Network helper ──────────────────────────────────────────────────────────

private fun fetchLatestRelease(context: Context, apiUrl: String): ReleaseStatus {
    return try {
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.apply {
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "SilentAlarm-App/1.0")
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
        }

        val code = connection.responseCode
        if (code != 200) {
            connection.disconnect()
            return ReleaseStatus.Error(
                context.getString(R.string.api_error_format, code)
            )
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "")
        if (tagName.isBlank()) {
            return ReleaseStatus.Error(context.getString(R.string.no_release_tag))
        }

        val latestVersion = stripVersionPrefix(tagName)
        val currentVersion = getAppVersion(context)

        if (compareVersions(latestVersion, currentVersion) > 0) {
            ReleaseStatus.UpdateAvailable(current = currentVersion, latest = latestVersion)
        } else {
            ReleaseStatus.UpToDate(version = currentVersion)
        }

    } catch (e: java.net.UnknownHostException) {
        ReleaseStatus.Error(context.getString(R.string.network_error))
    } catch (e: java.net.SocketTimeoutException) {
        ReleaseStatus.Error(context.getString(R.string.timeout_error))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.printStackTrace()
        ReleaseStatus.Error(
            context.getString(R.string.update_error_format, e.message ?: "")
        )
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
