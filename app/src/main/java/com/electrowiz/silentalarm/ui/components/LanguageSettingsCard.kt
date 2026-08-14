package com.electrowiz.silentalarm.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.electrowiz.silentalarm.R

private data class LanguageOption(val tag: String, val displayName: String)

/*
 * Curated display names keep the language list readable and consistent on
 * every Android system locale.
 */
private val supportedLanguages = listOf(
    LanguageOption("en", "English"),
    LanguageOption("zh-CN", "简体中文"),
    LanguageOption("zh-TW", "繁體中文"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ja", "日本語"),
    LanguageOption("ko", "한국어"),
    LanguageOption("it", "Italiano"),
    LanguageOption("ar", "العربية"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("in", "Bahasa Indonesia"),
    LanguageOption("vi", "Tiếng Việt"),
    LanguageOption("tr", "Türkçe"),
    LanguageOption("pl", "Polski"),
    LanguageOption("nl", "Nederlands"),
    LanguageOption("th", "ไทย"),
    LanguageOption("uk", "Українська"),
    LanguageOption("sv", "Svenska"),
    LanguageOption("ms", "Bahasa Melayu"),
    LanguageOption("fil", "Filipino"),
    LanguageOption("cs", "Čeština"),
    LanguageOption("ro", "Română"),
    LanguageOption("hu", "Magyar"),
    LanguageOption("el", "Ελληνικά"),
    LanguageOption("iw", "עברית"),
    LanguageOption("da", "Dansk"),
    LanguageOption("nb", "Norsk"),
    LanguageOption("fi", "Suomi"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("ta", "தமிழ்"),
    LanguageOption("fa", "فارسی")
)

private fun displayNameFor(tag: String): String? {
    val normalized = tag.lowercase()
    val base = normalized.substringBefore('-').substringBefore('_')
    val languageTag = when {
        base == "id" -> "in"
        base == "he" -> "iw"
        base == "zh" -> if (normalized.contains("hant") || normalized.contains("tw")) {
            "zh-TW"
        } else {
            "zh-CN"
        }
        else -> base
    }
    return supportedLanguages.find { it.tag == languageTag }?.displayName
}

private fun applyLanguage(tag: String) {
    val locales = if (tag.isEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

/**
 * Language selector card. Opens an MD3 bottom sheet with search and a
 * scrollable list so a large number of supported languages stays manageable.
 */
@Composable
fun LanguageSettingsCard(modifier: Modifier = Modifier) {
    val followSystemLabel = stringResource(R.string.follow_system)
    val searchLabel = stringResource(R.string.search_languages)
    val noResultsLabel = stringResource(R.string.no_languages_found)
    val currentTag = runCatching {
        AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
    }.getOrNull() ?: ""
    val selectedLabel = displayNameFor(currentTag) ?: followSystemLabel
    var showSheet by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsCardHeader(
                icon = Icons.Outlined.Translate,
                title = stringResource(R.string.language_title)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    showSheet = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedLabel,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }

    if (showSheet) {
        SearchableSelectSheet(
            title = stringResource(R.string.language_title),
            searchPlaceholder = searchLabel,
            noResultsText = noResultsLabel,
            items = listOf(SelectOption("", followSystemLabel)) +
                supportedLanguages.map { SelectOption(it.tag, it.displayName) },
            selectedId = currentTag,
            onSelect = { tag ->
                showSheet = false
                applyLanguage(tag)
            },
            onDismiss = { showSheet = false }
        )
    }
}
