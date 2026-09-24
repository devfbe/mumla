package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.media.AudioDeviceInfo
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.session.AudioRouter
import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.session.CommunicationDevices
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * The line the user's complaint is actually about. `HumlaService.onConnectionDisconnected` stops
 * SCO on every dropped connection -- including the ones auto-reconnect recovers from -- and
 * nothing ever started it again, so "Bluetooth is off after the reconnect" was not a race but a
 * missing line in the recovery path. The stored preference is the wish, the service hands it to
 * the router from its first moment and on every change, and the router takes the route whenever a
 * session is synchronized. Since the audio chooser, the preference defaults to on: a connected
 * Bluetooth headset is used without being asked for, as in the phone app.
 *
 * `Robolectric.buildService(MumlaService::class.java).create()` survives both onCreate
 * implementations, and the superclass `onConnectionSynchronized` returns *normally* when
 * `mModelHandler` is null, so the service is reachable without a native audio stack.
 *
 * What is faked here is exactly one object: the `CommunicationDevices` seam, i.e. the one-line
 * delegations to `AudioManager` inside the humla library. Everything between the preference and
 * it -- the permission, the listener registration, and `AudioRouter`'s own reconciliation -- is
 * the real code.
 *
 * Task A9b replaced `BluetoothScoReceiver` with a router over `CommunicationDevices`, so the
 * calls this reads back are `select`/`clear` rather than `startBluetoothSco`/`stopBluetoothSco`.
 * Two tests went with that change and are named where they went, below.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceBluetoothTest {

    /** Reads back the route calls the service makes, with one headset present to route to. */
    class RecordingDevices : CommunicationDevices {
        /** device id -> AudioDeviceInfo type, in the order the platform would report them. */
        val available = linkedMapOf(HEADSET_ID to AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val selectCalls = mutableListOf<Int>()
        var clearCalls = 0
        private var selectedId: Int? = null
        private var listener: (() -> Unit)? = null

        fun startCount(): Int = selectCalls.size
        fun stopCount(): Int = clearCalls

        override fun available(): List<CommunicationDevice> =
            available.map { (id, type) -> CommunicationDevice(id, type, "") }

        override fun select(id: Int): Boolean {
            selectCalls += id
            selectedId = id
            return true
        }

        override fun clear() {
            clearCalls++
            selectedId = null
        }

        override fun setCommunicationMode(on: Boolean) = Unit

        override fun current(): CommunicationDevice? =
            selectedId?.let { id -> available[id]?.let { CommunicationDevice(id, it, "") } }

        override fun setOnChangedListener(listener: (() -> Unit)?) {
            this.listener = listener
        }

        companion object {
            const val HEADSET_ID = 7
        }
    }

    private lateinit var app: Application
    private lateinit var service: MumlaService
    private lateinit var receiver: RecordingDevices
    private lateinit var settings: Settings

    private fun humlaField(name: String) =
        Class.forName("se.lublin.humla.HumlaService").getDeclaredField(name)
            .apply { isAccessible = true }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)
        service = create()
    }

    private fun create(): MumlaService {
        receiver = RecordingDevices()
        val controller = Robolectric.buildService(MumlaService::class.java)
        // Before create(): HumlaService.onCreate wraps the platform AudioManager when the seam is
        // unset, and the router it builds there is the one that lives for the service.
        controller.get().communicationDevices = receiver
        return controller.create().get()
    }

    /**
     * A connection that reports itself synchronized, and the route the superclass takes for a
     * synchronized session. mModelHandler stays null on purpose: the superclass hook then logs and
     * returns instead of building an AudioHandler over the native stack, and MumlaService's own
     * half runs. That early return also skips engaging the router - which takes no route before a
     * session exists - so it is done here, the way a synchronized session does it.
     */
    private fun connect() {
        val connection = mockk<HumlaConnection>(relaxed = true)
        every { connection.isConnected } returns true
        every { connection.isSynchronized } returns true
        humlaField("mConnection").set(service, connection)
        (humlaField("mRouter").get(service) as AudioRouter).engage()
    }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    /** The chat log, which is where the service reports what the user has to act on. */
    private fun warnings(): List<String> =
        service.messageLog.filterIsInstance<IChatMessage.InfoMessage>()
            .filter { it.type == IChatMessage.InfoMessage.Type.WARNING }
            .map { it.body }

    /** The user's requirement: a connected Bluetooth headset is used without being asked for. */
    @Test
    fun aHeadsetIsTakenByDefault() {
        assertThat(service.usingBluetoothSco()).isTrue()

        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    /**
     * The service follows the stored preference from its first moment, not from the first
     * synchronization: the router is engaged by the superclass before any line of this class's
     * hook runs, so a wish that only arrived there would route the old one first and take it
     * back a moment later.
     */
    @Test
    fun aStoredNoIsHonouredFromTheStart() {
        settings.setBluetoothScoEnabled(false)
        service = create()

        assertThat(service.usingBluetoothSco()).isFalse()
        connect()
        assertThat(receiver.startCount()).isEqualTo(0)
    }

    /**
     * Keep asking, stop gating -- the ruling in spec 4.1. The permission is still requested where
     * the user switches the preference on, because P3 says so and because the store listing has
     * carried the Nearby-devices entry since task 2; but a denial is an answer about the
     * permission, not about what the user wants. Measured against the SDK's own annotation
     * database: of 26 annotated `AudioManager` members exactly four carry a `RequiresPermission`,
     * and `BLUETOOTH_CONNECT` appears on 136 members, none of them in `android.media` --
     * `setCommunicationDevice` included.
     */
    @Test
    fun theHeadsetIsTakenEvenWithoutThePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    /**
     * **The ordering test that stood here is gone with the line it ordered.** The restore used to
     * be `enableBluetoothSco()` in this class's `onConnectionSynchronized`, and it had to stand
     * ahead of the hot corner and the proximity sensor, either of which can throw. The wish is now
     * pushed into the router whenever the preference changes, and the route is taken by the
     * superclass's `onConnectionSynchronized` - which runs, and engages the router, before any
     * statement of this class's hook. Nothing here can throw in front of it any more; the restore
     * itself is pinned by `HumlaServiceBluetoothTest.bluetoothScoIsRestartedAfterAReconnect`.
     *
     * The two refusal tests that lived here before task A9b moved to
     * `se.lublin.humla.HumlaServiceBluetoothTest.aPlatformRefusalIsReportedOnceAsAChatLine`.
     */

    @Test
    fun routingThatSucceedsSaysNothingInTheChatLog() {
        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(warnings()).isEmpty()
    }

    // The tests below never call onSharedPreferenceChanged themselves. MumlaService registers
    // itself on the default SharedPreferences in onCreate, so writing the preference is the whole
    // user gesture, and the registration is pinned along with the switch arm -- an explicit call
    // would have held even with the service unregistered.

    @Test
    fun turningThePreferenceOnWhileConnectedStartsTheHeadset() {
        settings.setBluetoothScoEnabled(false)
        connect()
        assertThat(receiver.startCount()).isEqualTo(0)

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    /**
     * The router clears the communication device only when the route it would clear is **its
     * own** - clearing whatever else the platform chose would take the user off their own speaker
     * or wired headset for a reason they never gave.
     */
    @Test
    fun turningThePreferenceOffWhileConnectedStopsTheHeadset() {
        connect()
        assertThat(receiver.startCount()).isEqualTo(1)

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(1)
    }

    /** And with no route held, turning it off asks the platform for nothing at all. */
    @Test
    fun turningThePreferenceOffWithNoRouteHeldTouchesNothing() {
        receiver.available.clear()
        connect()

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(0)
        assertThat(receiver.startCount()).isEqualTo(0)
    }

    /**
     * No session, no route - but the wish still moves. The old hook checked `isSynchronized()` and
     * dropped a change made while disconnected, so the next session routed the stale wish; the
     * router only routes while engaged, so the wish can follow the preference at any time.
     */
    @Test
    fun thePreferenceMovesTheWishButTouchesNothingWhileDisconnected() {
        settings.setBluetoothScoEnabled(false)
        assertThat(service.usingBluetoothSco()).isFalse()
        settings.setBluetoothScoEnabled(true)
        assertThat(service.usingBluetoothSco()).isTrue()

        assertThat(receiver.startCount()).isEqualTo(0)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    @Test
    fun anotherPreferenceDoesNotTouchTheHeadset() {
        settings.setBluetoothScoEnabled(false)
        connect()

        preferences().edit().putBoolean(Settings.PREF_PTT_SOUND, true).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_PTT_SOUND)

        assertThat(receiver.startCount()).isEqualTo(0)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }
}
