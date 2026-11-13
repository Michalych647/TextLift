package io.github.michalych647.textlift

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.entityextraction.EntityExtractionRemoteModel
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import java.util.Locale

/** Language models for ML Kit Entity Extraction, used to find postal addresses. */
object SmartModels {

    val languages: List<String> = listOf(
        EntityExtractorOptions.ARABIC, EntityExtractorOptions.CHINESE, EntityExtractorOptions.DUTCH,
        EntityExtractorOptions.ENGLISH, EntityExtractorOptions.FRENCH, EntityExtractorOptions.GERMAN,
        EntityExtractorOptions.ITALIAN, EntityExtractorOptions.JAPANESE, EntityExtractorOptions.KOREAN,
        EntityExtractorOptions.POLISH, EntityExtractorOptions.PORTUGUESE, EntityExtractorOptions.RUSSIAN,
        EntityExtractorOptions.SPANISH, EntityExtractorOptions.THAI, EntityExtractorOptions.TURKISH,
    )

    val busy: MutableSet<String> = HashSet()

    private val manager by lazy { RemoteModelManager.getInstance() }

    private fun model(code: String) = EntityExtractionRemoteModel.Builder(code).build()

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).let { it.getDisplayName(it) }
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun forTag(tag: String): String? {
        val base = tag.substringBefore('-').lowercase()
        return languages.firstOrNull { it == base }
    }

    suspend fun isInstalled(code: String): Boolean =
        runCatching { manager.isModelDownloaded(model(code)).await() }.getOrDefault(false)

    suspend fun installed(): Set<String> = languages.filterTo(HashSet()) { isInstalled(it) }

    suspend fun download(code: String) {
        manager.download(model(code), DownloadConditions.Builder().build()).await()
    }

    suspend fun delete(code: String) {
        manager.deleteDownloadedModel(model(code)).await()
    }
}
