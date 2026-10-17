package com.vowifi.sw;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

// 复制型Binder只证明请求/读回协议，不模拟critical保护、FGS升档或OEM行为。
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ObserverNotificationChannelRepairTest {
    private static final int UID = 10042;

    @Test public void authorizedLockedLowRepairReadsBackBlockableBeforeRequestingNone() throws Exception {
        RepairBinder f = new RepairBinder();
        assertTrue("前提：复现渠道已锁重要度", f.channel().hasUserSetImportance());
        assertTrue("前提：FGS已展示", shown(f.channel()));
        assertFalse("前提：旧渠道不可阻止", blockable(f.channel()));
        Map<String, byte[]> others = new LinkedHashMap<>();
        for (String id : new String[] {"messages", "incoming_calls", "mic"}) others.put(id, bytes(f.channels.get(id)));
        NotificationChannel first = copy(f.channel()); setBlockable(first, true);
        assertTrue("本轮明确隐藏授权须能修复已锁LOW，而非沿用默认路径跳过", repair(f));
        assertEquals(Arrays.asList("read", "write:2:true", "read", "write:0:true", "read"), f.events);
        assertArrayEquals("首个update只能开放blockable，不得抢先改重要度或其它字段", bytes(first), bytes(f.updates.get(0)));
        assertReady(f.channel());
        for (Map.Entry<String, byte[]> other : others.entrySet())
            assertArrayEquals("其它渠道完整字节保持", other.getValue(), bytes(f.channels.get(other.getKey())));
    }

    @Test public void alreadyBlockableLowNeedsOnlyOneImportanceWriteAndReadback() throws Exception {
        RepairBinder f = new RepairBinder(); setBlockable(f.channel(), true);
        assertTrue(repair(f));
        assertEquals(Arrays.asList("read", "write:0:true", "read"), f.events);
        assertReady(f.channel());
    }

    @Test public void readyRepairIsIdempotentWithZeroWrites() throws Exception {
        RepairBinder f = new RepairBinder(); setBlockable(f.channel(), true);
        f.channel().setImportance(NotificationManager.IMPORTANCE_NONE);
        byte[] before = bytes(f.channel());
        assertTrue(repair(f)); assertTrue(repair(f));
        assertEquals(Arrays.asList("read", "read"), f.events);
        assertEquals(0, f.updates.size());
        assertArrayEquals(before, bytes(f.channel()));
    }

    @Test public void ignoredBlockableWriteStopsBeforeAnyNoneRequest() throws Exception {
        RepairBinder f = new RepairBinder(); f.ignoreWrite = 1;
        byte[] before = bytes(f.channel());
        assertFalse("首阶段未持久化不得报成功或继续改NONE", repair(f));
        assertEquals(Arrays.asList("read", "write:2:true", "read"), f.events);
        assertEquals(1, f.updates.size());
        assertArrayEquals(before, bytes(f.channel()));
    }

    @Test public void ignoredImportanceWriteCannotBeReportedAsReady() throws Exception {
        RepairBinder f = new RepairBinder(); f.ignoreWrite = 2;
        assertFalse("第二阶段被忽略必须读回失败", repair(f));
        assertEquals(Arrays.asList("read", "write:2:true", "read", "write:0:true", "read"), f.events);
        assertTrue(blockable(f.channel()));
        assertEquals(NotificationManager.IMPORTANCE_LOW, f.channel().getImportance());
    }

    @Test public void incompleteNoneCannotBeReadyWhenItsRepairWriteIsIgnored() throws Exception {
        for (String missing : new String[] {"blockable", "importanceLock", "shown"}) {
            RepairBinder f = new RepairBinder();
            f.channel().setImportance(NotificationManager.IMPORTANCE_NONE); setBlockable(f.channel(), true);
            if (missing.equals("blockable")) setBlockable(f.channel(), false);
            if (missing.equals("importanceLock")) NotificationChannel.class.getMethod("unlockFields", int.class)
                    .invoke(f.channel(), importanceLock());
            if (missing.equals("shown")) shownMethod("setUserVisibleTaskShown", "setFgServiceShown", boolean.class)
                    .invoke(f.channel(), false);
            byte[] before = bytes(f.channel()); f.ignoreWrite = 1;
            assertFalse("NONE不能掩盖缺少的" + missing, repair(f));
            assertEquals("须实际尝试修补缺失字段", 1, f.updates.size());
            assertEquals("更新后必须读回", 3, f.events.size());
            assertEquals("read", f.events.get(2));
            assertArrayEquals(before, bytes(f.channel()));
        }
    }

    @Test public void missingChannelAndInvalidUidNeverWrite() throws Exception {
        RepairBinder missing = new RepairBinder(); missing.channels.remove("ims_observer");
        assertFalse(repair(missing)); assertEquals(Arrays.asList("read"), missing.events);
        RepairBinder invalid = new RepairBinder();
        assertFalse(ObserverNotificationChannelMain.applyRepair(invalid, RepairBinder.class, 9999));
        assertTrue("无效UID不能调用Binder", invalid.events.isEmpty());
    }

    private static boolean repair(RepairBinder f) throws ReflectiveOperationException {
        return ObserverNotificationChannelMain.applyRepair(f, RepairBinder.class, UID);
    }
    private static boolean blockable(NotificationChannel c) throws ReflectiveOperationException {
        return (Boolean) NotificationChannel.class.getMethod("isBlockable").invoke(c);
    }
    private static void setBlockable(NotificationChannel c, boolean value) throws ReflectiveOperationException {
        NotificationChannel.class.getMethod("setBlockable", boolean.class).invoke(c, value);
    }
    private static int importanceLock() throws ReflectiveOperationException {
        return NotificationChannel.class.getDeclaredField("USER_LOCKED_IMPORTANCE").getInt(null);
    }
    private static Method shownMethod(String current, String legacy, Class<?>... args) throws NoSuchMethodException {
        try { return NotificationChannel.class.getMethod(current, args); }
        catch (NoSuchMethodException missing) { return NotificationChannel.class.getMethod(legacy, args); }
    }
    private static boolean shown(NotificationChannel c) throws ReflectiveOperationException {
        return (Boolean) shownMethod("isUserVisibleTaskShown", "isFgServiceShown").invoke(c);
    }
    private static void assertReady(NotificationChannel c) throws ReflectiveOperationException {
        assertEquals(NotificationManager.IMPORTANCE_NONE, c.getImportance());
        assertTrue(c.hasUserSetImportance()); assertTrue(shown(c)); assertTrue(blockable(c));
    }
    private static byte[] bytes(NotificationChannel c) {
        Parcel p = Parcel.obtain();
        try { c.writeToParcel(p, 0); return p.marshall(); } finally { p.recycle(); }
    }
    private static NotificationChannel copy(NotificationChannel c) {
        if (c == null) return null;
        Parcel p = Parcel.obtain();
        try { c.writeToParcel(p, 0); p.setDataPosition(0); return NotificationChannel.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }

    public static final class RepairBinder {
        final Map<String, NotificationChannel> channels = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<NotificationChannel> updates = new ArrayList<>();
        int ignoreWrite;
        RepairBinder() throws ReflectiveOperationException {
            for (String id : new String[] {"ims_observer", "messages", "incoming_calls", "mic"})
                channels.put(id, new NotificationChannel(id, id, NotificationManager.IMPORTANCE_LOW));
            NotificationChannel.class.getMethod("lockFields", int.class).invoke(channel(), importanceLock());
            shownMethod("setUserVisibleTaskShown", "setFgServiceShown", boolean.class).invoke(channel(), true);
            setBlockable(channel(), false);
        }
        NotificationChannel channel() { return channels.get("ims_observer"); }
        public NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String id,
                String conversationId, boolean includeDeleted) {
            assertEquals("com.vowifi.sw", pkg); assertEquals(UID, uid); assertEquals("ims_observer", id);
            assertNull(conversationId); assertFalse(includeDeleted);
            events.add("read"); return copy(channels.get(id));
        }
        public void updateNotificationChannelForPackage(String pkg, int uid, NotificationChannel c)
                throws ReflectiveOperationException {
            assertEquals("com.vowifi.sw", pkg); assertEquals(UID, uid); assertEquals("ims_observer", c.getId());
            events.add("write:" + c.getImportance() + ":" + blockable(c)); updates.add(copy(c));
            if (updates.size() != ignoreWrite) channels.put(c.getId(), copy(c));
        }
    }
}
