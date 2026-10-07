package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*

internal val callOwnerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
internal val callOwnerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
internal const val callNumber = "+12025550123"
internal fun callUiEdt(block: () -> Unit) {
    try { SwingUtilities.invokeAndWait(block) }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

internal class CallDualSimUiSource : CallSource by FixtureCallSource("unavailable") {
    val bindingA = CallBinding("synthetic-a", null, 1)
    val bindingB = CallBinding("synthetic-b", null, 1)
    override val state = MutableStateFlow<CallUiState>(ready(bindingA))
    override val history = MutableStateFlow<CallHistoryState>(CallHistoryState.Empty)
    override val historyOwners = MutableStateFlow(listOf(CallHistoryOwnerChoice(callOwnerA, "历史线路 1", true)))
    override val lines = MutableStateFlow(CallLineState(choices = listOf(choice(callOwnerA), choice(callOwnerB)), rosterComplete = true))
    val selections = mutableListOf<SimOwnerId>()
    val fallbackDials = mutableListOf<Pair<CallBinding, String>>()
    override fun refresh() = Unit
    override fun selectLine(owner: SimOwnerId) { selections.add(owner); authorize(owner) }
    override fun dial(expected: CallBinding, number: String) { fallbackDials.add(expected to number) }
    fun authorize(owner: SimOwnerId) {
        lines.value = lines.value.copy(selectedOwner = owner,
            outgoingBinding = if (owner == callOwnerA) bindingA else bindingB,
             choices = lines.value.choices.map { it.copy(selected = it.owner == owner) })
        historyOwners.value = listOf(CallHistoryOwnerChoice(owner, if (owner == callOwnerA) "历史线路 1" else "历史线路 2", true))
    }
    fun ready(binding: CallBinding = bindingA, phase: CallPhase = CallPhase.Idle, busy: Boolean = false) =
        CallUiState.Ready(phase, CallAudioState.Idle, CallOrigin.ENGINE, busy = busy, binding = binding)
    private fun choice(owner: SimOwnerId) = CallLineChoice(owner,
        if (owner == callOwnerA) "Synthetic A" else "Synthetic B", true, true, true, true, false, running = true)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class CallDualSimUiDriver(
    val source: CallDualSimUiSource = CallDualSimUiSource(),
    initial: AppRoute.Subpage? = AppRoute.Dialpad,
    restored: Map<String, List<Any?>>? = null,
    callback: Boolean = true,
) : AutoCloseable {
    private val scheduler = TestCoroutineScheduler()
    private val registry = SaveableStateRegistry(restored) { true }
    private val routes = Channel<AppRoute>(Channel.CONFLATED)
    private val incoming = routes.receiveAsFlow()
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    val requests = mutableListOf<Pair<CallBinding, String>>()
    var permissionClicks = 0
    private val sources = SoftVoWiFiSources(
        FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
        FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
        FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
        FixtureMessageSource("empty"), source,
    )
    private var time = 0L
    private var currentRoute: AppRoute = initial ?: AppRoute.Calls
    private val scene = ImageComposeScene(width = 420, height = 980,
        coroutineContext = StandardTestDispatcher(scheduler)) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalSaveableStateRegistry provides registry) {
            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.CALLS,
                initialSubpage = initial, incomingRoutes = incoming, forceDark = false,
                microphonePermissionGranted = false,
                onCallDial = if (callback) { binding, number -> requests.add(binding to number) } else null,
                onCallPermissions = { permissionClicks++ },
                callLineLabel = "Synthetic B")
        }
    }
    init { assertTrue(SwingUtilities.isEventDispatchThread()); frame() }
    fun frame() {
        repeat(40) { scheduler.runCurrent(); time += 16_666_667L; scene.render(time).close(); scheduler.runCurrent() }
    }
    fun route(route: AppRoute) { currentRoute = route; assertTrue(routes.trySend(route).isSuccess); frame() }
    fun save(): Map<String, List<Any?>> = registry.performSave()
    fun edit(value: String) {
        val field = nodes().last { it.config.getOrNull(SemanticsProperties.EditableText) != null }
        assertNull(field.config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        frame()
    }
    // EditableText is the visual, spaced number; InputText is the actual draft submitted to the source.
    fun number() = nodes().last { it.config.getOrNull(SemanticsProperties.InputText) != null }
        .config[SemanticsProperties.InputText].text
    fun hasText(value: String) = nodes().any { value in labels(it) }
    fun action(value: String) = assertNotNull(nodes().lastOrNull {
        value in labels(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
    }, "missing native action: $value")
    fun enabled(value: String) = action(value).config.getOrNull(SemanticsProperties.Disabled) == null
    fun geometry() = listOf("删除号码", "1", "0", "拨打").map { action(it).boundsInRoot }
    fun click(value: String) { invoke(action(value)); frame() }
    fun invoke(node: SemanticsNode) {
        assertNull(node.config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    fun select(owner: SimOwnerId) {
        val original = currentRoute
        if (original == AppRoute.Dialpad) route(AppRoute.Calls)
        click("出向线路")
        assertTrue(hasText("选择拨出线路"))
        click(if (owner == callOwnerA) "Synthetic A" else "Synthetic B")
        if (original == AppRoute.Dialpad) route(AppRoute.Dialpad)
    }
    fun capture(name: String) {
        val directory = Files.createDirectories(Path.of("build", "reports", "call-dual-ui"))
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(directory.resolve("$name.png"), data.bytes)
        } }
    }
    private fun labels(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
    override fun close() { scene.close(); scheduler.runCurrent(); routes.close() }
}
