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
import se.lublin.humla.audio.BluetoothScoReceiver
import se.lublin.humla.net.HumlaConnection
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
 * What is faked here is exactly one object: the `BluetoothScoReceiver`, whose two methods are
 * one-line delegations to `AudioManager` inside the humla library. Everything between the hook
 * and it -- the preference, the permission, `isSynchronized()`, `getBluetoothReceiver()`'s own
 * synchronization check -- is the real code.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceBluetoothTest {

    /** Reads back the two calls the service makes into the SCO receiver. */
    class RecordingScoReceiver(context: Context, listener: Listener) :
        BluetoothScoReceiver(context, listener) {
        private var starts = 0
        private var stops = 0

        /**
         * An OEM that enforces `BLUETOOTH_CONNECT` on `android.media` although the platform's own
         * annotation database declares it on `android.bluetooth.*` only. Absent from the database
         * is not "never thrown anywhere", which is the whole reason the call is wrapped.
         */
        var refuses = false

        fun startCount(): Int = starts
        fun stopCount(): Int = stops

        override fun startBluetoothSco() {
            starts++
            if (refuses) throw SecurityException("Need BLUETOOTH_CONNECT permission")
        }

        override fun stopBluetoothSco() {
            stops++
            if (refuses) throw SecurityException("Need BLUETOOTH_CONNECT permission")
        }
    }

    private lateinit var app: Application
    private lateinit var service: MumlaService
    private lateinit var receiver: RecordingScoReceiver
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

        service = Robolectric.buildService(MumlaService::class.java).create().get()
        receiver = RecordingScoReceiver(app, service)
        humlaField("mBluetoothReceiver").set(service, receiver)
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
     * And the other side of the wrap: the platform is allowed to be stricter than its own
     * annotations, so the call is caught rather than trusted. Once in the chat log, not once per
     * reconnection -- the hook runs on every synchronization and auto-reconnect can run it a lot.
     */
    @Test
    fun aDeviceThatRefusesScoIsReportedOnceAndDoesNotTakeTheServiceDown() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        receiver.refuses = true
        connect()

        service.onConnectionSynchronized()
        service.onConnectionSynchronized()

        assertThat(receiver.startCount()).isEqualTo(2)
        assertThat(warnings()).containsExactly(app.getString(R.string.bluetooth_sco_refused))
    }

    @Test
    fun aDeviceThatRefusesToStopScoDoesNotTakeTheServiceDownEither() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        settings.setBluetoothScoEnabled(true)
        connect()
        receiver.refuses = true

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(1)
        assertThat(warnings()).containsExactly(app.getString(R.string.bluetooth_sco_refused))
    }

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

    @Test
    fun turningThePreferenceOffWhileConnectedStopsTheHeadset() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        settings.setBluetoothScoEnabled(true)
        connect()

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(1)
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
