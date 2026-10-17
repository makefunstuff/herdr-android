// Renders the app's real screens (app/ and core/, compiled for the desktop) to PNGs, without an
// Android SDK or an emulator. The app's real HostSession, HerdrApi and board client talk to
// FakeMachine, and scenes move through the app by its routes and by tapping what is on screen.
package preview

import com.makefunstuff.herdr.push.PushState
import com.makefunstuff.herdr.core.push.Alert
import com.makefunstuff.herdr.core.push.PhoneQuiet
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.HerdrRoot
import com.makefunstuff.herdr.core.host.HostStatus
import com.makefunstuff.herdr.core.ssh.Machine
import com.makefunstuff.herdr.data.IdentityStore
import com.makefunstuff.herdr.ui.design.Toasts
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO

/** Pixels per dp: the shots are 2× (a 412×892 dp phone is 824×1784 px). */
const val SCALE = 2f

class Scene(
    val name: String,
    /** Where the app is when the scene's [actions] start (`Routes.…`); null stays on Agents. */
    val route: String? = null,
    val widthDp: Int = 412,
    val heightDp: Int = 892,
    val fontScale: Float = 1f,
    /** The app's SSH key; [IdentityStore.State.Missing] opens the first-run key dialog. */
    val key: IdentityStore.State = IdentityStore.State.Ready(Demo.PUBLIC_KEY),
    /** Changes the demo machines before the app starts. */
    val setup: SceneData.() -> Unit = {},
    /** Taps and types on the running app. */
    val actions: ComposeUiTest.(SceneContext) -> Unit = {},
)

/** What the app finds when it starts: the saved machines and what each answers. */
class SceneData {
    var machines: List<Machine> = Demo.machines()
    val hosts: MutableMap<String, FakeMachine> = Demo.hosts().toMutableMap()
    /** Chat drafts saved before the app opens, by DraftStore key. */
    val drafts = HashMap<String, String>()
    /** Notifications while closed, as saved before the app opens (PushStore). */
    var push: PushState? = null
    /** The phone's push apps (packages) and whether it lets the app notify. */
    var pushApps: List<String> = listOf("io.heckel.ntfy")
    var notificationsAllowed: Boolean = true
    /** Whether Android shows Live Updates as such (null: before Android 16). */
    var liveAllowed: Boolean? = true
    /** Do Not Disturb (or Bedtime) or car mode on the phone, for quiet hours. */
    var quietMode: PhoneQuiet? = null
    /** Notifications held during quiet hours, as saved before the app opens (Inbox). */
    var held: List<Alert> = emptyList()
    fun host(id: String): FakeMachine = hosts.getValue(id)
}

class SceneContext(val container: AppContainer, val nav: NavHostController, val data: SceneData) {
    /** Makes the app read [machineId]'s snapshot again now, as an event from herdr would. */
    fun refresh(machineId: String) = container.hosts.session(machineId)?.refresh()
}

fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/shots" }).apply { mkdirs() }
    val only = args.getOrElse(1) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val all = scenes()
    val names = all.map { it.name }
    val unknown = only.filter { o -> names.none { it == o || it == o.removeSuffix("-light") } }
    if (unknown.isNotEmpty()) {
        System.err.println("No scene called ${unknown.joinToString()}. Scenes: ${names.joinToString()}")
        kotlin.system.exitProcess(2)
    }
    var failed = 0
    for (scene in all) for (dark in listOf(true, false)) {
        val name = if (dark) scene.name else scene.name + "-light"
        if (only.isNotEmpty() && name !in only && scene.name !in only) continue
        val file = File(out, "$name.png")
        val started = System.currentTimeMillis()
        runCatching { render(scene, dark, file) }
            .onSuccess { println("ok   $name  (${System.currentTimeMillis() - started} ms)") }
            .onFailure { failed++; println("FAIL $name: $it"); it.printStackTrace() }
    }
    println("Shots in ${out.absolutePath}")
    // Leftover coroutines and the fake machines' threads would keep the JVM alive.
    kotlin.system.exitProcess(if (failed > 0) 1 else 0)
}

fun render(scene: Scene, dark: Boolean, file: File) {
    val root = kotlin.io.path.createTempDirectory("herdr-preview").toFile()
    val data = SceneData().apply(scene.setup)
    val context = Context(root)
    context.filesDir.resolve("machines.json").writeText(Json.encodeToString(ListSerializer(Machine.serializer()), data.machines))
    data.drafts.forEach { (k, v) -> context.getSharedPreferences("drafts", 0).edit().putString(k, v).apply() }
    data.push?.let { context.filesDir.resolve("push.json").writeText(Json.encodeToString(PushState.serializer(), it)) }
    PreviewPhone.apps = data.pushApps
    PreviewPhone.allowed = data.notificationsAllowed
    PreviewPhone.liveAllowed = data.liveAllowed
    PreviewPhone.quietMode = data.quietMode
    if (data.held.isNotEmpty()) context.filesDir.resolve("held.json").writeText(Json.encodeToString(ListSerializer(Alert.serializer()), data.held))
    IdentityStore.initial = scene.key
    FakeMachines.machines = data.hosts
    Toasts.dismiss()
    val container = AppContainer(context)
    try {
        // Every machine has answered (or refused) before the first frame, so a shot never catches "Connecting".
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val states = container.hosts.hosts.value
            if (states.size == data.machines.size && states.all { it.status != HostStatus.Connecting && it.status != HostStatus.Stopped }) break
            Thread.sleep(20)
        }
        val w = (scene.widthDp * SCALE).toInt()
        val h = (scene.heightDp * SCALE).toInt()
        runDesktopComposeUiTest(w, h) {
            mainClock.autoAdvance = false
            lateinit var nav: NavHostController
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(SCALE, scene.fontScale),
                    LocalContext provides context,
                    LocalView provides android.view.View(context),
                    LocalLifecycleOwner provides Resumed,
                    LocalViewModelStoreOwner provides Resumed,
                    LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) {} },
                ) {
                    nav = rememberNavController()
                    HerdrRoot(container, dark = dark, nav = nav)
                }
            }
            settle()
            scene.route?.let { route ->
                runOnUiThread { nav.navigate(route) }
                settle()
            }
            scene.actions(this, SceneContext(container, nav, data))
            settle()
            ImageIO.write(capture(), "png", file)
        }
    } finally {
        container.close()
        FakeMachines.closeAll()
        root.deleteRecursively()
    }
}

/** A lifecycle that is always resumed and a ViewModel store, which an Activity gives the app on the phone. */
private object Resumed : LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
}

/**
 * Lets the app catch up: advances the test clock (animations, LaunchedEffect delays) and gives the
 * fake machines' threads real time to answer, a few times over.
 */
fun ComposeUiTest.settle(rounds: Int = 12) {
    repeat(rounds) {
        mainClock.advanceTimeBy(200)
        Thread.sleep(60)
        waitForIdle()
    }
}

private fun ComposeUiTest.node(matcher: SemanticsMatcher) = onAllNodes(matcher, useUnmergedTree = true).onFirst()

/** Taps the first element showing [text] (exactly, or as its start when it is longer). */
fun ComposeUiTest.tapText(text: String) { node(hasText(text, substring = true)).performClick(); settle() }

/** Taps the first element whose content description (an icon button's label) is [label]. */
fun ComposeUiTest.tapLabel(label: String) { node(hasContentDescription(label)).performClick(); settle() }

fun ComposeUiTest.longPressText(text: String) { node(hasText(text, substring = true)).performTouchInput { longClick() }; settle() }

/** Types into the first text field (or the one showing [field]). */
fun ComposeUiTest.type(value: String, field: String? = null) {
    val matcher = androidx.compose.ui.test.hasSetTextAction().let { if (field != null) it and hasText(field, substring = true) else it }
    node(matcher).performTextInput(value)
    settle()
}

/** The main window plus any popups (menus, dialogs, sheets) drawn at their places in it. */
fun ComposeUiTest.capture(): java.awt.image.BufferedImage {
    val roots = onAllNodes(isRoot())
    val nodes = roots.fetchSemanticsNodes()
    val base = roots[0].captureToImage().toAwtImage()
    val out = java.awt.image.BufferedImage(base.getWidth(null), base.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(base, 0, 0, null)
    for (i in 1 until nodes.size) {
        val b = nodes[i].boundsInWindow
        g.drawImage(roots[i].captureToImage().toAwtImage(), b.left.toInt(), b.top.toInt(), null)
    }
    g.dispose()
    return out
}
