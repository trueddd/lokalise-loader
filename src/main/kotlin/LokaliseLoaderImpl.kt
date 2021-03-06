import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import util.createBaseUrlBuilder
import util.leftJoin
import java.io.File

internal class LokaliseLoaderImpl(
    private val apiToken: String,
    private val projectId: String,
    private val outputDirPath: String,
    private val platforms: List<Platforms>,
    private val defaultLocale: String,
) : LokaliseLoader {

    private val okHttpClient by lazy { OkHttpClient() }

    private val gson by lazy { Gson() }

    private inline fun <reified T> Gson.parseData(raw: String): T? {
        return try {
            fromJson(raw, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadKeys(): List<Key> {
        val urlBuilder = createBaseUrlBuilder()
            .addPathSegment("projects")
            .addPathSegment(projectId)
            .addPathSegment("keys")
            .addQueryParameter("include_comments", "0")
            .addQueryParameter("include_screenshots", "0")
            .addQueryParameter("include_translations", "0")
            .addQueryParameter("filter_platforms", platforms.joinToString(",") { it.raw })
        val keysRequest = Request.Builder()
            .get()
            .addHeader("X-Api-Token", apiToken)
        val keys = mutableListOf<Key>()
        var page = 1
        while (true) {
            val call = okHttpClient.newCall(
                keysRequest
                    .url(
                        urlBuilder
                            .setQueryParameter("page", "$page")
                            .setQueryParameter("limit", "5000")
                            .build()
                    )
                    .build()
            ).execute()
            val pageKeys = if (call.isSuccessful) {
                call.body?.string()?.let { gson.parseData<KeysResponse>(it) }
            } else {
                System.err.println(call.message)
                null
            }
            when {
                pageKeys == null -> throw IllegalStateException("Something wrong happened while loading keys")
                pageKeys.keys.isEmpty() -> break
                else -> {
                    keys.addAll(pageKeys.keys)
                    page++
                }
            }
        }
        println("$TAG: Loaded ${keys.size} keys")
        return keys
    }

    private fun loadLanguages(): List<Language> {
        val url = createBaseUrlBuilder()
            .addPathSegment("projects")
            .addPathSegment(projectId)
            .addPathSegment("languages")
            .addQueryParameter("limit", "5000")
            .build()
        val request = Request.Builder()
            .get()
            .url(url)
            .addHeader("X-Api-Token", apiToken)
            .build()
        val call = okHttpClient.newCall(request).execute()
        val response = if (call.isSuccessful) {
            call.body?.string()?.let { gson.parseData<LanguageResponse>(it) }
        } else {
            System.err.println(call.message)
            null
        } ?: throw IllegalStateException("Something wrong happened while loading languages")
        println("$TAG: Loaded ${response.languages.size} languages")
        return response.languages
    }

    private fun loadTranslations(): List<Translation> {
        val urlBuilder = createBaseUrlBuilder()
            .addPathSegment("projects")
            .addPathSegment(projectId)
            .addPathSegment("translations")
        val translationsRequest = Request.Builder()
            .get()
            .addHeader("X-Api-Token", apiToken)
        val allTranslations = mutableListOf<Translation>()
        var page = 1
        while (true) {
            val call = okHttpClient.newCall(
                translationsRequest
                    .url(
                        urlBuilder
                            .setQueryParameter("page", "$page")
                            .setQueryParameter("limit", "5000")
                            .build()
                    )
                    .build()
            ).execute()
            val translations = if (call.isSuccessful) {
                call.body?.string()?.let { gson.parseData<TranslationsResponse>(it) }
            } else {
                System.err.println(call.message)
                null
            }
            when {
                translations == null -> throw IllegalStateException("Something wrong happened while loading translations")
                translations.translations.isEmpty() -> break
                else -> {
                    allTranslations.addAll(translations.translations)
                    page++
                }
            }
        }
        println("$TAG: Loaded ${allTranslations.size} translations")
        return allTranslations
    }

    private fun writeKeysToFile(file: File, translations: Map<Key, Translation?>) {
        val xmlFormattedKeys = translations.mapNotNull {
            val translation = it.value ?: return@mapNotNull null
            if (translation.value.startsWith('{') && translation.value.endsWith('}')) {
                val plurals = gson.parseData<Map<String, String>>(translation.value) ?: return@mapNotNull null
                buildString {
                    append("\n\t<plurals name=\"${it.key.keyName.android}\">")
                    plurals.forEach { (quantity, value) ->
                        val string = value.replace("\'", "\\\'")
                        append("\n\t\t<item quantity=\"$quantity\">$string</item>")
                    }
                    append("\n\t</plurals>")
                }
            } else {
                "\n\t<string name=\"${it.key.keyName.android}\">${translation.value}</string>"
            }
        }
        val xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        val result = buildString {
            append(xmlHeader)
            append("\n<resources>")
            xmlFormattedKeys.forEach {
                append(it.replace("\'", "\\\'"))
            }
            append("\n</resources>")
        }
        file.writeText(result)
    }

    override fun load() {
        val languages = loadLanguages()
        val keys = loadKeys()
        val translations = loadTranslations().groupBy { it.languageIso }
        val isoLangs = languages.map { it.langIso }
        isoLangs.forEach { isoLang ->
            val isoLangTrimmed = isoLang.substringBefore('_')
            val dirName = if (defaultLocale == isoLangTrimmed) "values" else "values-$isoLangTrimmed"
            val dir = File("$outputDirPath/$dirName")
            if (!dir.exists()) {
                dir.mkdir()
            }
            val file = File("$outputDirPath/$dirName/strings.xml")
            if (!file.exists()) {
                file.createNewFile()
            }
            val langTranslations = translations[isoLang] ?: return@forEach
            val langTranslationsWithKeys = keys.leftJoin(langTranslations, { it.keyId }, { it.keyId })
            writeKeysToFile(file, langTranslationsWithKeys)
        }
    }

    companion object {
        const val TAG = "LokaliseLoader"
    }
}