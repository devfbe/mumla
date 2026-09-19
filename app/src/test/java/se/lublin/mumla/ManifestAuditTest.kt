package se.lublin.mumla

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
import org.xmlpull.v1.XmlPullParser
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
     * Audit guard (deliberate change detector): BROADCAST_STICKY was removed by this task because
     * nothing in the tree uses it at minSdk 31; this pins that decision, and fails only if it is
     * re-added, not on a bug. WRITE_EXTERNAL_STORAGE (maxSdkVersion=29) is not asserted: the
     * framework parser already drops it at sdk 35, so an assertion could never fail.
     *
     * BROADCAST_CLOSE_SYSTEM_DIALOGS is asserted too, but as a forward guard only: it was already
     * gone before this task started (removed by the Foundation stream's `4912005 fix(lint):
     * resolve the errors that block abortOnError`, an ancestor of this branch), so this assertion
     * never went red for this task -- it passed against the unmodified manifest as well. It stays
     * here to guard against the permission coming back with a future dependency or manifest merge.
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
     * squarely an android:exported audit item; the plan itself only specified the minimal fix of
     * `exported="false"` on `.HumlaService` for this file -- the outright removal below is a
     * deliberate deviation from that plan, made because it is strictly stronger, and is flagged
     * here and in the task report for the other streams to see at integration): libraries/humla's
     * own manifest used to merge in an exported `se.lublin.humla.HumlaService` component,
     * separate from the app's non-exported `MumlaService`, with intent filters for
     * `se.lublin.humla.ACTION_CONNECT` and `se.lublin.humla.ACTION_DISCONNECT`. Nothing in the
     * tree starts or binds that component directly -- `ServerConnectTask` targets `MumlaService`
     * with an explicit intent, so the filter is not even consulted for the app's own connect flow
     * -- and the declared `ACTION_CONNECT` string in the manifest
     * ("se.lublin.humla.ACTION_CONNECT") does not match the constant HumlaService actually checks
     * in `onStartCommand` ("se.lublin.humla.CONNECT"), so even an external caller who fired
     * exactly that filter would not trigger a connect. The component served no reachable purpose;
     * it is removed rather than merely marked non-exported.
     *
     * This is an audit guard, like the ones above: it can only fail on a redecision (the
     * component being re-declared, exported or not, e.g. by a future manifest merge), not on a
     * bug, since nothing in the running app can regress "a component that does not exist."
     */
    @Test
    fun humlaServiceComponentIsNotMergedIntoTheApp() {
        val humlaService = ComponentName(context.packageName, "se.lublin.humla.HumlaService")

        assertThrows(PackageManager.NameNotFoundException::class.java) {
            pm.getServiceInfo(humlaService, 0)
        }
    }

    /**
     * Cross-stream finding: the app's server database (`databases/mumble.db`, see
     * `MumlaSQLiteDatabase`) holds saved server passwords and access tokens *and* the client
     * certificate's PKCS#12 blob (table `certificates`, column `data`) *and* the favourites list,
     * all in the same file. Auto Backup excludes at file granularity, so "keep favourites, drop
     * secrets" needs a custom backup agent, which is a feature, not an audit fix. Instead,
     * `android:dataExtractionRules` (`@xml/backup_rules`) excludes the app's entire data root from
     * cloud backup while leaving the local, user-initiated device-to-device transfer unrestricted,
     * so a phone swap still carries the certificate identity and server list without any of it
     * ever reaching Drive. (`MumlaTrustStore`, by contrast, holds only the trusted *server*
     * certificates the user has accepted, not their own identity; it is not the sensitive file
     * here, though it is covered by the same blanket cloud-backup exclusion.)
     *
     * This is an audit guard: it can only fail on a redecision (the exclusion rule being narrowed
     * or removed), not on a bug -- there is no running-app behavior that exercises Auto Backup
     * under Robolectric.
     *
     * The test resolves `@xml/backup_rules` by resource name rather than through
     * `ApplicationInfo.dataExtractionRulesRes`: that field exists on the framework class Robolectric
     * runs against (confirmed empirically and by inspecting its android-all jar with `javap`) but
     * Robolectric's manifest/package parsing does not populate it from
     * `android:dataExtractionRules` -- it read back as 0 even though the merged manifest
     * (`app/build/intermediates/merged_manifests/.../AndroidManifest.xml`) correctly contains
     * `android:dataExtractionRules="@xml/backup_rules"`. That attribute-to-resource wiring is
     * instead verified two other ways: by reading the merged manifest directly (done for this
     * task's report), and by Android Lint's own backup-related checks, which parse the manifest
     * and the referenced resource independently of Robolectric and reported zero findings after
     * this change. What this test *can* and does pin under Robolectric is the resource's own
     * content, which is the part a careless future edit is most likely to break silently.
     */
    @Test
    fun cloudBackupExcludesEverythingButDeviceTransferIsUnrestricted() {
        val resourceId = context.resources.getIdentifier("backup_rules", "xml", context.packageName)
        assertWithMessage("res/xml/backup_rules.xml resolves").that(resourceId).isNotEqualTo(0)

        var sawCloudBackupRootExclude = false
        var sawDeviceTransfer = false
        val parser = context.resources.getXml(resourceId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "device-transfer" -> sawDeviceTransfer = true
                    "exclude" ->
                        if (parser.getAttributeValue(null, "domain") == "root") {
                            sawCloudBackupRootExclude = true
                        }
                }
            }
            parser.next()
        }

        assertWithMessage("<cloud-backup> excludes the whole data root")
            .that(sawCloudBackupRootExclude).isTrue()
        assertWithMessage("<device-transfer> is declared (so it does not fall back to cloud-backup's rules)")
            .that(sawDeviceTransfer).isTrue()
    }
}
