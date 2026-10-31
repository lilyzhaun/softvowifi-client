package com.vowifi.sw.root

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Binder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

/** Real service methods and caller/signature checks; no su process or device traffic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE,
    shadows = [RootCallerPackageManagerShadow::class])
class RootBinderCallerHostTest {
    private lateinit var service: RootBackendService
    private lateinit var backend: IRootBackend
    private lateinit var installed: PackageInfo
    private var permissionGranted = true
    private var permissionChecks = 0
    private val owner = 10123

    @Before fun prepare() {
        RootCallerPackageManagerShadow.signerMatches = true
        RootCallerPackageManagerShadow.checkedUid = -1
        RootCallerPackageManagerShadow.checkedDigest = null
        val app = RuntimeEnvironment.getApplication()
        installed = PackageInfo().apply {
            packageName = "com.vowifi.sw"
            applicationInfo = ApplicationInfo().apply { packageName = "com.vowifi.sw"; uid = owner }
            signatures = arrayOf(Signature("synthetic-signer".toByteArray()))
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signatures) }
        }
        shadowOf(app.packageManager).installPackage(installed)
        assertEquals(owner, app.packageManager.getApplicationInfo("com.vowifi.sw", 0).uid)
        assertArrayEquals(installed.signatures,
            app.packageManager.getPackageInfo("com.vowifi.sw", PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners)
        val context = object : ContextWrapper(app) {
            override fun getPackageName() = "com.vowifi.sw"
            override fun enforceCallingPermission(permission: String, message: String?) {
                assertEquals("com.vowifi.sw.permission.ROOT_SERVICE", permission)
                assertEquals("permission must be checked before clearing identity", owner, Binder.getCallingUid())
                permissionChecks++
                if (!permissionGranted) throw SecurityException("synthetic_permission_denied")
            }
        }
        service = RootBackendService()
        // Attach only the Android context; the libsu process/server lifecycle is outside this host test.
        ReflectionHelpers.setField(service, "mBase", context)
        backend = IRootBackend.Stub.asInterface(ReflectionHelpers.callInstanceMethod(service, "onBind",
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent())))
        ShadowProcess.setUid(0)
        ShadowBinder.setCallingUid(owner)
    }

    @After fun cleanup() { ReflectionHelpers.callInstanceMethod<Unit>(service, "onDestroy") }

    private fun assertAllMethodsDeny(expected: String) {
        val methods: List<Pair<String, () -> Unit>> = listOf(
            "probe" to { backend.runReadOnlyChecks(); Unit },
            "probe stream" to { backend.attachProbeStream(null) },
            "probe status" to { backend.getProbeStreamStatus(); Unit },
            "subscriptions" to { backend.activeSubscriptions(); Unit },
            "subscription extras" to { backend.subscriptionExtras(1); Unit },
            "runtime" to { backend.prepareRuntime(); Unit },
            "native status" to { backend.nativeStatus(); Unit },
            "call state" to { backend.nativeCallsIdle(); Unit },
            "registration" to { backend.observeNativeRegistration(null) },
            "engine stream" to { backend.attachEngineStream("invalid", false, null) },
        )
        methods.forEach { (name, call) ->
            try { call(); fail("unauthorized method accepted: $name") }
            catch (error: SecurityException) { assertEquals(name, expected, error.message) }
        }
    }

    @Test fun foreignUidCannotInvokeAnyMethodEvenWithTheSameSigner() {
        val other = PackageInfo().apply {
            packageName = "com.vowifi.sw.qa"
            applicationInfo = ApplicationInfo().apply { packageName = "com.vowifi.sw.qa"; uid = owner + 1 }
            signatures = installed.signatures
            signingInfo = installed.signingInfo
        }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(other)
        ShadowBinder.setCallingUid(owner + 1)
        assertAllMethodsDeny("caller_denied")
        assertEquals(0, permissionChecks)
    }

    @Test fun nonRootServiceCannotInvokeAnyMethod() {
        ShadowProcess.setUid(owner)
        assertAllMethodsDeny("caller_denied")
        assertEquals(0, permissionChecks)
    }

    @Test fun revokedPermissionIsRecheckedOnEveryMethod() {
        assertEquals("not_started", backend.getProbeStreamStatus())
        assertEquals(owner, RootCallerPackageManagerShadow.checkedUid)
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256")
            .digest(installed.signatures!![0].toByteArray()), RootCallerPackageManagerShadow.checkedDigest)
        permissionChecks = 0
        permissionGranted = false
        assertAllMethodsDeny("synthetic_permission_denied")
        assertEquals(10, permissionChecks)
        permissionGranted = true
        assertEquals("not_started", backend.getProbeStreamStatus())
    }

    @Test fun missingSigningMetadataFailsClosed() {
        installed.signingInfo = null
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(installed)
        assertAllMethodsDeny("caller_unavailable")
        assertEquals(0, permissionChecks)
    }

    @Test fun callerUidWithRejectedCertificateCannotInvokeAnyMethod() {
        RootCallerPackageManagerShadow.signerMatches = false
        assertAllMethodsDeny("signer_denied")
        assertEquals(0, permissionChecks)
    }
}

/** Robolectric has no shadow for this UID overload; model only the framework verdict. */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
class RootCallerPackageManagerShadow : ShadowApplicationPackageManager() {
    @Implementation fun hasSigningCertificate(uid: Int, certificate: ByteArray, type: Int): Boolean {
        assertEquals(PackageManager.CERT_INPUT_SHA256, type)
        checkedUid = uid
        checkedDigest = certificate.copyOf()
        return signerMatches
    }
    companion object {
        var signerMatches = true
        var checkedUid = -1
        var checkedDigest: ByteArray? = null
    }
}
