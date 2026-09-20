package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ActiveEngineSelector
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageRouteContractTest {
    @Test
    fun conversationRowClickTargetsItsOpaqueThreadRoute() {
        val state = ready(MessageRecord.Incoming("incoming", "+155501", "body", 100L))
        val row = projectMessageConversations(owner(), state).rows.single()

        assertEquals(AppRoute.MessageThread(row.handle), messageConversationRoute(row))
        assertFalse(messageConversationRoute(row).toString().contains("155501"))
    }

    @Test
    fun persistedHistoryRemainsVisibleWhenEngineIsReadOnly() {
        val state = ready(
            MessageRecord.Incoming("persisted", "+155501", "persisted body", 100L),
            composerEnabled = false,
            attachmentNotice = ActiveEngineSelector.NONE_MESSAGE,
        )

        val root = projectMessageConversations(owner(), state)
        val thread = projectMessageThread(root.rows.single().handle, owner(), state) {
            MessageDateSpec("day", "某日")
        }

        assertEquals(1, root.rows.size)
        assertEquals(MessageComposeMode.READ_ONLY, root.compose.mode)
        assertEquals(2, thread?.items?.size)
        assertEquals(MessageComposeMode.READ_ONLY, thread?.compose?.mode)
    }

    @Test
    fun eachMessageRouteHasOneScrollOwnerAndThreadKeepsLastItemAboveIme() {
        val root = messageRouteLayout(AppRoute.Messages)
        val thread = messageRouteLayout(
            AppRoute.MessageThread(projectMessageConversations(owner(), ready()).rows.firstOrNull()?.handle
                ?: com.vowifi.sw.manager.ui.navigation.ConversationHandle.fromOpaque("conversation-empty")),
        )
        val fresh = messageRouteLayout(AppRoute.NewMessage)

        assertEquals(MessageScrollOwner.CONVERSATION_LIST, root.scrollOwner)
        assertFalse(root.composerPinned)
        assertEquals(MessageScrollOwner.THREAD_TIMELINE, thread.scrollOwner)
        assertTrue(thread.composerPinned)
        assertTrue(thread.composerConsumesImeInsets)
        assertTrue(thread.reserveLastItemSpace)
        assertEquals(MessageScrollOwner.NEW_MESSAGE_FORM, fresh.scrollOwner)
        assertTrue(fresh.composerPinned)
        assertTrue(fresh.composerConsumesImeInsets)
        assertTrue(fresh.reserveLastItemSpace)
    }

    @Test
    fun threadAndNewMessageUseExactlyOneImeOwnerConsumedByProductionContract() {
        val thread = messageRouteLayout(
            AppRoute.MessageThread(
                com.vowifi.sw.manager.ui.navigation.ConversationHandle.fromOpaque("conversation-ime-owner"),
            ),
        )
        val fresh = messageRouteLayout(AppRoute.NewMessage)

        assertEquals(MessageImeOwner.COMPOSE_COMPOSER_PADDING, thread.imeOwner)
        assertEquals(MessageImeOwner.COMPOSE_COMPOSER_PADDING, fresh.imeOwner)
        assertTrue(thread.composerConsumesImeInsets)
        assertTrue(fresh.composerConsumesImeInsets)
    }

    @Test
    fun threadInitialScrollTargetsTheLastBubbleWithoutARepeatedPeerHeader() {
        assertEquals(6, messageThreadLastItemIndex(itemCount = 6, disclosed = true))
        assertEquals(5, messageThreadLastItemIndex(itemCount = 6, disclosed = false))
        assertEquals(null, messageThreadLastItemIndex(itemCount = 0, disclosed = true))
    }

    @Test
    fun listAndComposerDimensionsStayCompactAndTouchSafe() {
        val list = messageListLayoutContract()
        val composer = messageComposerLayoutContract()

        assertEquals(72, list.rowMinHeightDp)
        assertEquals(44, list.avatarSizeDp)
        assertEquals(48, list.topActionSizeDp)
        assertFalse(list.usesFab)
        assertTrue(list.openRows)
        assertEquals(48, list.minimumTouchDp)
        assertEquals(2, list.previewMaxLines)
        assertEquals(1, list.statusMaxLines)
        assertEquals(48, composer.sendTargetDp)
        assertTrue(composer.horizontal)
        assertTrue(composer.pinnedAboveIme)
        assertTrue(composer.capsule)
        assertTrue(composer.circularSendAction)
        assertFalse(composer.attachmentAction)
    }

    @Test
    fun bubblesUseAsymmetricSemanticGeometryAndBoundedMotion() {
        val incoming = messageBubbleLayoutContract(MessageBubbleDirection.INCOMING)
        val outgoing = messageBubbleLayoutContract(MessageBubbleDirection.OUTGOING)
        val motion = messageMotionContract(reducedMotion = false)
        val reduced = messageMotionContract(reducedMotion = true)

        assertEquals(0.78f, incoming.maximumWidthFraction)
        assertEquals(16, incoming.bodyFontSizeSp)
        assertEquals(22, incoming.bodyLineHeightSp)
        assertEquals(18, incoming.topStartRadiusDp)
        assertEquals(18, incoming.topEndRadiusDp)
        assertEquals(18, incoming.bottomEndRadiusDp)
        assertEquals(4, incoming.bottomStartRadiusDp)
        assertEquals(4, outgoing.bottomEndRadiusDp)
        assertEquals(18, outgoing.bottomStartRadiusDp)
        assertEquals(MessageBubbleColorRole.SURFACE_CONTAINER, incoming.colorRole)
        assertEquals(MessageBubbleColorRole.INTERACTIVE_PRIMARY, outgoing.colorRole)
        assertTrue(motion.durationMillis <= 160)
        assertEquals(0, reduced.durationMillis)
        assertFalse(motion.rootCrossfade)
        assertEquals(0.78f, messageBubbleMaximumWidth(fontScale = 1f))
        assertEquals(0.90f, messageBubbleMaximumWidth(fontScale = 1.5f))
        assertEquals(0.90f, messageBubbleMaximumWidth(fontScale = 2f))
    }

    @Test
    fun messageAnimationsAreFadeOnlyWithoutLazyPlacementDrift() {
        val animation = messageContentAnimationContract()

        assertTrue(animation.durationMillis <= 160)
        assertEquals(setOf(MessageMotionProperty.ALPHA), animation.listAndBubbleProperties)
        assertFalse(animation.animatePlacement)
        assertEquals(null, animation.placementDurationMillis)
        assertEquals(
            setOf(MessageMotionProperty.ALPHA, MessageMotionProperty.COLOR, MessageMotionProperty.SCALE),
            animation.sendActionProperties,
        )
        assertFalse(animation.decorativePulse)
    }

    @Test
    fun draftPolicyPreservesBackNavigationAndClearsOnlyAfterSubmission() {
        val policy = messageDraftPolicy()

        assertTrue(policy.preserveOnBack)
        assertFalse(policy.clearAfterSubmission)
    }

    @Test
    fun fixtureDisclosureIsRequiredOnRootAndDeepPages() {
        val fixture = ready(origin = MessageOrigin.DESKTOP_FIXTURE)
        val engine = ready(origin = MessageOrigin.ENGINE)

        listOf(AppRoute.Messages, AppRoute.NewMessage).forEach { route ->
            assertEquals(FIXTURE_DISCLOSURE_TEXT, messageFixtureDisclosure(route, fixture))
            assertEquals(null, messageFixtureDisclosure(route, engine))
        }
    }

    @Test
    fun submissionGatePreventsDuplicateSendUntilSourceSettles() {
        val ready = ready()
        val gate = MessageSubmissionGate()

        assertTrue(gate.tryBegin(ready, "+155501", "body"))
        assertFalse(gate.tryBegin(ready, "+155501", "body"))
        gate.observeSourceBusy(true)
        assertFalse(gate.tryBegin(ready, "+155501", "body"))
        gate.observeSourceBusy(false)
        assertTrue(gate.tryBegin(ready, "+155501", "body"))
    }

    @Test
    fun unknownResultAndReadOnlyStorageNeverEnableAnotherSendPath() {
        val unknown = ready(
            MessageRecord.Outgoing(
                id = "unknown",
                peerLabel = "+155501",
                preview = "body",
                timestampMs = 100L,
                status = com.vowifi.sw.manager.model.OutgoingMessageStatus.UNKNOWN,
            ),
            composerEnabled = false,
            attachmentNotice = ActiveEngineSelector.NONE_MESSAGE,
        )
        val page = projectMessageConversations(owner(), unknown)

        assertEquals("发送结果未知", page.rows.single().outgoingStatus)
        assertEquals(MessageComposeMode.READ_ONLY, page.compose.mode)
        assertFalse(canSendMessage(unknown, "+155501", "retry"))
    }

    @Test
    fun longCjkControlAndSipTextStayBoundedAndPrivateForTwoHundredPercentLayout() {
        val longCjk = "很长的短信内容".repeat(200)
        val unsafe = "sip:user@example.invalid\u0000 raw-owner"
        val state = ready(
            MessageRecord.Incoming("long", "+155501", longCjk, 100L),
            MessageRecord.Incoming("unsafe", unsafe, unsafe, 200L),
        )

        val rows = projectMessageConversations(owner(), state).rows

        assertTrue(rows.first { it.peerDisplay == "+155501" }.latestPreview.length <= 80)
        assertEquals("未知号码", rows.first { it.peerDisplay == "未知号码" }.peerDisplay)
        assertEquals("内容不可显示", rows.first { it.peerDisplay == "未知号码" }.latestPreview)
        assertTrue(messageTextLayoutContract(fontScale = 2f).softWrap)
        assertTrue(messageTextLayoutContract(fontScale = 2f).breakUnboundedText)
    }

    private fun owner(): SimOwnerId = checkNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 11 }))

    private fun ready(
        vararg records: MessageRecord,
        origin: MessageOrigin = MessageOrigin.ENGINE,
        composerEnabled: Boolean = true,
        attachmentNotice: String? = null,
    ) = MessageUiState.Ready(
        records = records.toList(),
        origin = origin,
        composerEnabled = composerEnabled,
        attachmentNotice = attachmentNotice,
    )
}
