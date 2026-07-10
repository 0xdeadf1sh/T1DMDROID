package com.t1dm.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Read-only "About" panel (Phase 7C — item 18): app identity, version/build, the
 * GPL-3.0 licence, the loaded model's provenance (id + descriptor arch + ExecuTorch version), the git
 * SHA, and internal build info. All copy is PUBLIC-SAFE — no tokens, no keys, no host-internal paths.
 */
data class AboutInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val applicationId: String,
    val flavor: String,
    val buildType: String,
    val gitSha: String,
    val license: String,
    val modelId: String?,
    val modelArchVersion: String?,
    val modelParamCount: Long?,
    val executorchVersion: String?,
    val nativeCoreStatus: String,
)

@Composable
fun AboutScreen(info: AboutInfo) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(info.appName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "${info.versionName} (build ${info.versionCode})",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )

        Section("Build")
        Kv("Package", info.applicationId)
        Kv("Flavor / type", "${info.flavor} · ${info.buildType}")
        Kv("Git", info.gitSha)

        Section("Source")
        val uriHandler = LocalUriHandler.current
        val repoUrl = "https://github.com/0xdeadf1sh/T1DMDROID"
        Text(
            repoUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { uriHandler.openUri(repoUrl) }
                .padding(vertical = 4.dp),
        )

        Section("Model")
        Kv("Model id", info.modelId ?: "none loaded")
        Kv("Descriptor arch", info.modelArchVersion ?: "n/a")
        Kv("Parameters", info.modelParamCount?.let { "%,d".format(it) } ?: "n/a")
        Kv("ExecuTorch", info.executorchVersion ?: "n/a")
        Kv("Native core", info.nativeCoreStatus)

        Section("License")
        Text(
            info.license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun Kv(k: String, v: String) {
    com.t1dm.core.design.KeyValueRow(k, v, numeric = false)
}
