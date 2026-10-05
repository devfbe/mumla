package se.lublin.mumla

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.preference.CertificateExportActivity
import se.lublin.mumla.preference.CertificateGenerateActivity
import se.lublin.mumla.preference.CertificateImportActivity
import se.lublin.mumla.preference.CertificateSelectActivity
import se.lublin.mumla.preference.ServerCertificateClearActivity
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.service.MuteTileService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun requestedPermissions(): List<String> =
        pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions!!.toList()

    @Test
    fun theAppSupportsRightToLeftLayouts() {
        val flags = pm.getApplicationInfo(context.packageName, 0).flags

        assertThat(flags and android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL).isNotEqualTo(0)
    }

    /** Layouts mirror under RTL only when they say start/end; left/right stays put. */
    @Test
    fun noLayoutUsesLeftOrRight() {
        val layouts = java.io.File("src/main/res").walk()
            .filter { it.isFile && it.parentFile!!.name.startsWith("layout") && it.extension == "xml" }
            .toList()
        assertThat(layouts).isNotEmpty()
        val offenders = layouts
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (LEFT_RIGHT.containsMatchIn(line)) "${file.parentFile!!.name}/${file.name}:${i + 1}" else null
                }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun requestsBluetoothConnectInsteadOfLegacyBluetooth() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.BLUETOOTH_CONNECT)
        // Change detector: the legacy permission must not come back with a merge.
        assertThat(permissions).doesNotContain(Manifest.permission.BLUETOOTH)
    }

    @Test
    fun requestsForegroundServiceTypesAndBatteryExemption() {
        val permissions = requestedPermissions()

        assertThat(permissions).contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        assertThat(permissions).doesNotContain(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK)
        assertThat(permissions).contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    /**
     * Change detector: these permissions are unused at minSdk 31 and must not come back with a
     * dependency or manifest merge. WRITE_EXTERNAL_STORAGE (maxSdkVersion=29) is not asserted: the
     * framework parser already drops it at sdk 35.
     */
    @Test
    fun obsoletePermissionsAreGone() {
        val permissions = requestedPermissions()

        assertThat(permissions).doesNotContain(Manifest.permission.BROADCAST_STICKY)
        assertThat(permissions).doesNotContain("android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS")
    }

    /**
     * Change detector: chat images go to the gallery through MediaStore, which needs no permission
     * for the app's own rows on API 29+. None of these may come back with a merge.
     */
    @Test
    fun savingImagesNeedsNoStoragePermission() {
        val permissions = requestedPermissions()

        assertThat(permissions).containsNoneOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        )
    }

    @Test
    fun mumlaServiceIsNotExportedAndDeclaresOnlyTheMicrophoneType() {
        val info = pm.getServiceInfo(ComponentName(context, MumlaService::class.java), 0)

        assertThat(info.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
     * Our own components are opened by class. An intent filter on a component nobody else may
     * start only invites implicit intents, which can resolve to another installed Mumla variant
     * that declares the same actions. Exported components keep their filters (launcher, mumble://
     * links, the quick settings tile).
     */
    @Test
    fun noNonExportedComponentOfOursDeclaresAnIntentFilter() {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        val info = pm.getPackageInfo(context.packageName, flags)
        val shadow = shadowOf(pm)
        val components =
            info.activities.orEmpty().map { Triple(it.name, it.exported, shadow::getIntentFiltersForActivity) } +
            info.services.orEmpty().map { Triple(it.name, it.exported, shadow::getIntentFiltersForService) } +
            info.receivers.orEmpty().map { Triple(it.name, it.exported, shadow::getIntentFiltersForReceiver) }
        val ours = components.filter { (name, _, _) -> name.startsWith(OUR_NAMESPACE) }
        assertThat(ours).isNotEmpty()

        val offenders = ours
            .filter { (name, exported, filters) ->
                !exported && filters(ComponentName(context.packageName, name)).isNotEmpty()
            }
            .map { (name, _, _) -> name }
        assertThat(offenders).isEmpty()

        // Not vacuous: the filters of the exported components are seen.
        assertThat(shadow.getIntentFiltersForActivity(ComponentName(context, MumlaActivity::class.java))).isNotEmpty()
        assertThat(shadow.getIntentFiltersForService(ComponentName(context, MuteTileService::class.java))).isNotEmpty()
    }

    /**
     * Change detector: `databases/mumble.db` holds passwords, tokens and the client certificate
     * next to the favourites, and Auto Backup excludes per file, so `backup_rules` excludes the
     * whole data root from cloud backup while leaving device-to-device transfer unrestricted.
     *
     * Only the resource *content* is pinned, resolved by name: Robolectric does not populate
     * `ApplicationInfo.dataExtractionRulesRes`. The manifest wiring is enforced by lint
     * (`error += "DataExtractionRules"` in app/build.gradle.kts).
     */
    @Test
    fun theBackupRulesFileExcludesCloudBackupButNotDeviceTransfer() {
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

    private companion object {
        const val OUR_NAMESPACE = "se.lublin.mumla."

        val LEFT_RIGHT = Regex(
            """android:(layout_)?(margin|padding)(Left|Right)=|""" +
                """android:layout_(alignParent|to|align)(Left|Right)(Of)?=|""" +
                """android:drawable(Left|Right)=|android:(layout_)?gravity="[^"]*\b(left|right)\b""",
        )
    }
}
