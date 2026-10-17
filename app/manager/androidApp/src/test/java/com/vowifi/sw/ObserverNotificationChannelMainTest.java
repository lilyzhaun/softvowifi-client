package com.vowifi.sw;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ObserverNotificationChannelMainTest {
    private static final int UID = 10042;

    @Test public void defaultLowBecomesUserLockedNoneAndShownThroughBinderCopies() throws Exception {
        NotificationBinder f = new NotificationBinder();
        assertFalse("前提：默认渠道没有用户重要度选择", f.channel().hasUserSetImportance());
        assertTrue("默认观察渠道须完成迁移", apply(f));
        assertEquals("只能写一次目标渠道", 1, f.writes);
        assertEquals("必须读回而非修改本地副本就宣告成功", 2, f.reads);
        assertEquals("只隐藏目标渠道", NotificationManager.IMPORTANCE_NONE, f.channel().getImportance());
        assertTrue("须记录用户重要度锁以防FGS重新升为LOW", f.channel().hasUserSetImportance());
        assertTrue("须标记FGS/task已经展示", shown(f.channel()));
    }

    @Test public void alreadyMigratedNoneIsIdempotentWithoutAnotherWrite() throws Exception {
        NotificationBinder f = new NotificationBinder();
        f.channel().setImportance(NotificationManager.IMPORTANCE_NONE); lock(f.channel()); setShown(f.channel());
        assertTrue("既有目标状态须成功", apply(f));
        assertTrue("重复调用仍须成功", apply(f));
        assertEquals("已符合时不能重复写", 0, f.writes);
    }

    @Test public void userSelectedHighOrLowIsPreservedWithoutWriting() throws Exception {
        for (int importance : new int[] {NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_LOW}) {
            NotificationBinder f = new NotificationBinder(); f.channel().setImportance(importance); lock(f.channel());
            assertTrue("前提：真实NotificationChannel已锁用户选择", f.channel().hasUserSetImportance());
            assertTrue("用户显式开启应作为已处理保留", apply(f));
            assertEquals("不得覆盖用户显式开启", importance, f.channel().getImportance());
            assertEquals("保用户选择不得发update", 0, f.writes);
        }
    }

    @Test public void otherChannelsRemainByteForByteUnchanged() throws Exception {
        NotificationBinder f = new NotificationBinder();
        Map<String, byte[]> before = new LinkedHashMap<>();
        for (String id : new String[] {"messages", "incoming_calls", "mic"}) before.put(id, bytes(f.channels.get(id)));
        assertTrue("目标渠道迁移必须确已执行", apply(f));
        for (Map.Entry<String, byte[]> entry : before.entrySet())
            assertArrayEquals("其它渠道全部字段必须保持", entry.getValue(), bytes(f.channels.get(entry.getKey())));
        assertEquals("仅一次固定目标update", 1, f.writes);
    }

    @Test public void anIgnoredUpdateCannotBeReportedAsReady() throws Exception {
        NotificationBinder f = new NotificationBinder(); f.ignoreWrite = true;
        assertFalse("服务端未采纳不能假成功", apply(f));
        assertEquals("前提：真实update形态被调用一次", 1, f.writes);
        assertEquals("必须重新读取跨Binder副本", 2, f.reads);
        assertEquals("未生效保持LOW", NotificationManager.IMPORTANCE_LOW, f.channel().getImportance());
    }

    @Test public void missingChannelIsUnavailableAndDoesNotCreateOrUpdateIt() throws Exception {
        NotificationBinder f = new NotificationBinder(); f.channels.remove("ims_observer");
        assertFalse("渠道不存在不能宣布已隐藏", apply(f));
        assertEquals("必须实际查询渠道", 1, f.reads); assertEquals("不得创建新渠道", 0, f.writes);
    }

    private static boolean apply(NotificationBinder f) throws ReflectiveOperationException {
        return ObserverNotificationChannelMain.apply(f, NotificationBinder.class, UID);
    }
    private static void lock(NotificationChannel channel) throws ReflectiveOperationException {
        int flag = NotificationChannel.class.getDeclaredField("USER_LOCKED_IMPORTANCE").getInt(null);
        NotificationChannel.class.getMethod("lockFields", int.class).invoke(channel, flag);
    }
    private static Method shownMethod(String current, String legacy, Class<?>... parameters) throws NoSuchMethodException {
        try { return NotificationChannel.class.getMethod(current, parameters); }
        catch (NoSuchMethodException missing) { return NotificationChannel.class.getMethod(legacy, parameters); }
    }
    private static boolean shown(NotificationChannel channel) throws ReflectiveOperationException {
        return (Boolean) shownMethod("isUserVisibleTaskShown", "isFgServiceShown").invoke(channel);
    }
    private static void setShown(NotificationChannel channel) throws ReflectiveOperationException {
        shownMethod("setUserVisibleTaskShown", "setFgServiceShown", boolean.class).invoke(channel, true);
    }
    private static byte[] bytes(NotificationChannel channel) {
        Parcel p = Parcel.obtain();
        try { channel.writeToParcel(p, 0); return p.marshall(); } finally { p.recycle(); }
    }
    private static NotificationChannel copy(NotificationChannel channel) {
        if (channel == null) return null;
        Parcel p = Parcel.obtain();
        try { channel.writeToParcel(p, 0); p.setDataPosition(0); return NotificationChannel.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }
    public static final class NotificationBinder {
        final Map<String, NotificationChannel> channels = new LinkedHashMap<>();
        int reads; int writes; boolean ignoreWrite;
        NotificationBinder() {
            for (String id : new String[] {"ims_observer", "messages", "incoming_calls", "mic"})
                channels.put(id, new NotificationChannel(id, id, NotificationManager.IMPORTANCE_LOW));
        }
        NotificationChannel channel() { return channels.get("ims_observer"); }
        public NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String id, String conversationId, boolean includeDeleted) {
            assertEquals("固定目标包", "com.vowifi.sw", pkg); assertEquals("目标实际App UID", UID, uid);
            assertEquals("固定目标渠道", "ims_observer", id); assertFalse("不处理已删除渠道", includeDeleted);
            assertNull("不操作conversation子渠道", conversationId);
            reads++; return copy(channels.get(id));
        }
        public void updateNotificationChannelForPackage(String pkg, int uid, NotificationChannel channel) {
            assertEquals("固定目标包", "com.vowifi.sw", pkg); assertEquals("目标实际App UID", UID, uid);
            assertEquals("只能更新观察渠道", "ims_observer", channel.getId());
            writes++; if (!ignoreWrite) channels.put(channel.getId(), copy(channel));
        }
    }
}
