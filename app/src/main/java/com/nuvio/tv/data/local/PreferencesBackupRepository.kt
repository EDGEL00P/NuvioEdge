package com.nuvio.tv.data.local

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class PreferencesBackupPreview(val content: String, val settingCount: Int)

@Singleton
class PreferencesBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private enum class Kind { TEXT, BOOLEAN, TEXT_SET, EPISODE_THRESHOLD }
    private val restoreMutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val allowedTextValues = mapOf(
        ("theme_settings" to "selected_theme") to com.nuvio.tv.domain.model.AppTheme.entries.map { it.name }.toSet(),
        ("theme_settings" to "selected_font") to com.nuvio.tv.domain.model.AppFont.entries.map { it.name }.toSet(),
        ("theme_settings" to "settings_ui_style") to com.nuvio.tv.domain.model.SettingsUiStyle.entries.map { it.name }.toSet(),
        ("layout_settings" to "selected_layout") to com.nuvio.tv.domain.model.HomeLayout.entries.map { it.name }.toSet(),
        ("player_settings" to "internal_player_engine") to InternalPlayerEngine.entries.map { it.name }.toSet(),
        ("live_tv_settings" to "start_screen") to LiveTvStartScreen.entries.map { it.name }.toSet(),
        ("live_tv_settings" to "channel_sort") to LiveTvChannelSort.entries.map { it.name }.toSet()
    )
    private val schema = mapOf(
        "theme_settings" to mapOf(
            "selected_theme" to Kind.TEXT,
            "selected_font" to Kind.TEXT,
            "settings_ui_style" to Kind.TEXT,
            "custom_theme_colors" to Kind.TEXT,
            "amoled_mode" to Kind.BOOLEAN,
            "amoled_surfaces_mode" to Kind.BOOLEAN
        ),
        "layout_settings" to mapOf(
            "selected_layout" to Kind.TEXT,
            "sidebar_collapsed_by_default" to Kind.BOOLEAN,
            "modern_sidebar_enabled" to Kind.BOOLEAN,
            "modern_sidebar_blur_enabled" to Kind.BOOLEAN,
            "modern_landscape_posters_enabled" to Kind.BOOLEAN,
            "hero_section_enabled" to Kind.BOOLEAN,
            "poster_labels_enabled" to Kind.BOOLEAN,
            "catalog_addon_name_enabled" to Kind.BOOLEAN,
            "catalog_type_suffix_enabled" to Kind.BOOLEAN
        ),
        "player_settings" to mapOf(
            "player_preference" to Kind.TEXT,
            "internal_player_engine" to Kind.TEXT,
            "auto_switch_internal_player_on_error" to Kind.BOOLEAN,
            "use_libass" to Kind.BOOLEAN,
            "libass_render_type" to Kind.TEXT,
            "stream_auto_play_next_episode_enabled" to Kind.BOOLEAN,
            "stream_auto_play_next_episode_fallback_enabled" to Kind.BOOLEAN,
            "still_watching_enabled" to Kind.BOOLEAN,
            "still_watching_episode_threshold" to Kind.EPISODE_THRESHOLD
        ),
        "live_tv_settings" to mapOf(
            "auto_refresh_while_visible" to Kind.BOOLEAN,
            "start_screen" to Kind.TEXT,
            "show_channel_numbers" to Kind.BOOLEAN,
            "channel_sort" to Kind.TEXT,
            "wrap_zap" to Kind.BOOLEAN,
            "osd_after_tune" to Kind.BOOLEAN
        ),
        "sports_ticker" to mapOf(
            "ticker_enabled" to Kind.BOOLEAN,
            "hide_scores_v1" to Kind.BOOLEAN,
            "followed_leagues_v1" to Kind.TEXT_SET,
            "followed_teams_v1" to Kind.TEXT_SET
        )
    )

    fun currentProfileId(): Int = profileManager.activeProfileId.value

    suspend fun exportTo(uri: Uri, profileId: Int) = withContext(Dispatchers.IO) {
        require(profileManager.profiles.value.any { it.id == profileId })
        val features = buildJsonObject {
            schema.forEach { (feature, fields) ->
                val values = factory.get(profileId, feature).data.first().asMap().mapKeys { it.key.name }
                put(feature, buildJsonObject {
                    fields.forEach { (name, kind) -> encode(values[name], kind)?.let { put(name, it) } }
                })
            }
        }
        val document = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("features", features)
        }.toString().toByteArray(Charsets.UTF_8)
        require(document.size <= MAX_BYTES)
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("Backup destination unavailable")
        output.use { it.write(document) }
    }

    suspend fun preview(uri: Uri): PreferencesBackupPreview = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Backup file unavailable")
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
        }
        val content = output.toString(Charsets.UTF_8.name())
        val validated = validate(content)
        PreferencesBackupPreview(content, validated.values.sumOf { it.size })
    }

    suspend fun restore(preview: PreferencesBackupPreview, profileId: Int) = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            require(profileManager.profiles.value.any { it.id == profileId })
            val features = validate(preview.content)
            val originals = features.mapValues { (feature, fields) ->
                val values = factory.get(profileId, feature).data.first().asMap().mapKeys { it.key.name }
                fields.mapValues { (name, _) -> encode(values[name], schema.getValue(feature).getValue(name)) }
            }
            val applied = ArrayList<String>()
            try {
                features.forEach { (feature, fields) ->
                    applied += feature
                    factory.get(profileId, feature).edit { prefs ->
                        fields.forEach { (name, value) -> apply(prefs, name, schema.getValue(feature).getValue(name), value) }
                    }
                }
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    applied.asReversed().forEach { feature ->
                        try {
                            factory.get(profileId, feature).edit { prefs ->
                                originals.getValue(feature).forEach { (name, value) ->
                                    apply(prefs, name, schema.getValue(feature).getValue(name), value)
                                }
                            }
                        } catch (rollbackFailure: Exception) {
                            failure.addSuppressed(rollbackFailure)
                        }
                    }
                }
                throw failure
            }
        }
    }

    private fun validate(content: String): Map<String, Map<String, JsonElement>> {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = json.parseToJsonElement(content) as? JsonObject ?: error("Invalid backup")
        require((root["format"] as? JsonPrimitive)?.content == FORMAT)
        require((root["version"] as? JsonPrimitive)?.intOrNull == VERSION)
        val features = root["features"] as? JsonObject ?: error("Invalid backup")
        val accepted = linkedMapOf<String, Map<String, JsonElement>>()
        features.forEach { (feature, document) ->
            val fields = schema[feature] ?: return@forEach
            val incoming = document as? JsonObject ?: error("Invalid feature")
            val values = incoming.filterKeys { it in fields }
            values.forEach { (name, value) ->
                val valid = when (fields.getValue(name)) {
                    Kind.TEXT -> value is JsonPrimitive && value.isString && value.content.length <= 4096
                    Kind.BOOLEAN -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                    Kind.EPISODE_THRESHOLD -> value is JsonPrimitive && !value.isString &&
                        value.intOrNull?.let { it in PlayerSettings.MIN_STILL_WATCHING_EPISODE_THRESHOLD..PlayerSettings.MAX_STILL_WATCHING_EPISODE_THRESHOLD } == true
                    Kind.TEXT_SET -> value is JsonArray && value.size <= 1000 && value.all {
                        it is JsonPrimitive && it.isString && it.content.length <= 512
                    }
                }
                require(valid)
                if (fields.getValue(name) == Kind.TEXT) {
                    val text = (value as JsonPrimitive).content
                    allowedTextValues[feature to name]?.let { require(text in it) }
                    if (feature == "theme_settings" && name == "custom_theme_colors") {
                        val colors = text.split(',')
                        require(colors.size == 3 && colors.all { com.nuvio.tv.domain.model.parseHexColor(it) != null })
                    }
                }
            }
            accepted[feature] = values
        }
        require(accepted.isNotEmpty())
        return accepted
    }

    private fun encode(value: Any?, kind: Kind): JsonElement? = when (kind) {
        Kind.TEXT -> (value as? String)?.takeIf { it.length <= 4096 }?.let(::JsonPrimitive)
        Kind.BOOLEAN -> (value as? Boolean)?.let(::JsonPrimitive)
        Kind.EPISODE_THRESHOLD -> (value as? Int)
            ?.takeIf { it in PlayerSettings.MIN_STILL_WATCHING_EPISODE_THRESHOLD..PlayerSettings.MAX_STILL_WATCHING_EPISODE_THRESHOLD }
            ?.let(::JsonPrimitive)
        Kind.TEXT_SET -> (value as? Set<*>)?.takeIf { values ->
            values.size <= 1000 && values.all { it is String && it.length <= 512 }
        }?.let { values -> JsonArray(values.map { JsonPrimitive(it as String) }) }
    }

    private fun apply(prefs: MutablePreferences, name: String, kind: Kind, value: JsonElement?) {
        when (kind) {
            Kind.TEXT -> {
                val key = stringPreferencesKey(name)
                if (value == null) prefs.remove(key) else prefs[key] = (value as JsonPrimitive).content
            }
            Kind.BOOLEAN -> {
                val key = booleanPreferencesKey(name)
                if (value == null) prefs.remove(key) else prefs[key] = (value as JsonPrimitive).booleanOrNull!!
            }
            Kind.EPISODE_THRESHOLD -> {
                val key = intPreferencesKey(name)
                if (value == null) prefs.remove(key) else prefs[key] = (value as JsonPrimitive).intOrNull!!
            }
            Kind.TEXT_SET -> {
                val key = stringSetPreferencesKey(name)
                if (value == null) prefs.remove(key) else prefs[key] = (value as JsonArray).map { (it as JsonPrimitive).content }.toSet()
            }
        }
    }

    private companion object {
        const val FORMAT = "nuvioedge.preferences"
        const val VERSION = 1
        const val MAX_BYTES = 512 * 1024
    }
}