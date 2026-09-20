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
 * away and no reconnect brought it back. It also never asked for `BLUETOOTH_CONNECT`, which spec
 * P3 requires before SCO is used and which the store listing has advertised as a Nearby-devices
 * entry since task 2 -- note that it is *not* a crash today: the SDK annotation database puts no
 * permission requirement on `AudioManager.startBluetoothSco()` at all (only `android.bluetooth.*`
 * carries `BLUETOOTH_CONNECT`), so the gap was a promise to the user, not a SecurityException.
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
     *
     * Granularity, so nobody reads more into that counter than it holds: it counts *the call*,
     * not a redraw. Robolectric's action bar does not rebuild the menu off an
     * `invalidateOptionsMenu()`, so what these assertions guarantee is "the fragment asked for
     * the menu to be rebuilt", and what draws the tick afterwards is
     * `onPrepareOptionsMenu` -- which the tests call themselves, through `prepared()`. The two
     * are pinned separately on purpose; at this level there is nothing finer to read.
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

    /**
     * The nine `isChecked` assertions in this class cannot see this, and that is the whole
     * reason it is written out: `MenuItemImpl.setChecked`/`isChecked` store and return the
     * CHECKED flag whether or not the CHECKABLE flag is set, so every one of them stays green
     * with `android:checkable` gone from the item -- while the item draws no tick at all and
     * the user taps "Bluetooth" and gets no confirmation that the stored wish was taken.
     *
     * The attribute predates this task; what this task did was make it load-bearing. The tick
     * used to come from `usingBluetoothSco()` and only ever appeared on a live connection.
     *
     * No other assertion is allowed in front of this one: an earlier failure would shadow it
     * and the coverage would be mis-attributed (spec 4.05).
     */
    @Test
    fun theItemIsCheckableAndNotMerelyRememberingATick() {
        assertThat(prepared().isCheckable).isTrue()
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

    /**
     * The one moment the user most wants this switch is the one the old code refused it: while
     * auto-reconnect is working, `service.isConnected` is false, the fragment is still on screen,
     * and the whole `onOptionsItemSelected` body was behind that guard -- the tap did nothing and
     * said nothing. The wish is a preference; it does not need a session.
     */
    @Test
    fun theItemWorksWhileTheConnectionIsDown() {
        every { service.isConnected } returns false
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val item = tapBluetooth()

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(item.isChecked).isTrue()
        assertThat(prepared().isChecked).isTrue()
    }

    @Test
    fun theItemWorksBeforeTheServiceIsEvenBound() {
        activity.bind(null)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val item = tapBluetooth()

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        assertThat(item.isChecked).isTrue()
        assertThat(prepared().isChecked).isTrue()
    }

    @Test
    fun tappingItConsumesTheEventAndTappingAnythingElseDoesNotReachIt() {
        // The branch sits ahead of the connection guard now, so its own item test is the only
        // thing keeping every other menu item out of it -- and the item still has to be consumed
        // here, or the tap travels on to the activity and toggles nothing twice.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val menu = inflatedMenu()
        @Suppress("DEPRECATION")
        fragment.onPrepareOptionsMenu(menu)

        @Suppress("DEPRECATION")
        val consumed = fragment.onOptionsItemSelected(menu.findItem(R.id.menu_bluetooth))

        assertThat(consumed).isTrue()
        assertThat(settings.isBluetoothScoEnabled()).isTrue()

        @Suppress("DEPRECATION")
        fragment.onOptionsItemSelected(menu.findItem(R.id.menu_mute_button))

        assertThat(settings.isBluetoothScoEnabled()).isTrue()
        verify { session.setSelfMuteDeafState(true, false) }
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
