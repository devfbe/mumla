package se.lublin.mumla.channel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * The action-bar "Bluetooth" item, which is where the user's complaint starts: it used to call
 * `enableBluetoothSco()` on the live session and remember nothing, so the next dropped connection
 * -- `HumlaService.onConnectionDisconnected` stops SCO on every one of them -- took the headset
 * away and no reconnect brought it back. It also predates `BLUETOOTH_CONNECT` and never asked for
 * it, which on API 31 and up is a `SecurityException` waiting behind a menu tap.
 *
 * What the item shows and what it writes are both the persistent preference now, so this class
 * pins the item against the stored wish rather than against the audio stack.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentBluetoothTest {

    /**
     * Same contract as `ChannelListFragmentTest.HostActivity` (the accessors are functions, not
     * `var`s, or they would generate the interfaces' own getters), plus a count of the menu
     * invalidations -- the only way the fragment's "the preference moved, redraw the item" effect
     * can be read back.
     */
    class RecordingHostActivity : AppCompatActivity(), HumlaServiceProvider, DatabaseProvider {
        private var bound: IMumlaService? = null
        private val db: MumlaDatabase = mockk(relaxed = true)
        private var invalidations = 0

        fun bind(service: IMumlaService?) {
            bound = service
        }

        fun invalidationCount(): Int = invalidations

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun invalidateOptionsMenu() {
            invalidations++
            super.invalidateOptionsMenu()
        }

        override fun getService(): IMumlaService? = bound
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun getDatabase(): MumlaDatabase = db
    }

    private lateinit var app: Application
    private lateinit var controller: ActivityController<RecordingHostActivity>
    private lateinit var parent: ChannelListFragmentTest.HostParent
    private lateinit var fragment: ChannelListFragment
    private lateinit var service: IMumlaService
    private lateinit var session: IHumlaSession
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)
        ShadowToast.reset()

        service = mockk(relaxed = true)
        session = mockk(relaxed = true)
        every { service.isConnected } returns true
        every { service.HumlaSession() } returns session

        controller = Robolectric.buildActivity(RecordingHostActivity::class.java).setup()
        controller.get().bind(service)
        parent = ChannelListFragmentTest.HostParent()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment()
        fragment.arguments = Bundle().apply { putBoolean("pinned", false) }
        parent.childFragmentManager.beginTransaction().add(fragment, "list").commitNow()
    }

    private val activity: RecordingHostActivity get() = controller.get()

    /** The real menu resource, inflated the way the action bar inflates it. */
    private fun inflatedMenu(): Menu {
        val menu = PopupMenu(activity, View(activity)).menu
        activity.menuInflater.inflate(R.menu.fragment_channel_list, menu)
        return menu
    }

    @Suppress("DEPRECATION")
    private fun prepared(): MenuItem {
        val menu = inflatedMenu()
        fragment.onPrepareOptionsMenu(menu)
        return menu.findItem(R.id.menu_bluetooth)
    }

    @Suppress("DEPRECATION")
    private fun tapBluetooth(): MenuItem {
        val item = prepared()
        fragment.onOptionsItemSelected(item)
        return item
    }

    private fun lastRequestedPermissions(): List<String> =
        shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList() ?: emptyList()

    private fun answerThePermissionDialog(granted: Boolean) {
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission) {
            "nothing asked for a permission, so there is no dialog to answer"
        }
        if (granted) {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        }
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
    }

    @Test
    fun theItemShowsTheStoredWishAndNotTheLiveScoState() {
        // The session is the thing that forgets. Asked for the opposite of the preference on
        // every call, it must not be what the tick comes from.
        every { session.usingBluetoothSco() } returns true

        assertThat(prepared().isChecked).isFalse()

        settings.setBluetoothScoEnabled(true)
        every { session.usingBluetoothSco() } returns false

        assertThat(prepared().isChecked).isTrue()
    }

    @Test
    fun tappingItWithThePermissionStoresTheWish() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val item = tapBluetooth()

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(item.isChecked).isTrue()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun tappingItWithoutThePermissionAsksAndStoresNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val item = tapBluetooth()

        assertThat(lastRequestedPermissions()).contains(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(settings.isBluetoothScoEnabled()).isFalse()
        assertThat(item.isChecked).isFalse()
    }

    @Test
    fun tappingItAgainClearsTheWishWithoutAskingForAnything() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val item = tapBluetooth()

        assertThat(settings.isBluetoothScoEnabled()).isFalse()
        assertThat(item.isChecked).isFalse()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    /**
     * The redraw comes from the preference write, not from a second call in the permission
     * callback: an explicit `invalidateOptionsMenu()` there survived its own mutation, because
     * `onSharedPreferenceChanged` had already done it. It was removed rather than pinned.
     */
    @Test
    fun grantingThePermissionAfterwardsStoresTheWishAndRedrawsTheItem() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        tapBluetooth()
        val before = activity.invalidationCount()

        answerThePermissionDialog(granted = true)

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(activity.invalidationCount()).isGreaterThan(before)
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
        assertThat(prepared().isChecked).isTrue()
    }

    @Test
    fun denyingThePermissionLeavesTheWishOffAndSaysWhatIsMissing() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        tapBluetooth()

        answerThePermissionDialog(granted = false)

        assertThat(settings.isBluetoothScoEnabled()).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(app.getString(R.string.grant_perm_bluetooth))
        assertThat(prepared().isChecked).isFalse()
    }

    @Test
    fun theSettingsScreenAndTheMenuItemStayInSync() {
        // The other writer of this preference is the settings checkbox, in another activity. The
        // fragment learns about it the same way it learns about the user-count preference.
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val before = activity.invalidationCount()

        fragment.onSharedPreferenceChanged(preferences, "some.other.preference")

        assertThat(activity.invalidationCount()).isEqualTo(before)

        fragment.onSharedPreferenceChanged(preferences, Settings.PREF_BLUETOOTH_SCO)

        assertThat(activity.invalidationCount()).isEqualTo(before + 1)
    }

    @Test
    fun theItemNeverTouchesTheAudioStackItself() {
        // Routing is the service's job, off the preference, on every (re)connection. A fragment
        // that also starts SCO directly would be a second source of truth -- and the one that
        // dies with the connection.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        tapBluetooth()
        tapBluetooth()

        verify(exactly = 0) { session.enableBluetoothSco() }
        verify(exactly = 0) { session.disableBluetoothSco() }
    }
}
