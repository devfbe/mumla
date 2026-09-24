package se.lublin.mumla.chat

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Message
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.HumlaEvent.DenyType

/** The exact English lines the chat log and the refusal dialog show. */
@RunWith(RobolectricTestRunner::class)
class NoticeFormatterTest {

    private val formatter = NoticeFormatter(ApplicationProvider.getApplicationContext())

    private fun hl(name: String) = "<font color=\"#33b5e5\">$name</font>"

    private fun format(notice: HumlaEvent.Notice) = formatter.format(notice)

    @Test
    fun aLogMessageIsShownAsItIs() {
        assertThat(format(HumlaEvent.LogMessage(HumlaEvent.Level.WARNING, "<b>raw</b>"))).isEqualTo("<b>raw</b>")
    }

    @Test
    fun namesAreHighlightedInTheServiceColor() {
        assertThat(NoticeFormatter.highlight("Ann")).isEqualTo(hl("Ann"))
        assertThat(NoticeFormatter.highlight(null)).isEqualTo(hl("null"))
    }

    @Test
    fun comingAndGoing() {
        assertThat(format(HumlaEvent.UserJoinedServer("Ann"))).isEqualTo("${hl("Ann")} connected.")
        assertThat(format(HumlaEvent.UserLeftServer("Ann"))).isEqualTo("${hl("Ann")} disconnected.")
        assertThat(format(HumlaEvent.UserLeftServer(null))).isEqualTo("${hl("unknown")} disconnected.")
    }

    @Test
    fun kicksAndBans() {
        assertThat(format(HumlaEvent.SelfKicked("Mod", "spam", ban = false)))
            .isEqualTo("You were kicked from the server by ${hl("Mod")}: spam.")
        assertThat(format(HumlaEvent.SelfKicked(null, "spam", ban = true)))
            .isEqualTo("You were kicked and banned from the server by ${hl("unknown")}: spam.")
        assertThat(format(HumlaEvent.UserKicked("Ann", "Mod", "spam", ban = false)))
            .isEqualTo("${hl("Ann")} was kicked from the server by ${hl("Mod")}: spam.")
        assertThat(format(HumlaEvent.UserKicked(null, "Mod", "spam", ban = true)))
            .isEqualTo("${hl("unknown")} was kicked and banned from the server by ${hl("Mod")}: spam.")
    }

    @Test
    fun ourOwnMuteState() {
        assertThat(format(HumlaEvent.SelfMuteChanged(muted = true, deafened = true))).isEqualTo("Muted and deafened.")
        assertThat(format(HumlaEvent.SelfMuteChanged(muted = true, deafened = false))).isEqualTo("Muted.")
        assertThat(format(HumlaEvent.SelfMuteChanged(muted = false, deafened = false))).isEqualTo("Unmuted.")
        // Deafened without muted does not happen; it reads as unmuted, as it always did.
        assertThat(format(HumlaEvent.SelfMuteChanged(muted = false, deafened = true))).isEqualTo("Unmuted.")
    }

    @Test
    fun somebodyElsesMuteState() {
        assertThat(format(HumlaEvent.UserMuteChanged("Ann", muted = true, deafened = true)))
            .isEqualTo("${hl("Ann")} is now muted and deafened.")
        assertThat(format(HumlaEvent.UserMuteChanged("Ann", muted = true, deafened = false)))
            .isEqualTo("${hl("Ann")} is now muted.")
        assertThat(format(HumlaEvent.UserMuteChanged("Ann", muted = false, deafened = false)))
            .isEqualTo("${hl("Ann")} is now unmuted.")
    }

    @Test
    fun recording() {
        assertThat(format(HumlaEvent.SelfRecordingChanged(true))).isEqualTo("Recording started")
        assertThat(format(HumlaEvent.SelfRecordingChanged(false))).isEqualTo("Recording stopped")
        assertThat(format(HumlaEvent.UserRecordingChanged("Ann", true))).isEqualTo("${hl("Ann")} started recording.")
        assertThat(format(HumlaEvent.UserRecordingChanged("Ann", false))).isEqualTo("${hl("Ann")} stopped recording.")
    }

    @Test
    fun movesOutOfOurChannel() {
        assertThat(format(HumlaEvent.UserLeftChannel("Ann", "Games", "Ann", byThemselves = true)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")}.")
        assertThat(format(HumlaEvent.UserLeftChannel("Ann", "Games", "Mod", byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")} by ${hl("Mod")}.")
        assertThat(format(HumlaEvent.UserLeftChannel("Ann", "Games", null, byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved to ${hl("Games")} by the server.")
    }

    @Test
    fun movesIntoOurChannel() {
        assertThat(format(HumlaEvent.UserEnteredChannel("Ann", "Games", "Ann", byThemselves = true)))
            .isEqualTo("${hl("Ann")} entered channel.")
        assertThat(format(HumlaEvent.UserEnteredChannel("Ann", "Games", "Mod", byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved in from ${hl("Games")} by ${hl("Mod")}.")
        assertThat(format(HumlaEvent.UserEnteredChannel("Ann", "Games", null, byThemselves = false)))
            .isEqualTo("${hl("Ann")} moved in from ${hl("Games")} by the server.")
    }

    @Test
    fun refusals() {
        val expected = mapOf(
            DenyType.CHANNEL_NAME to "Denied: Invalid channel name.",
            DenyType.TEXT_TOO_LONG to "Denied: Text message too long.",
            DenyType.TEMPORARY_CHANNEL to "Denied: Operation not permitted in temporary channel.",
            DenyType.MISSING_CERTIFICATE to "You need a certificate to perform this operation.",
            DenyType.USER_NAME to "Invalid username.",
            DenyType.CHANNEL_FULL to "Channel is full.",
            DenyType.NESTING_LIMIT to "Channel nesting limit reached.",
            DenyType.OTHER to "Permission denied.",
        )
        for ((type, text) in expected) {
            // The server's reason only matters for the refusals the client does not explain itself.
            assertThat(formatter.denial(HumlaEvent.PermissionDenied(type, null))).isEqualTo(text)
        }
        assertThat(formatter.denial(HumlaEvent.PermissionDenied(DenyType.CHANNEL_FULL, "full")))
            .isEqualTo("Channel is full.")
        assertThat(formatter.denial(HumlaEvent.PermissionDenied(DenyType.OTHER, "not you")))
            .isEqualTo("Reason: not you")
    }

    @Test
    fun aMessageWithoutASenderIsFromTheServer() {
        val fromAnn = Message(2, "Ann", emptyList(), emptyList(), emptyList(), "hi")
        val fromServer = Message(0, null, emptyList(), emptyList(), emptyList(), "motd")

        assertThat(formatter.senderName(fromAnn)).isEqualTo("Ann")
        assertThat(formatter.senderName(fromServer)).isEqualTo("Server")
    }
}
