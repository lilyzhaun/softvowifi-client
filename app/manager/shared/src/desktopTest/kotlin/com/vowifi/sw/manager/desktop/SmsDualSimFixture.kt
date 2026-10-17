package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val smsOwnerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
internal val smsOwnerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
internal const val smsPeerA = "+12025550123"
internal const val smsPeerB = "+12025550124"
internal val smsThreadA = StoredMessageConversation(smsOwnerA, smsPeerA.drop(1))

internal fun smsOnEdt(block: () -> Unit) {
    try { SwingUtilities.invokeAndWait(block) }
    catch (failure: InvocationTargetException) { throw failure.targetException }
}

internal fun smsChoices(
    owners: List<SimOwnerId> = listOf(smsOwnerA, smsOwnerB),
    active: Boolean? = true,
    complete: Boolean = true,
    selected: SimOwnerId? = null,
) = MessageHistoryChoices(
    lines = owners.mapIndexed { index, owner ->
        MessageHistoryLine(owner, 0, connected = true,
            label = MessageOwnerLabel(index + 1L, if (owner == smsOwnerA) "Synthetic A" else "Synthetic B"),
            active = active, sendable = true)
    },
    selectedOwner = selected, storageAvailable = true, activeRosterComplete = complete,
)

internal fun smsReady(owner: SimOwnerId = smsOwnerA, authorized: Boolean = true) =
    MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = authorized, ownerId = owner)

internal class SmsDualSimSource(
    initial: MessageUiState = smsReady(),
    choices: MessageHistoryChoices = smsChoices(selected = smsOwnerA),
) : MessageSource {
    override val state = MutableStateFlow(initial)
    override val historyChoices = MutableStateFlow(choices)
    val selections = mutableListOf<SimOwnerId>()
    val sends = mutableListOf<MessageSendAttempt>()
    val remarks = mutableListOf<Pair<SimOwnerId, String>>()
    var publishSelections = true
    private val attempts = mutableMapOf<SimOwnerId, MessageSendAttempt>()
    private var rootState = initial
    var conversation: String? = null
        private set

    override fun refresh() = Unit
    override suspend fun saveOwnerRemark(owner: SimOwnerId, input: String): MessageRemarkSaveResult {
        remarks.add(owner to input)
        historyChoices.value = historyChoices.value.copy(lines = historyChoices.value.lines.map { line ->
            if (line.owner == owner) line.copy(label = MessageOwnerLabel(requireNotNull(line.label).ordinal, input)) else line
        })
        return MessageRemarkSaveResult.Saved
    }
    override fun selectHistoryOwner(owner: SimOwnerId) {
        selections.add(owner)
        historyChoices.value = historyChoices.value.copy(selectedOwner = owner)
        if (!publishSelections) return
        rootState = smsReady(owner).copy(sendAttempt = attempts[owner])
        if (conversation == null) state.value = rootState
    }

    override fun selectConversation(handle: String?) {
        if (handle == conversation) return
        conversation = handle
        state.value = if (handle == null) rootState else {
            assertEquals(smsThreadA.handle.value, handle)
            smsReady(smsOwnerA).copy(
                records = listOf(MessageRecord.Incoming("synthetic-incoming", smsPeerA, "synthetic incoming", 0)),
                window = MessageThreadWindow(smsThreadA, null), sendAttempt = attempts[smsOwnerA],
            )
        }
    }

    override fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) {
        val displayed = state.value as MessageUiState.Ready
        assertEquals(displayed.ownerId, owner)
        assertTrue(displayed.composerEnabled)
        val attempt = MessageSendAttempt(requireNotNull(owner), "attempt-${sends.size}", number, text, OutgoingMessageStatus.QUEUED)
        sends.add(attempt)
        attempts[owner] = attempt
        publish(displayed.copy(sendBusy = true, sendAttempt = attempt))
    }

    fun publish(value: MessageUiState) {
        state.value = value
        if (conversation == null) rootState = value
    }

    fun complete(owner: SimOwnerId, status: OutgoingMessageStatus) {
        val attempt = requireNotNull(attempts[owner]).copy(status = status)
        attempts[owner] = attempt
        val displayed = state.value as? MessageUiState.Ready
        if (displayed?.ownerId == owner) publish(displayed.copy(sendBusy = false, sendAttempt = attempt))
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SmsDualSimDriver(
    val source: SmsDualSimSource = SmsDualSimSource(),
    initial: AppRoute.Subpage? = AppRoute.NewMessage,
    restored: Map<String, List<Any?>>? = null,
) : AutoCloseable {
    init { assertTrue(SwingUtilities.isEventDispatchThread()) }
    private val scheduler = TestCoroutineScheduler()
    private val registry = SaveableStateRegistry(restored) { true }
    private val routes = Channel<AppRoute>(Channel.CONFLATED)
    private val incoming = routes.receiveAsFlow()
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val sources = SoftVoWiFiSources(
        FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
        FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
        FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
        // Root creation requires explicit registered-engine facts; SMS authorization is still tested independently.
        source, CallDualSimUiSource(),
    )
    private var time = 0L
    private var activeRoute: AppRoute = initial ?: AppRoute.Messages
    private var coveredRootNodeIds = emptySet<Int>()
    private val scene = ImageComposeScene(width = 420, height = 900,
        coroutineContext = StandardTestDispatcher(scheduler)) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalSaveableStateRegistry provides registry) {
            SoftVoWiFiApp(sources, {}, false, "test", initialDestination = AppDestination.MESSAGES,
                initialSubpage = initial, incomingRoutes = incoming, forceDark = false)
        }
    }
    init { frame() }
    fun frame() {
        assertTrue(SwingUtilities.isEventDispatchThread())
        repeat(40) {
            scheduler.runCurrent()
            time += 16_666_667L
            // C2的150ms确认驻留必须与T8渲染共用时钟，不能只推进render而让delay永久挂起。
            scheduler.advanceTimeBy(time / 1_000_000L - scheduler.currentTime)
            scheduler.runCurrent()
            scene.render(time).close()
            scheduler.runCurrent()
        }
    }
    fun route(route: AppRoute) {
        if (activeRoute is AppRoute.Root) coveredRootNodeIds = nodes().map { it.id }.toSet()
        activeRoute = route
        assertTrue(routes.trySend(route).isSuccess)
        frame()
    }
    fun fields() = nodes().filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
        .takeLast(if (activeRoute is AppRoute.MessageThread) 1 else 2)
    fun field(index: Int) = fields()[index].config[SemanticsProperties.EditableText].text
    fun editable(index: Int) = fields()[index].config.getOrNull(SemanticsProperties.Disabled) == null
    fun save(): Map<String, List<Any?>> = registry.performSave()
    fun edit(index: Int, value: String) {
        val input = fields()[index]
        assertTrue(input.config.getOrNull(SemanticsProperties.Disabled) == null, "draft field must be editable")
        assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
        frame()
    }
    fun canSend() = nodes().any { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
    fun send() {
        clickNode(assertNotNull(nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送"
        }, "production send action must be enabled"))
    }
    fun hasText(value: String) = nodes().any { value in labels(it) }
    fun trace(stage: String) {
        fun owner(value: SimOwnerId?) = when (value) { null -> "none"; smsOwnerA -> "A"; smsOwnerB -> "B"; else -> "other" }
        val inputs = nodes().filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }.takeLast(4)
        println("S4_SMS_DUAL|stage=$stage|route=${activeRoute::class.simpleName}|clock=${scheduler.currentTime}|renderMs=${time / 1_000_000L}|" +
            "state=${source.state.value::class.simpleName}|stateOwner=${owner((source.state.value as? MessageUiState.Available)?.ownerId)}|" +
            "selected=${owner(source.historyChoices.value.selectedOwner)}|selections=${source.selections.map(::owner)}|sends=${source.sends.size}|" +
            "picker=${pickerPresent()}|inputs=" + inputs.map { node ->
                val value = node.config[SemanticsProperties.EditableText].text
                "${node.id}@${node.boundsInRoot}:disabled=${node.config.contains(SemanticsProperties.Disabled)},length=${value.length},peerA=${value == smsPeerA},peerB=${value == smsPeerB}"
            })
    }
    fun click(value: String) {
        if (value == "新建短信" || value == "处理未绑定草稿") {
            coveredRootNodeIds = nodes().map { it.id }.toSet()
            activeRoute = AppRoute.NewMessage
        }
        clickNode(assertNotNull(nodes().lastOrNull {
            value in labels(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
        }, "missing native action: $value"))
    }
    fun openPicker() {
        clickNode(assertNotNull(nodes().lastOrNull { node ->
            (activeRoute is AppRoute.Root || node.id !in coveredRootNodeIds) &&
            node.config.getOrNull(SemanticsActions.OnClick) != null && labels(node).any {
                it.contains("线路") || it.contains("SIM") || it == "Synthetic A" || it == "Synthetic B"
            }
        }, "new-message page must expose a native SIM picker even without history"))
        assertTrue(hasText(if (source.historyChoices.value.lines.any { it.running }) "选择短信线路" else "选择短信记录线路"), "native line picker must open")
    }
    fun settlePickerExit() {
        // C2确认驻留后仍须完成原T8；只等真实宿主移除，不以选线回调到达冒充退场完成。
        repeat(3) { if (!pickerPresent(visibleOnly = false)) return; frame() }
        assertTrue(!pickerPresent(visibleOnly = false), "confirmed native line picker must finish its actual exit")
    }
    // 未绑定时根入口也叫“选择短信线路”；面板必须以真实heading归属识别，不能把入口当残留宿主。
    private fun pickerPresent(visibleOnly: Boolean = true) = nodes(visibleOnly).any { node ->
        node.config.contains(SemanticsProperties.Heading) &&
            labels(node).any { it == "选择短信线路" || it == "选择短信记录线路" }
    }
    fun selectAtRoot(owner: SimOwnerId) {
        route(AppRoute.Messages)
        openPicker()
        click(if (owner == smsOwnerA) "Synthetic A" else "Synthetic B")
        route(AppRoute.NewMessage)
    }
    fun capture(name: String) {
        val directory = Files.createDirectories(Path.of("build", "reports", "sms-s3"))
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(directory.resolve("$name.png"), data.bytes)
        } }
    }
    private fun clickNode(node: SemanticsNode) {
        assertTrue(node.config.getOrNull(SemanticsProperties.Disabled) == null)
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        frame()
    }
    private fun labels(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun nodes(visibleOnly: Boolean = true): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }.filter { !visibleOnly || it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
    override fun close() { scene.close(); scheduler.runCurrent(); routes.close() }
}
