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

        fun startCount(): Int = starts
        fun stopCount(): Int = stops

        override fun startBluetoothSco() {
            starts++
        }

        override fun stopBluetoothSco() {
            stops++
        }
    }

    private lateinit var app: Application
    private lateinit var service: MumlaService
    private lateinit var receiver: RecordingScoReceiver
    private lateinit var settings: Settings

    private fun humlaField(name: String) =
        Class.forName("se.lublin.humla.HumlaService").getDeclaredField(name)
            .apply { isAccessible = true }

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

    @Test
    fun synchronizingDoesNotRouteAWishWhosePermissionWasRevoked() {
        settings.setBluetoothScoEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        service.onConnectionSynchronized()

        assertThat(receiver.startCount()).isEqualTo(0)
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
    fun aWishWhosePermissionIsGoneStopsTheHeadsetRatherThanStartingIt() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        connect()

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(0)
        assertThat(receiver.stopCount()).isEqualTo(1)
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
