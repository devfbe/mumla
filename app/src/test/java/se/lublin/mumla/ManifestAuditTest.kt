package se.lublin.mumla

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.lublin.mumla.preference.CertificateExportActivity
import se.lublin.mumla.preference.CertificateGenerateActivity
import se.lublin.mumla.preference.CertificateImportActivity
import se.lublin.mumla.preference.CertificateSelectActivity
import se.lublin.mumla.preference.ServerCertificateClearActivity
import se.lublin.mumla.service.MumlaService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun requestedPermissions(): List<String> =
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()

    @Test
    fun requestsBluetoothConnectInsteadOfLegacyBluetooth() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.BLUETOOTH_CONNECT)
        // Audit guard (deliberate change detector): the legacy permission was removed in the
        // targetSdk 36 audit and must not come back with a merge.
        assertThat(permissions).doesNotContain(Manifest.permission.BLUETOOTH)
    }

    @Test
    fun requestsForegroundServiceTypesAndBatteryExemption() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
        assertThat(permissions).contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    /**
     * Audit guard (deliberate change detector): these two permissions were removed because
     * nothing in the tree uses them at minSdk 31; the test pins that audit decision.
     * WRITE_EXTERNAL_STORAGE (maxSdkVersion=29) is not asserted: the framework parser already
     * drops it at sdk 35, so an assertion could never fail.
     */
    @Test
    fun obsoletePermissionsAreGone() {
        val permissions = requestedPermissions()

        assertThat(permissions).doesNotContain(Manifest.permission.BROADCAST_STICKY)
        assertThat(permissions).doesNotContain("android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS")
    }

    @Test
    fun mumlaServiceIsNotExportedAndDeclaresMicrophoneAndMediaPlayback() {
        val info = pm.getServiceInfo(ComponentName(context, MumlaService::class.java), 0)

        // The microphone bit is declared today already; it comes first so that a failure of
        // this test on the old manifest proves Robolectric populates foregroundServiceType.
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertThat(info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            .isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        assertThat(info.exported).isFalse()
    }

    @Test
    fun certificateActivitiesAreNotExported() {
        val activities = listOf(
            CertificateSelectActivity::class.java,
            CertificateImportActivity::class.java,
            CertificateExportActivity::class.java,
            CertificateGenerateActivity::class.java,
            ServerCertificateClearActivity::class.java,
        )

        for (activity in activities) {
            val info = pm.getActivityInfo(ComponentName(context, activity), 0)
            assertWithMessage(activity.simpleName).that(info.exported).isFalse()
        }
    }

    /**
     * Cross-stream finding (not in any stream's file-ownership table, taken here because it is
     * squarely an android:exported audit item): libraries/humla's own manifest used to merge in
     * an exported `se.lublin.humla.HumlaService` component, separate from the app's non-exported
     * `MumlaService`, with intent filters for `se.lublin.humla.ACTION_CONNECT` and
     * `se.lublin.humla.ACTION_DISCONNECT`. Nothing in the tree starts or binds that component
     * directly -- `ServerConnectTask` targets `MumlaService` with an explicit intent, so the
     * filter is not even consulted for the app's own connect flow -- and the declared
     * `ACTION_CONNECT` string in the manifest ("se.lublin.humla.ACTION_CONNECT") does not match
     * the constant HumlaService actually checks in `onStartCommand`
     * ("se.lublin.humla.CONNECT"), so even a external caller who fires exactly that filter would
     * not trigger a connect. The component served no reachable purpose; it is removed rather
     * than merely marked non-exported. This is a positive test, not a guard: it fails if a future
     * manifest change re-declares the component, exported or not.
     */
    @Test
    fun humlaServiceComponentIsNotMergedIntoTheApp() {
        val humlaService = ComponentName(context.packageName, "se.lublin.humla.HumlaService")

        assertThrows(PackageManager.NameNotFoundException::class.java) {
            pm.getServiceInfo(humlaService, 0)
        }
    }

    /**
     * Cross-stream finding: both the app and the (now removed) library service declaration set
     * `allowBackup="true"` with no backup rules, so Android's Auto Backup would upload the
     * server database (saved passwords, access tokens) and the client-certificate trust store
     * to the user's Google account without the user ever choosing to. A voice client's stored
     * certificate is a per-server identity credential; backing it up to Drive is not appropriate
     * for this app. This assertion is positive, not a guard: it fails whenever the flag is set.
     */
    @Test
    fun backupIsDisabledSoCredentialsAndCertificatesAreNotUploaded() {
        val appInfo = pm.getApplicationInfo(context.packageName, 0)

        assertThat(appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
    }
}
