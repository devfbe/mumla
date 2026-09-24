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
 * The Bluetooth preference drives the router: the service hands the stored wish to it from its
 * first moment and on every change, and the router takes the route whenever a session is
 * synchronized. Only the `CommunicationDevices` seam is faked; the permission, the listener
 * registration and `AudioRouter`'s reconciliation are real.
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
     * A synchronized connection. mModelHandler stays null, so the superclass hook returns before
     * building an AudioHandler and skips engaging the router; that is done here instead.
     */
    private fun connect() {
        val connection = mockk<HumlaConnection>(relaxed = true)
        every { connection.isConnected } returns true
        every { connection.isSynchronized } returns true
        humlaField("mConnection").set(service, connection)
        (humlaField("mRouter").get(service) as AudioRouter).engage()
    }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(app)

    private fun warnings(): List<String> =
        service.messageLog.filterIsInstance<IChatMessage.InfoMessage>()
            .filter { it.type == IChatMessage.InfoMessage.Type.WARNING }
            .map { it.body }

    @Test
    fun aHeadsetIsTakenByDefault() {
        assertThat(service.usingBluetoothSco()).isTrue()

        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    /** The router is engaged by the superclass before this class's hook runs. */
    @Test
    fun aStoredNoIsHonouredFromTheStart() {
        settings.setBluetoothScoEnabled(false)
        service = create()

        assertThat(service.usingBluetoothSco()).isFalse()
        connect()
        assertThat(receiver.startCount()).isEqualTo(0)
    }

    /** A denied BLUETOOTH_CONNECT does not gate routing; `setCommunicationDevice` doesn't need it. */
    @Test
    fun theHeadsetIsTakenEvenWithoutThePermission() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
    }

    @Test
    fun routingThatSucceedsSaysNothingInTheChatLog() {
        connect()

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(warnings()).isEmpty()
    }

    // The tests below only write the preference: MumlaService registers itself as a
    // SharedPreferences listener in onCreate, so this also pins the registration.

    @Test
    fun turningThePreferenceOnWhileConnectedStartsTheHeadset() {
        settings.setBluetoothScoEnabled(false)
        connect()
        assertThat(receiver.startCount()).isEqualTo(0)

        settings.setBluetoothScoEnabled(true)

        assertThat(receiver.startCount()).isEqualTo(1)
        assertThat(receiver.stopCount()).isEqualTo(0)
    }

    /** The router clears the communication device only when the route is its own. */
    @Test
    fun turningThePreferenceOffWhileConnectedStopsTheHeadset() {
        connect()
        assertThat(receiver.startCount()).isEqualTo(1)

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(1)
    }

    @Test
    fun turningThePreferenceOffWithNoRouteHeldTouchesNothing() {
        receiver.available.clear()
        connect()

        settings.setBluetoothScoEnabled(false)

        assertThat(receiver.stopCount()).isEqualTo(0)
        assertThat(receiver.startCount()).isEqualTo(0)
    }

    /** The router only routes while engaged, so the wish follows the preference at any time. */
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
