package com.makefunstuff.herdr.data

import android.content.Context
import com.makefunstuff.herdr.core.api.LayoutNode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A tab layout saved on the phone, as a template herdr can build anywhere. */
@Serializable
data class SavedLayout(val name: String, val root: LayoutNode)

/** Saved tab layouts, kept in the app's preferences so they survive restarts. */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("layouts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<SavedLayout> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SavedLayout>>(text) }.getOrDefault(emptyList())
    }

    /** Saves [root] under [name], replacing one with the same name. */
    fun save(name: String, root: LayoutNode): List<SavedLayout> =
        write(all().filterNot { it.name == name } + SavedLayout(name, root))

    fun remove(name: String): List<SavedLayout> = write(all().filterNot { it.name == name })

    private fun write(list: List<SavedLayout>): List<SavedLayout> {
        prefs.edit().putString(KEY, json.encodeToString(ListSerializer(SavedLayout.serializer()), list)).apply()
        return list
    }

    private companion object {
        const val KEY = "saved"
    }
}
