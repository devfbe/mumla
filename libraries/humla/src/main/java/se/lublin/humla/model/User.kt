/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.humla.model

import com.google.protobuf.ByteString

class User @JvmOverloads constructor(session: Int = 0, name: String? = null) : IUser, Comparable<User> {
    private var mSession = session
    private var mId = -1
    private var mName: String? = name
    private var mComment: String? = null
    private var mCommentHash: ByteString? = null
    private var mTexture: ByteString? = null
    private var mTextureHash: ByteString? = null
    private var mHash: String? = null

    private var mMuted = false
    private var mDeafened = false
    private var mSuppressed = false

    private var mSelfMuted = false
    private var mSelfDeafened = false

    private var mPrioritySpeaker = false
    private var mRecording = false

    private var mChannel: Channel? = null

    private var mTalkState: TalkState = TalkState.PASSIVE

    // Local state
    private var mLocalMuted = false
    private var mLocalIgnored = false

    /**
     * The number of samples normally available from the user. A Kotlin property rather than a
     * getter/setter pair because `AudioOutputSpeech` (Kotlin) reads and writes it as one; the
     * Java-visible names are unchanged.
     */
    var averageAvailable = 0f

    override fun getSession(): Int = mSession

    override fun getChannel(): Channel? = mChannel

    /**
     * Changes the user's channel, removing them from their last channel (if set).
     * @param channel The user's new channel.
     */
    fun setChannel(channel: Channel?) {
        mChannel?.removeUser(this)
        mChannel = channel
        channel?.addUser(this)
    }

    override fun getUserId(): Int = mId

    fun setUserId(id: Int) {
        mId = id
    }

    override fun getName(): String? = mName

    fun setName(name: String?) {
        mName = name
    }

    override fun getComment(): String? = mComment

    fun setComment(comment: String?) {
        mComment = comment
    }

    override fun getCommentHash(): ByteArray? = mCommentHash?.toByteArray()

    fun setCommentHash(commentHash: ByteString?) {
        mCommentHash = commentHash
    }

    override fun getTexture(): ByteArray? = mTexture?.toByteArray()

    fun setTexture(texture: ByteString?) {
        mTexture = texture
    }

    override fun getTextureHash(): ByteArray? = mTextureHash?.toByteArray()

    fun setTextureHash(textureHash: ByteString?) {
        mTextureHash = textureHash
    }

    override fun getHash(): String? = mHash

    fun setHash(hash: String?) {
        mHash = hash
    }

    override fun isMuted(): Boolean = mMuted

    fun setMuted(muted: Boolean) {
        mMuted = muted
    }

    override fun isDeafened(): Boolean = mDeafened

    fun setDeafened(deafened: Boolean) {
        mDeafened = deafened
    }

    override fun isSuppressed(): Boolean = mSuppressed

    fun setSuppressed(suppressed: Boolean) {
        mSuppressed = suppressed
    }

    override fun isSelfMuted(): Boolean = mSelfMuted

    fun setSelfMuted(selfMuted: Boolean) {
        mSelfMuted = selfMuted
    }

    override fun isSelfDeafened(): Boolean = mSelfDeafened

    fun setSelfDeafened(selfDeafened: Boolean) {
        mSelfDeafened = selfDeafened
    }

    override fun isPrioritySpeaker(): Boolean = mPrioritySpeaker

    fun setPrioritySpeaker(prioritySpeaker: Boolean) {
        mPrioritySpeaker = prioritySpeaker
    }

    override fun isRecording(): Boolean = mRecording

    fun setRecording(recording: Boolean) {
        mRecording = recording
    }

    override fun isLocalMuted(): Boolean = mLocalMuted

    override fun setLocalMuted(muted: Boolean) {
        mLocalMuted = muted
    }

    override fun isLocalIgnored(): Boolean = mLocalIgnored

    override fun setLocalIgnored(ignored: Boolean) {
        mLocalIgnored = ignored
    }

    override fun getTalkState(): TalkState = mTalkState

    fun setTalkState(talkState: TalkState) {
        mTalkState = talkState
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return mSession == (other as User).mSession
    }

    override fun hashCode(): Int = mId

    /**
     * Orders case-insensitively by name, with nameless users first. The Java original dereferenced
     * both names and threw for a user whose `UserState` carried none - reachable through
     * [Channel.addUser].
     */
    override fun compareTo(other: User): Int =
        (mName ?: "").lowercase().compareTo((other.getName() ?: "").lowercase())
}
