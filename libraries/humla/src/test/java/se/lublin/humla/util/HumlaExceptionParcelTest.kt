package se.lublin.humla.util

import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.protobuf.Mumble

@RunWith(RobolectricTestRunner::class)
class HumlaExceptionParcelTest {
    private fun roundTrip(e: HumlaException): HumlaException {
        val parcel = Parcel.obtain()
        try {
            e.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return HumlaException.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun aRejectSurvivesTheParcel() {
        val reject = Mumble.Reject.newBuilder()
            .setType(Mumble.Reject.RejectType.WrongUserPW)
            .setReason("bad password")
            .build()

        val copy = roundTrip(HumlaException(reject))

        assertThat(copy.reason).isEqualTo(HumlaException.HumlaDisconnectReason.REJECT)
        assertThat(copy.reject).isEqualTo(reject)
        assertThat(copy.userRemove).isNull()
        assertThat(copy.message).isEqualTo("Rejected: bad password")
    }

    @Test
    fun aKickSurvivesTheParcel() {
        val kick = Mumble.UserRemove.newBuilder().setSession(3).setReason("bye").build()

        val copy = roundTrip(HumlaException(kick))

        assertThat(copy.reason).isEqualTo(HumlaException.HumlaDisconnectReason.USER_REMOVE)
        assertThat(copy.userRemove).isEqualTo(kick)
        assertThat(copy.reject).isNull()
    }

    @Test
    fun aPlainErrorSurvivesTheParcel() {
        val copy = roundTrip(HumlaException("boom", HumlaException.HumlaDisconnectReason.CONNECTION_ERROR))

        assertThat(copy.reason).isEqualTo(HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)
        assertThat(copy.reject).isNull()
        assertThat(copy.userRemove).isNull()
    }
}
