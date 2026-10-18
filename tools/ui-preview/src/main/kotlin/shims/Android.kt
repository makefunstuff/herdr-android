// The few android.* classes the app's shared code touches, as plain-JVM stand-ins for the desktop
// preview. Only what the app calls is here; a new call fails to compile until it is added.
@file:Suppress("unused", "UNUSED_PARAMETER")

package android.content

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One scene's app storage: a temp folder for files and in-memory preferences. */
open class Context(val root: File) {
    val filesDir: File get() = File(root, "files").apply { mkdirs() }
    val cacheDir: File get() = File(root, "cache").apply { mkdirs() }
    private val prefs = HashMap<String, SharedPreferences>()
    val contentResolver = ContentResolver()
    private val clipboard = ClipboardManager()

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(prefs) { prefs.getOrPut(name) { MemoryPreferences() } }
    fun getSystemService(name: String): Any? = if (name == CLIPBOARD_SERVICE) clipboard else null
    fun startActivity(intent: Intent) {}

    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
    }
}

class ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = null
    fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream? = OutputStream.nullOutputStream()
}

interface SharedPreferences {
    fun getString(key: String, default: String?): String?
    fun getFloat(key: String, default: Float): Float
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
    override fun getString(key: String, default: String?) = values[key] as? String ?: default
    override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    override fun contains(key: String) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = LinkedHashMap<String, Any?>()
        var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}

class ClipData private constructor(private val text: CharSequence?) {
    class Item(val text: CharSequence?)
    val itemCount: Int get() = if (text == null) 0 else 1
    fun getItemAt(index: Int) = Item(text)

    companion object {
        fun newPlainText(label: CharSequence?, text: CharSequence?) = ClipData(text)
    }
}

class ClipboardManager {
    var primaryClip: ClipData? = null
        private set
    fun setPrimaryClip(clip: ClipData) { primaryClip = clip }
}

class Intent(val action: String? = null, val data: Uri? = null) {
    fun addFlags(flags: Int) = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
