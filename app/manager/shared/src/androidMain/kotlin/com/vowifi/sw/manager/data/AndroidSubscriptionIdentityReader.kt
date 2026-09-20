package com.vowifi.sw.manager.data

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

internal class AndroidSubscriptionIdentityReader(
    context: Context,
) {
    private val context = context.applicationContext

    fun read(iccid: String): SubscriptionIdentityRead = try {
        val requested = IccidNormalizer.normalize(iccid) ?: return SubscriptionIdentityRead.NotActive(NOT_ACTIVE)
        if (!hasTelephonyFeatures()) return SubscriptionIdentityRead.Unsupported(UNSUPPORTED)
        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return SubscriptionIdentityRead.Unreadable(UNREADABLE)
        val info = manager.activeSubscriptionInfoList.orEmpty()
            .firstOrNull { IccidNormalizer.normalize(it.iccId) == requested }
            ?: return SubscriptionIdentityRead.NotActive(NOT_ACTIVE)
        val subId = info.subscriptionId
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return SubscriptionIdentityRead.NotActive(NOT_ACTIVE)
        }
        val slot = info.simSlotIndex
        if (slot != SLOT_ZERO && slot != SLOT_ONE) {
            return SubscriptionIdentityRead.Unsupported(UNSUPPORTED)
        }
        SubscriptionIdentityRead.Complete(
            RawSubscriptionIdentity(
                iccid = requested,
                slot = slot,
                subscriptionId = subId,
                mcc = info.mccString,
                mnc = info.mncString,
                line1 = optionalRead { manager.getPhoneNumber(subId) },
                smsc = optionalRead { readSmsc(subId) },
                carrierId = AndroidCarrierId.project(info.carrierId),
            ),
        )
    } catch (_: SecurityException) {
        SubscriptionIdentityRead.PermissionDenied(PERMISSION_DENIED)
    } catch (_: IllegalStateException) {
        SubscriptionIdentityRead.Unreadable(UNREADABLE)
    } catch (_: UnsupportedOperationException) {
        SubscriptionIdentityRead.Unsupported(UNSUPPORTED)
    } catch (_: RuntimeException) {
        SubscriptionIdentityRead.Unreadable(UNREADABLE)
    }

    private fun hasTelephonyFeatures(): Boolean {
        val packageManager = context.packageManager
        return packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
    }

    private fun readSmsc(subId: Int): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            return null
        }
        return SmsManager.getSmsManagerForSubscriptionId(subId).smscAddress
    }

    private fun <T> optionalRead(block: () -> T?): T? = try {
        block()
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val SLOT_ZERO = 0
        const val SLOT_ONE = 1
    }
}

internal sealed interface SubscriptionIdentityRead {
    class Complete(val identity: RawSubscriptionIdentity) : SubscriptionIdentityRead {
        override fun toString(): String = "Complete"
    }
    data class Unsupported(val message: String) : SubscriptionIdentityRead
    data class Unreadable(val message: String) : SubscriptionIdentityRead
    data class PermissionDenied(val message: String) : SubscriptionIdentityRead
    data class NotActive(val message: String) : SubscriptionIdentityRead
}

private const val UNSUPPORTED = "该 SIM 不支持自动配置"
private const val UNREADABLE = "无法读取 SIM 身份"
private const val PERMISSION_DENIED = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
private const val NOT_ACTIVE = "SIM 当前未激活"
