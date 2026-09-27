/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.channel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.humla.model.UserStats
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.model.WhisperTargetUsers
import se.lublin.humla.model.localVolumeKey
import se.lublin.humla.model.localVolumeScope
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.Settings
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.util.changes

/** The channel list's rows while the session is synchronized, and the channel we are in. */
data class ChannelTree(val rows: List<ChannelRow>, val ownChannel: Int?)

/** What a channel's menu shows besides the items that depend on permissions. */
data class ChannelMenuState(
    val hasDescription: Boolean,
    val isPinned: Boolean,
    val isOwn: Boolean,
    val isLinkedToOwn: Boolean,
    val isListening: Boolean,
)

/** What a user's menu shows: the user, whether it is us, and our permissions over them. */
data class UserMenuState(
    val user: UserState,
    val isSelf: Boolean,
    val serverPermissions: Int,
    val channelPermissions: Int,
)

/**
 * The channel list of the connected server: its rows, built off the main thread from each snapshot
 * (a burst of snapshots builds once), the talk states to paint over them, and every action its rows
 * and menus offer. Main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // One action per menu item.
class ChannelTreeViewModel(
    private val sessions: SessionManager,
    private val repository: MumlaRepository,
    private val pinnedOnly: Boolean,
    showUserCount: Flow<Boolean>,
    private val settings: Settings,
    buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val expanded = MutableStateFlow<Map<Int, Boolean>>(emptyMap())

    /** The model of the synchronized session; null otherwise. */
    private val model: Flow<ServerState?> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            combine(session.state, session.model) { state, model -> model.takeIf { state == SessionState.Connected } }
        }
    }

    /** The channels the tree is rooted at: the pinned ones, as they were when the list was built. */
    private val roots: Flow<List<Int>> = if (!pinnedOnly) {
        flowOf(listOf(ServerState.ROOT_CHANNEL_ID))
    } else {
        sessions.session.flatMapLatest { session ->
            val server = session?.targetServer ?: return@flatMapLatest flowOf(emptyList())
            flow { emit(repository.pinnedChannels.of(server.id).filterNotNull().first().toList()) }
                .onStart { emit(emptyList()) }
        }
    }

    val tree: StateFlow<ChannelTree?> = combine(model, roots, expanded, showUserCount, ::TreeInput)
        .conflate()
        .map { input ->
            input.model?.let { model ->
                ChannelTree(channelRows(model, input.roots, input.expanded, input.showUserCount), model.self?.channel)
            }
        }
        .flowOn(buildDispatcher)
        .stateIn(viewModelScope, WHILE_SHOWN, null)

    val talkStates: StateFlow<Map<Int, TalkState>> =
        sessions.session.flatMapLatest { it?.talkStates ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, WHILE_SHOWN, emptyMap())

    private class TreeInput(
        val model: ServerState?,
        val roots: List<Int>,
        val expanded: Map<Int, Boolean>,
        val showUserCount: Boolean,
    )

    private val connected: IHumlaSession? get() = sessions.connected
    private val current: ServerState? get() = connected?.model?.value

    fun setExpanded(channel: Int, expand: Boolean) = expanded.update { it + (channel to expand) }

    fun channelName(channel: Int): String? = current?.channel(channel)?.name

    /** Moves us into [channel]; false, with nothing sent, if the server said we may not enter it. */
    fun join(channel: Int): Boolean {
        val session = connected
        val allowed = session?.model?.value?.channel(channel)?.canEnter != false
        if (allowed) session?.actions?.joinChannel(channel)
        return allowed
    }

    /** Our permissions in [channel] as they change; 0 until the server says, and without a session. */
    fun permissions(channel: Int): Flow<Int> = model.map { it?.permissionsIn(channel) ?: 0 }

    fun requestPermissions(channel: Int) {
        connected?.actions?.requestPermissions(channel)
    }

    fun channelMenuState(channel: Int): ChannelMenuState? {
        val model = current
        val state = model?.channel(channel) ?: return null
        val own = model.self?.channel
        val server = connected?.targetServer
        return ChannelMenuState(
            hasDescription = state.description != null || state.hasDescriptionHash,
            isPinned = server != null && repository.pinnedChannels.isPinned(server.id, channel),
            isOwn = channel == own,
            isLinkedToOwn = own != null && own in state.links,
            isListening = channel in model.self?.listening.orEmpty(),
        )
    }

    fun userMenuState(session: Int): UserMenuState? = userMenuStateOf(current, session)

    /** [session]'s menu state, live; null once they are gone from the model. */
    fun userMenuStates(session: Int): Flow<UserMenuState?> = model.map { userMenuStateOf(it, session) }

    private fun userMenuStateOf(model: ServerState?, session: Int): UserMenuState? {
        val user = model?.user(session) ?: return null
        return UserMenuState(
            user = user,
            isSelf = session == model.selfSession,
            serverPermissions = model.permissions,
            channelPermissions = model.permissionsIn(user.channel),
        )
    }

    /** The channels a user can be moved to, in tree order. */
    fun channels(): List<ChannelState> = current?.flatten().orEmpty()

    fun removeChannel(channel: Int) {
        connected?.actions?.removeChannel(channel)
    }

    fun setPinned(channel: Int, pinned: Boolean) {
        val server = connected?.targetServer ?: return
        repository.pinnedChannels.setPinned(server.id, channel, pinned)
    }

    /** Links [channel] with ours, or unlinks it. */
    fun setLinked(channel: Int, linked: Boolean) {
        val session = connected ?: return
        val own = session.model.value?.self?.channel ?: return
        if (linked) session.actions.linkChannels(own, channel) else session.actions.unlinkChannels(own, channel)
    }

    fun unlinkAll(channel: Int) {
        connected?.actions?.unlinkAllChannels(channel)
    }

    fun setListening(channel: Int, listen: Boolean) {
        connected?.actions?.setListening(channel, listen)
    }

    /**
     * Whispers to [channel]; with hold-to-whisper on, only arms it, ready for the hold button.
     * False if the server has no voice target slot left.
     */
    fun shout(channel: Int, includeLinked: Boolean, includeSubchannels: Boolean): Boolean {
        val session = connected
        val target = session?.model?.value?.channel(channel) ?: return true
        return session.actions.whisperTo(
            WhisperTargetChannel(target, includeLinked, includeSubchannels, null),
            activate = !settings.isHoldToWhisper,
        )
    }

    /**
     * Whispers to the user at [session]; with hold-to-whisper on, only arms it. False if the
     * server has no voice target slot left, or true without a session to whisper to.
     */
    fun whisperToUser(session: Int): Boolean {
        val (humla, user) = userOf(session) ?: return true
        return humla.actions.whisperTo(
            WhisperTargetUsers(listOf(session), user.name),
            activate = !settings.isHoldToWhisper,
        )
    }

    fun kickBan(session: Int, reason: String, ban: Boolean) {
        connected?.actions?.kickBanUser(session, reason, ban)
    }

    fun setMuteDeaf(session: Int, mute: Boolean, deaf: Boolean) {
        connected?.actions?.setMuteDeafState(session, mute, deaf)
    }

    fun setPrioritySpeaker(session: Int, priority: Boolean) {
        connected?.actions?.setPrioritySpeaker(session, priority)
    }

    fun moveUser(session: Int, channel: Int) {
        connected?.actions?.moveUser(session, channel)
    }

    fun resetComment(session: Int) {
        connected?.actions?.setUserComment(session, "")
    }

    fun register(session: Int) {
        connected?.actions?.registerUser(session)
    }

    /** Mutes [session] on this device, and remembers it for a registered user of a saved server. */
    fun setLocalMuted(session: Int, muted: Boolean) {
        val (humla, user) = userOf(session) ?: return
        humla.actions.setLocalMuted(session, muted)
        remember(humla, user) { server, id ->
            if (muted) addLocalMutedUser(server, id) else removeLocalMutedUser(server, id)
        }
    }

    /** Ignores [session]'s messages on this device, remembered like [setLocalMuted]. */
    fun setLocalIgnored(session: Int, ignored: Boolean) {
        val (humla, user) = userOf(session) ?: return
        humla.actions.setLocalIgnored(session, ignored)
        remember(humla, user) { server, id ->
            if (ignored) addLocalIgnoredUser(server, id) else removeLocalIgnoredUser(server, id)
        }
    }

    /** Plays [session] at [volume] on this device, live, without storing it. */
    fun previewLocalVolume(session: Int, volume: Float) {
        connected?.actions?.setLocalVolume(session, volume)
    }

    /** Plays [session] at [volume] on this device and stores it for anyone of the same identity. */
    fun setLocalVolume(session: Int, volume: Float) {
        val (humla, user) = userOf(session) ?: return
        humla.actions.setLocalVolume(session, volume)
        val key = localVolumeKey(user, humla.targetServer?.localVolumeScope) ?: return
        repository.launchIo { setLocalVolume(key, volume) }
    }

    /** [channel]'s description, or null while only its hash is known. */
    fun description(channel: Int): String? = current?.channel(channel)?.description

    /** [session]'s connection statistics, asked for now and every few seconds while collected. */
    fun userStats(session: Int): Flow<UserStats> = channelFlow {
        val humla = connected ?: return@channelFlow
        launch(start = CoroutineStart.UNDISPATCHED) {
            humla.events.filterIsInstance<HumlaEvent.UserStatsReceived>()
                .filter { it.stats.session == session }
                .collect { send(it.stats) }
        }
        while (true) {
            humla.actions.requestUserStats(session)
            delay(STATS_REFRESH_MILLIS)
        }
    }

    private fun userOf(session: Int): Pair<IHumlaSession, UserState>? {
        val humla = connected
        val user = humla?.model?.value?.user(session) ?: return null
        return humla to user
    }

    /** Stores a local choice about a registered [user] of a saved server, off the main thread. */
    private fun remember(
        session: IHumlaSession,
        user: UserState,
        write: se.lublin.mumla.db.MumlaDatabase.(Long, Int) -> Unit,
    ) {
        val server = session.targetServer ?: return
        if (user.userId < 0 || !server.isSaved) return
        repository.launchIo { write(server.id, user.userId) }
    }

    companion object {
        private const val STATS_REFRESH_MILLIS = 5_000L

        /** No rows are built while no screen shows them, e.g. with the app in the background. */
        private val WHILE_SHOWN = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L)

        fun create(app: Application, pinnedOnly: Boolean): ChannelTreeViewModel {
            val preferences = PreferenceManager.getDefaultSharedPreferences(app)
            val showUserCount = preferences.changes(Settings.SHOW_USER_COUNT.key)
                .map { Settings.getInstance(app).shouldShowUserCount }
                .onStart { emit(Settings.getInstance(app).shouldShowUserCount) }
            return ChannelTreeViewModel(
                SessionManager.get(app),
                MumlaRepository.get(app),
                pinnedOnly,
                showUserCount,
                Settings.getInstance(app),
            )
        }
    }
}
