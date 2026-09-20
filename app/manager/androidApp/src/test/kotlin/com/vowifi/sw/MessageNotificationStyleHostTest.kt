package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageRecord
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageNotificationStyleHostTest {
    @Test fun frameworkMessagingViewShowsBoundedRealContentsAndGenericPublicVersion() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val adapter = AndroidMessageNotifications(app, Activity::class.java, R.drawable.ic_stat_message)
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val rows = (0..7).map { MessageRecord.Incoming("row-$it", "+12025550123",
            if (it == 7) "The meeting is at 10:30.\nPlease bring the notes." else "Synthetic message $it", 1_780_000_000_000L + it * 1000) }
        adapter.onSnapshot(owner, rows, rows.last().id)
        adapter.awaitNotifications()
        val notification = manager.activeNotifications.single().notification
        val recovered = Notification.Builder.recoverBuilder(app, notification)
        val style = recovered.style as Notification.MessagingStyle
        assertEquals(rows.takeLast(6).map { it.preview }, style.messages.map { it.text.toString() })
        assertEquals("+12025550123", style.messages.last().senderPerson?.name?.toString())
        assertFalse(style.isGroupConversation)
        assertNull(style.conversationTitle)
        assertEquals(8, notification.number)
        assertEquals(0, notification.actions?.size ?: 0)
        assertEquals("SoftVoWiFi", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("收到新短信", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertNull(notification.publicVersion.extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        assertNull(notification.publicVersion.contentIntent)
        val saved = shadowOf(notification.contentIntent).savedIntent
        assertEquals(setOf(AndroidMessageNotifications.EXTRA_HANDLE), saved.extras?.keySet())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            val parent = FrameLayout(activity)
            val view = requireNotNull(recovered.createBigContentView()).apply(activity, parent)
            parent.addView(view)
            activity.setContentView(parent)
            view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, 390, view.measuredHeight)
            assertTrue(view.measuredHeight > 0)
            val bitmap = Bitmap.createBitmap(390, view.measuredHeight, Bitmap.Config.ARGB_8888)
            val background = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
            bitmap.eraseColor(background.data)
            view.draw(Canvas(bitmap))
            File("/tmp/opencode/issue27-native-messaging.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { activity.finish() }
    }
}
