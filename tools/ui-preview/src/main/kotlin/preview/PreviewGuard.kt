package preview

import com.makefunstuff.herdr.data.Unlock
import kotlinx.coroutines.awaitCancellation

/**
 * The phone's fingerprint in the previews: whether it has a screen lock to ask with ([SceneData.screenLock]),
 * and what the prompt answers. Android's own prompt is a system window the harness can't draw; [asked] keeps
 * what it would have asked, and [answer] null leaves it up (the app lock's scene).
 */
object PreviewUnlock : Unlock {
    @Volatile var screenLock = true
    @Volatile var answer: Boolean? = true
    val asked = mutableListOf<String>()

    override fun canAsk() = screenLock

    override suspend fun ask(title: String, subtitle: String?): Boolean {
        synchronized(asked) { asked += title }
        return answer ?: awaitCancellation()
    }

    override fun openSettings() {}
}
