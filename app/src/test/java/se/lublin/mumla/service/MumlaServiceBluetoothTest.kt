package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.media.AudioDeviceInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.testutil.FakeCommunicationDevices
import se.lublin.humla.testutil.testConnection
import se.lublin.humla.testutil.testRouter
import se.lublin.mumla.Settings
import se.lublin.mumla.chat.IChatMessage
import se.lublin.mumla.testing.createMumlaService

/**
 * The Bluetooth preference drives the router: the service hands the stored wish to it from its
 * first moment and on every change, and the router takes the route whenever a session is
 * synchronized. Only the communication-device seam is faked; the permission, the listener
 * registration and `AudioRouter`'s reconciliation are real.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceBluetoothTest {

    private lateinit var app: Application
    private lateinit var service: MumlaService
    private lateinit var receiver: FakeCommunicationDevices
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        settings = Settings.getInstance(app)
        service = create()
    }

    private fun create(): MumlaService {
        receiver = FakeCommunicationDevices().apply { available[HEADSET_ID] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        // Before create(): HumlaService.onCreate wraps the platform AudioManager when the seam is
        // unset, and the router it builds there is the one that lives for the service.
        return createMumlaService { communicationDevices = receiver }.get()
    }

    /**
     * A synchronized connection. modelHandler stays null, so the superclass hook returns before
     * building an AudioHandler and skips engaging the router; that is done here instead.
     */
    private fun connect() {
        val connection = mockk<HumlaConnection>(relaxed = true)
        every { connection.isConnected } returns true
        every { connection.isSynchronized } returns true
        service.testConnection = connection
        service.testRouter.engage()
    }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    private fun warnings(): List<String> =
        service.messageLog.value.filterIsInstance<IChatMessage.InfoMessage>()
            .filter { it.type == IChatMessage.InfoMessage.Type.WARNING }
            .map { it.body }

    @Test
    fun aHeadsetIsTakenByDefault() {
        assertThat(service.usingBluetoothSco()).isTrue()

        connect()

        assertThat(receiver.selectCalls.size).isEqualTo(1)
    }

    /** The router is engaged by the superclass before this class's hook runs. */
    @Test
    fun aStoredNoIsHonouredFromTheStart() {
        settings.isBluetoothScoEnabled = false
        service = create()

        assertThat(service.usingBluetoothSco()).isFalse()
        connect()
        assertThat(receiver.selectCalls.size).isEqualTo(0)
    }

    /** A denied BLUETOOTH_CONNECT does not gate routing; `setCommunicationDevice` doesn't need it. */
    @Test
    fun theHeadsetIsTakenEvenWithoutThePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        connect()

        assertThat(receiver.selectCalls.size).isEqualTo(1)
    }

    @Test
    fun routingThatSucceedsSaysNothingInTheChatLog() {
        connect()

        assertThat(receiver.selectCalls.size).isEqualTo(1)
        assertThat(warnings()).isEmpty()
    }

    // The tests below only write the preference: MumlaService registers itself as a
    // SharedPreferences listener in onCreate, so this also pins the registration.

    @Test
    fun turningThePreferenceOnWhileConnectedStartsTheHeadset() {
        settings.isBluetoothScoEnabled = false
        connect()
        assertThat(receiver.selectCalls.size).isEqualTo(0)

        settings.isBluetoothScoEnabled = true

        assertThat(receiver.selectCalls.size).isEqualTo(1)
        assertThat(receiver.clearCalls).isEqualTo(0)
    }

    /** The router clears the communication device only when the route is its own. */
    @Test
    fun turningThePreferenceOffWhileConnectedStopsTheHeadset() {
        connect()
        assertThat(receiver.selectCalls.size).isEqualTo(1)

        settings.isBluetoothScoEnabled = false

        assertThat(receiver.clearCalls).isEqualTo(1)
    }

    @Test
    fun turningThePreferenceOffWithNoRouteHeldTouchesNothing() {
        receiver.available.clear()
        connect()

        settings.isBluetoothScoEnabled = false

        assertThat(receiver.clearCalls).isEqualTo(0)
        assertThat(receiver.selectCalls.size).isEqualTo(0)
    }

    /** The router only routes while engaged, so the wish follows the preference at any time. */
    @Test
    fun thePreferenceMovesTheWishButTouchesNothingWhileDisconnected() {
        settings.isBluetoothScoEnabled = false
        assertThat(service.usingBluetoothSco()).isFalse()
        settings.isBluetoothScoEnabled = true
        assertThat(service.usingBluetoothSco()).isTrue()

        assertThat(receiver.selectCalls.size).isEqualTo(0)
        assertThat(receiver.clearCalls).isEqualTo(0)
    }

    @Test
    fun anotherPreferenceDoesNotTouchTheHeadset() {
        settings.isBluetoothScoEnabled = false
        connect()

        preferences().edit().putBoolean(Settings.PREF_PTT_SOUND, true).commit()
        service.onPreferenceChanged(Settings.PREF_PTT_SOUND)

        assertThat(receiver.selectCalls.size).isEqualTo(0)
        assertThat(receiver.clearCalls).isEqualTo(0)
    }

    private companion object {
        const val HEADSET_ID = 7
    }
}
