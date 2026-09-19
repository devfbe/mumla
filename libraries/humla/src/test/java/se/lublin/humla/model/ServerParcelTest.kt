package se.lublin.humla.model

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Proves that Robolectric + Truth work in this module by exercising a real Android class. */
@RunWith(RobolectricTestRunner::class)
class ServerParcelTest {

    @Test
    fun `server survives a parcel round trip`() {
        val original = Server(7L, "Home", "mumble.example.org", 64738, "alice", "s3cret")
        val parcel = Parcel.obtain()
        original.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)

        val copy = Server.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertThat(copy.id).isEqualTo(7L)
        assertThat(copy.name).isEqualTo("Home")
        assertThat(copy.host).isEqualTo("mumble.example.org")
        assertThat(copy.port).isEqualTo(64738)
        assertThat(copy.username).isEqualTo("alice")
        assertThat(copy.password).isEqualTo("s3cret")
    }
}
