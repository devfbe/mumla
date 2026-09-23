package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.content.Context
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
import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.session.CommunicationDevices
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * The line the user's complaint is actually about. `HumlaService.onConnectionDisconnected` stops
 * SCO on every dropped connection -- including the ones auto-reconnect recovers from -- and
 * nothing ever started it again, so "Bluetooth is off after the reconnect" was not a race but a
 * missing line in the recovery path. The service re-reads the stored wish on every
 * synchronization, and follows the preference while it is connected.
 *
 * The plan for this task said there is no JVM seam that reaches
 * `MumlaService.onConnectionSynchronized`, and built the decision as a pure function for that
 * reason. The pure function was the right call, but the premise is wrong twice over:
 * `Robolectric.buildService(MumlaService::class.java).create()` already survives both onCreate
 * implementations (MumlaServiceMediaSessionWiringTest has done it since task 4), and the
 * superclass hook returns *normally* -- it logs and returns -- when `mModelHandler` is null,
 * which is a state its own comment says it has seen in the field. That leaves the second half of
 * `MumlaService.onConnectionSynchronized` reachable without a native audio stack, and with it the
 * one call that fixes the complaint.
 *
 * What is faked here is exactly one object: the `CommunicationDevices` seam, i.e. the four
 * one-line delegations to `AudioManager` inside the humla library. Everything between the hook and
 * it -- the preference, the permission, `isSynchronized()`, and `ScoRouter`'s own wanted-vs-active
 * reconciliation -- is the real code.
 *
 * Task A9b replaced `BluetoothScoReceiver` with `ScoRouter` over `CommunicationDevices`, so the
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

    private fun mumlaField(name: String) =
        MumlaService::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        settings = Settings.getInstance(app)

        receiver = RecordingDevices()
        val controller = Robolectric.buildService(MumlaService::class.java)
        // Before create(): HumlaService.onCreate wraps the platform AudioManager when the seam is
        // unset, and the router it builds there is the one that lives for the service.
        controller.get().communicationDevices = receiver
        service = controller.create().get()
    }

    /** A connection that reports itself synchronized, which is all these two hooks ask it. */
    private fun connect() {
        val connection = mockk<HumlaConnection>(relaxed = true)
        every { connection.isConnected } returns true
        every { connection.isSynchronized } returns true
        humlaField("mConnection").set(service, connection)
        // mModelHandler stays null on purpose: the superclass hook then logs and returns instead
        // of building an AudioHandler over the native stack, and MumlaService's own half runs.
    }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    /** The chat log, which is where the service reports what the user has to act on. */
    private fun warnings(): List<String> =
        service.messageLog.filterIsInstance<IChatMessage.InfoMessage>()
            .filter { it.type == IChatMessage.InfoMessage.Type.WARNING }
            .map { it.body }

    @Test
    fun synchronizingTurnsTheHeadsetBackOnWhenItWasAskedFor() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        service.onConnectionSynchronized()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    @Test
    fun synchronizingLeavesTheHeadsetAloneWhenNobodyAskedForIt() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        service.onConnectionSynchronized()

        assertThat(receiver.startCount()).isEqualTo(0)
    }

    /**
     * Keep asking, stop gating -- the ruling in spec 4.1. The permission is still requested at
     * both places the user can flip the switch, because P3 says so and because the store listing
     * has carried the Nearby-devices entry since task 2; but a denial is an answer about the
     * permission, not about what the user wants. Measured against the SDK's own annotation
     * database: of 26 annotated `AudioManager` members exactly four carry a `RequiresPermission`
     * and `startBluetoothSco()` is not among them, and `BLUETOOTH_CONNECT` appears on 136
     * members, none of them in `android.media`. Gating here took a working headset away from
     * every user who tapped "deny", and before this task the item asked for nothing at all.
     */
    @Test
    fun synchronizingRoutesTheStoredWishEvenWithoutThePermission() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        service.onConnectionSynchronized()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    /**
     * **Two refusal tests lived here and are now unwritable at this level (task A9b).** They drove
     * a `BluetoothScoReceiver` whose `startBluetoothSco`/`stopBluetoothSco` threw
     * `SecurityException`, which `MumlaService.applyBluetoothSco` caught and turned into one chat
     * line. The catch is now unreachable: `AndroidCommunicationDevices` wraps **every**
     * `AudioManager` call one layer down, answers with the value that reads as "no headset", and
     * reports the denial through a constructor callback that has no default - so nothing above it
     * can see a `SecurityException` any more.
     *
     * The property did not move, the layer did:
     * `se.lublin.humla.HumlaServiceBluetoothTest.aPlatformRefusalIsReportedOnceAsAChatLine` drives
     * the real wrapper against an `AudioManager` shadow that refuses, and asserts the one line.
     * `MumlaService.applyBluetoothSco`'s catch is dead code for task A12 to remove with the file.
     */

    /**
     * M1 from the review. The restore used to be the *last* statement of the hook, behind
     * `registerReceiver`, `mHotCorner.setShown(true)` and `setProximitySensorOn(true)`. Anything
     * that throws in front of it -- `WindowManager.addView` and the proximity wake lock both can
     * -- skips it and reproduces the user's complaint exactly: reconnected, and no headset. No
     * triggering case was found in the field (`setShown` checks `canDrawOverlays` and returns
     * early), so this pins an ordering rather than repairing a live defect, and the ordering
     * costs nothing.
     *
     * The exception is not handled by the hook and is not meant to be; what this reads back is
     * what had already happened when it was thrown.
     */
    @Test
    fun aLaterStepThatThrowsDoesNotCostTheHeadset() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        preferences().edit()
            .putString(Settings.PREF_HOT_CORNER_KEY, Settings.ARRAY_HOT_CORNER_TOP_LEFT).commit()
        val hotCorner = mockk<MumlaHotCorner>(relaxed = true)
        every { hotCorner.setShown(any()) } throws RuntimeException("addView refused")
        mumlaField("mHotCorner").set(service, hotCorner)
        connect()

        try {
            service.onConnectionSynchronized()
            throw AssertionError("the hot corner was supposed to throw; this test proves nothing")
        } catch (expected: RuntimeException) {
            // what matters is the state it was thrown in
        }

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    @Test
    fun routingThatSucceedsSaysNothingInTheChatLog() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        service.onConnectionSynchronized()

        assertThat(warnings()).isEmpty()
    }

    // The four tests below never call onSharedPreferenceChanged themselves. MumlaService
    // registers itself on the default SharedPreferences in onCreate, so writing the preference
    // is the whole user gesture, and the registration is pinned along with the switch arm --
    // an explicit call would have held even with the service unregistered.

    @Test
    fun turningThePreferenceOnWhileConnectedStartsTheHeadset() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    /**
     * The wish has to be routed before it can be taken back. `ScoRouter` clears the communication
     * device only when the route it would clear is **its own** SCO route - clearing whatever else
     * the platform chose would take the user off their own speaker or wired headset for a reason
     * they never gave. So the switch is flipped on while connected first, which is the gesture, and
     * the old `stopBluetoothSco()`-on-every-flip is what this no longer does.
     */
    @Test
    fun turningThePreferenceOffWhileConnectedStopsTheHeadset() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()
        settings.setBluetoothScoEnabled(true)
        assertThat(receiver.startCount()).isEqualTo(1)

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(1)
    }

    /** And with no route held, turning it off asks the platform for nothing at all. */
    @Test
    fun turningThePreferenceOffWithNoRouteHeldTouchesNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(0)
        assertThat(receiver.startCount()).isEqualTo(0)
    }

    @Test
    fun aWishWithoutThePermissionStartsTheHeadsetAnyway() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    @Test
    fun thePreferenceTouchesNothingWhileDisconnected() {
        // No connection at all: getBluetoothReceiver() throws when the service is not
        // synchronized, so a hook without the isSynchronized() check would not merely do nothing
        // here, it would take the service down on a settings change.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(0)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    @Test
    fun anotherPreferenceDoesNotTouchTheHeadset() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        preferences().edit().putBoolean(Settings.PREF_PTT_SOUND, true).commit()
        service.onSharedPreferenceChanged(preferences(), Settings.PREF_PTT_SOUND)

        assertThat(receiver.startCount()).isEqualTo(0)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }
}
