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

package se.lublin.humla.protocol;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import se.lublin.humla.R;
import se.lublin.humla.model.Channel;
import se.lublin.humla.model.IServerSettings;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.ServerSettings;
import se.lublin.humla.model.User;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.MessageFormatter;

/**
 * Handles network messages related to the user-channel tree model: channels, users, messages and
 * permissions.
 *
 * <p><b>Threading.</b> Every message* method runs on the "humla-protocol" thread, the only writer.
 * Getters are called from the main thread and binder threads. Compound read-check-write accesses
 * are safe only because of that single writer; the concurrent maps and volatile fields just keep
 * readers from seeing half-rehashed tables or half-built objects.
 */
public class ModelHandler extends HumlaTCPMessageListener.Stub {
    private static final String TAG = ModelHandler.class.getName();

    /**
     * How far below the root a channel may be placed. The server controls the tree depth and the
     * UI walks it recursively on the main thread, so an unbounded chain would end in a
     * {@code StackOverflowError}. 256 is far above Mumble's default nesting limit (10) and far
     * below the depth that overflows the stack.
     */
    public static final int MAX_CHANNEL_DEPTH = 256;

    /** The id Mumble gives the root channel. */
    public static final int ROOT_CHANNEL_ID = 0;

    private final Context mContext;
    private final Map<Integer, Channel> mChannels;
    private final Map<Integer, User> mUsers;
    private final List<Integer> mLocalMuteHistory;
    private final List<Integer> mLocalIgnoreHistory;
    private final IHumlaObserver mObserver;
    private final HumlaLogger mLogger;
    // Written on the protocol thread, read from the main thread through IHumlaSession; volatile
    // for safe publication.
    private volatile ServerSettings mServerSettings;
    private volatile int mPermissions;
    private volatile int mSession;

    public ModelHandler(Context context, IHumlaObserver observer, HumlaLogger logger,
                        @Nullable List<Integer> localMuteHistory,
                        @Nullable List<Integer> localIgnoreHistory) {
        mContext = context;
        // ConcurrentHashMap: getChannel()/getUser() are read from the main thread while the
        // protocol thread writes; a HashMap read during a rehash can miss a present key.
        mChannels = new ConcurrentHashMap<Integer, Channel>();
        mUsers = new ConcurrentHashMap<Integer, User>();
        mLocalMuteHistory = localMuteHistory;
        mLocalIgnoreHistory = localIgnoreHistory;
        mObserver = observer;
        mLogger = logger;
    }

    public Channel getChannel(int id) {
        return mChannels.get(id);
    }

    public User getUser(int session) {
        return mUsers.get(session);
    }

    public ServerSettings getServerSettings() {
        return mServerSettings;
    }

    /**
     * Creates a stub channel with the given ID.
     * Useful for keeping user references when we get a UserState message before a ChannelState.
     * @param id The channel ID.
     * @return The newly created stub channel.
     */
    private Channel createStubChannel(int id) {
        Channel channel = new Channel(id, false);
        mChannels.put(id, channel);
        return channel;
    }

    /**
     * Whether {@code channel} may be hung under {@code parent}. Refuses a parent that is
     * {@code channel} or one of its descendants (a cycle), and a parent already
     * {@link #MAX_CHANNEL_DEPTH} below the root. Compares by identity: channels with equal ids are
     * equal, but the question is about the linked objects.
     */
    private boolean mayHang(Channel channel, Channel parent) {
        int depth = 0;
        for(Channel above = parent; above != null; above = above.getParent()) {
            if(above == channel) {
                Log.w(TAG, "refusing to make channel " + channel.getId() + " its own ancestor");
                return false;
            }
            if(++depth > MAX_CHANNEL_DEPTH) {
                Log.w(TAG, "refusing to hang channel " + channel.getId() + " deeper than "
                        + MAX_CHANNEL_DEPTH);
                return false;
            }
        }
        return true;
    }

    /**
     * Where a channel goes when {@link #mayHang} refuses the parent its frame names, or
     * {@code null} to leave it where it is.
     *
     * <p>A parentless channel would never heal (the server does not resend its ChannelState) and
     * would be invisible together with its users, since the UI only walks down from the root. So a
     * refused channel without a parent is hung under the root; one that already has a parent keeps
     * it.
     */
    private Channel fallbackParent(Channel channel) {
        if(channel.getParent() != null) return null;
        Channel root = mChannels.get(ROOT_CHANNEL_ID);
        // The root's own frame need not have arrived first.
        if(root == null) root = createStubChannel(ROOT_CHANNEL_ID);
        // The fallback is a hang like any other: a frame naming the root as its own parent must
        // not make the root its own parent.
        return mayHang(channel, root) ? root : null;
    }

    public Map<Integer, Channel> getChannels() {
        return Collections.unmodifiableMap(mChannels);
    }

    public Map<Integer, User> getUsers() {
        return Collections.unmodifiableMap(mUsers);
    }

    /**
     * Returns the current user's permissions.
     * @return The server-wide permissions.
     */
    public int getPermissions() {
        return mPermissions;
    }

    public void clear() {
        mChannels.clear();
        mUsers.clear();
    }

    @Override
    public void messageChannelState(Mumble.ChannelState msg) {
        if(!msg.hasChannelId())
            return;

        Channel channel = mChannels.get(msg.getChannelId());

        final boolean newChannel = channel == null;

        if(channel == null) {
            channel = new Channel(msg.getChannelId(), msg.getTemporary());
            mChannels.put(msg.getChannelId(), channel);
        }

        if(msg.hasName())
            channel.setName(msg.getName());

        if(msg.hasPosition())
            channel.setPosition(msg.getPosition());

        if(msg.hasParent()) {
            // The server can name a parent we have no ChannelState for yet: stub it, the real
            // ChannelState fills in the same object later. Looked up only after the channel
            // itself exists, so a frame whose channel id is its own parent id finds that channel.
            Channel parent = mChannels.get(msg.getParent());
            if(parent == null) parent = createStubChannel(msg.getParent());
            if(!mayHang(channel, parent)) parent = fallbackParent(channel);
            if(parent != null) {
                Channel oldParent = channel.getParent();
                channel.setParent(parent);
                parent.addSubchannel(channel);
                if(oldParent != null) {
                    oldParent.removeSubchannel(channel);
                }
            }
        }

        if(msg.hasDescriptionHash()) {
            channel.setDescriptionHash(msg.getDescriptionHash().toByteArray());
            channel.setDescription(null);
        }

        if(msg.hasDescription()) {
            channel.setDescription(msg.getDescription());
            channel.setDescriptionHash(null);
        }

        if(msg.getLinksCount() > 0) {
            List<Channel> links = new ArrayList<Channel>(msg.getLinksCount());
            for(int link : msg.getLinksList()) {
                links.add(mChannels.get(link));
                // Don't add this channel to the other channel's link list: we get a message for
                // the other channels' links later during server synchronization.
            }
            // One replacement rather than clear-then-add: the main thread must never see the
            // emptied list.
            channel.setLinks(links);
        }

        // Unlike a parent, an unknown linked channel is skipped rather than stubbed.
        if(msg.getLinksRemoveCount() > 0) {
            for(int link : msg.getLinksRemoveList()) {
                Channel linked = mChannels.get(link);
                if(linked == null) continue;
                channel.removeLink(linked);
                linked.removeLink(channel);
            }
        }

        if(msg.getLinksAddCount() > 0) {
            for(int link : msg.getLinksAddList()) {
                Channel linked = mChannels.get(link);
                if(linked == null) continue;
                channel.addLink(linked);
                linked.addLink(channel);
            }
        }

        if(newChannel)
            mObserver.onChannelAdded(channel);
        else
            mObserver.onChannelStateUpdated(channel);
    }

    @Override
    public void messageChannelRemove(Mumble.ChannelRemove msg) {
        final Channel channel = mChannels.get(msg.getChannelId());
        if(channel != null && channel.getId() != 0) {
            mChannels.remove(channel.getId());
            Channel parent = channel.getParent();
            if(parent != null) {
                parent.removeSubchannel(channel);
            }
            mObserver.onChannelRemoved(channel);
        }
    }

    @Override
    public void messagePermissionQuery(Mumble.PermissionQuery msg) {
        if(msg.getFlush())
            for(Channel channel : mChannels.values())
                channel.setPermissions(0);

        final Channel channel = mChannels.get(msg.getChannelId());
        if(channel != null) {
            channel.setPermissions(msg.getPermissions());
            if(msg.getChannelId() == 0) // If we're provided permissions for the root channel, we'll apply these as our server permissions.
                mPermissions = channel.getPermissions();
            mObserver.onChannelPermissionsUpdated(channel);
        }
    }

    @Override
    public void messageUserState(Mumble.UserState msg) {
        User user = mUsers.get(msg.getSession());
        boolean newUser = false;

        User self = mUsers.get(mSession);

        if(user == null) {
            if(msg.hasName()) {
                user = new User(msg.getSession(), msg.getName());
                mUsers.put(msg.getSession(), user);
                newUser = true;
                // Add user to root channel by default; joining into root carries no channel ID.
                Channel root = mChannels.get(0);
                if(root == null) root = createStubChannel(0);
                user.setChannel(root);
            }
            else
                return;
        }

        User actor = null;
        if(msg.hasActor())
            actor = getUser(msg.getActor());

        final User finalUser = user;

        if(msg.hasUserId()) {
            user.setUserId(msg.getUserId());
            // Restore local mute and ignore from history
            if (mLocalMuteHistory != null && mLocalMuteHistory.contains(user.getUserId())) {
                user.setLocalMuted(true);
            }
            if (mLocalIgnoreHistory != null && mLocalIgnoreHistory.contains(user.getUserId())) {
                user.setLocalIgnored(true);
            }
        }

        if(msg.hasHash()) {
            user.setHash(msg.getHash());

            // TODO: re-mute users locally muted in the database; indicate friends.
        }

        if(newUser)
            mLogger.logInfo(mContext.getString(R.string.chat_notify_connected, MessageFormatter.highlightString(user.getName())));

        if(msg.hasSelfDeaf() || msg.hasSelfMute()) {
            if(msg.hasSelfMute())
                user.setSelfMuted(msg.getSelfMute());
            if(msg.hasSelfDeaf())
                user.setSelfDeafened(msg.getSelfDeaf());

            if (self != null) {
                Channel userChan = user.getChannel();
                if (user.getSession() != self.getSession() && userChan != null && userChan.equals(self.getChannel())) {
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_muted_deafened, MessageFormatter.highlightString(user.getName())));
                    else if (user.isSelfMuted())
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_muted, MessageFormatter.highlightString(user.getName())));
                    else
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_now_unmuted, MessageFormatter.highlightString(user.getName())));
                } else if (user.getSession() == self.getSession()) {
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_muted_deafened));
                    else if (user.isSelfMuted())
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_muted));
                    else
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_unmuted));
                }
            }
        }

        if(msg.hasRecording()) {
            user.setRecording(msg.getRecording());

            if(self != null) {
                if(user.getSession() == self.getSession()) {
                    if(user.isRecording())
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_self_recording_started));
                    else
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_self_recording_stopped));
                } else {
                    Channel selfChannel = self.getChannel();
                    // If in a linked channel OR the same channel as the current user, notify the user about recording
                    if(selfChannel != null && (selfChannel.getLinks().contains(selfChannel) || selfChannel.equals(user.getChannel()))) {
                        if(user.isRecording())
                            mLogger.logInfo(mContext.getString(R.string.chat_notify_user_recording_started, MessageFormatter.highlightString(user.getName())));
                        else
                            mLogger.logInfo(mContext.getString(R.string.chat_notify_user_recording_stopped, MessageFormatter.highlightString(user.getName())));
                    }
                }
            }
        }

        if(msg.hasDeaf() || msg.hasMute() || msg.hasSuppress() || msg.hasPrioritySpeaker()) {
            if(msg.hasDeaf())
                user.setDeafened(msg.getDeaf());
            if(msg.hasMute())
                user.setMuted(msg.getMute());
            if(msg.hasSuppress())
                user.setSuppressed(msg.getSuppress());
            if(msg.hasPrioritySpeaker())
                user.setPrioritySpeaker(msg.getPrioritySpeaker());
            // TODO: log mute/deaf changes (see Mumble's Messages.cpp).
        }

        if(msg.hasChannelId()) {
            final Channel channel = mChannels.get(msg.getChannelId());
            if(channel == null) {
                Log.e(TAG, "Invalid channel for user!");
                return; // TODO handle better
            }
            final Channel old = user.getChannel();

            user.setChannel(channel);

            if(!newUser) {
                mObserver.onUserJoinedChannel(finalUser, channel, old);
            }

            Channel sessionChannel = self != null ? self.getChannel() : null;

            if (self != null && sessionChannel != null && old != null && !self.equals(user)) {
                // TODO add logic for other user moving self
                String actorString = actor != null ? MessageFormatter.highlightString(actor.getName()) : mContext.getString(R.string.the_server);
                if(!sessionChannel.equals(channel) && sessionChannel.equals(old)) {
                    if(actor != null && actor.getSession() == user.getSession()) {
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(channel.getName())));
                    } else {
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel_by, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(channel.getName()), actorString));
                    }
                } else if(sessionChannel.equals(channel)) {
                    if(actor != null && actor.getSession() == user.getSession()) {
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel, MessageFormatter.highlightString(user.getName())));
                    } else {
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel_by, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(old.getName()), actorString));
                    }
                }
            }
        }

        if(msg.hasName())
            user.setName(msg.getName());

        if (msg.hasTextureHash()) {
            user.setTextureHash(msg.getTextureHash());
            user.setTexture(null); // clear cached texture when we receive a new hash
        }

        if (msg.hasTexture()) {
            // FIXME: is it reasonable to create a bitmap here? How expensive?
            user.setTexture(msg.getTexture());
            user.setTextureHash(null);
        }

        if(msg.hasCommentHash()) {
            user.setCommentHash(msg.getCommentHash());
            user.setComment(null);
        }

        if(msg.hasComment()) {
            user.setComment(msg.getComment());
            user.setCommentHash(null);
        }

        if (newUser)
            mObserver.onUserConnected(user);
        else
            mObserver.onUserStateUpdated(user);
    }

    @Override
    public void messageUserRemove(Mumble.UserRemove msg) {
        final User user = mUsers.get(msg.getSession());
        final User actor = mUsers.get(msg.getActor());
        final String reason = msg.getReason();

        // TODO: revisit which of session/actor may be absent (see Mumble.proto).
        final String userName = user != null ? user.getName() : "unknown";
        final String actorName = actor != null ? actor.getName() : "unknown";
        if(msg.getSession() == mSession)
            mLogger.logWarning(mContext.getString(msg.getBan() ? R.string.chat_notify_kick_ban_self : R.string.chat_notify_kick_self, MessageFormatter.highlightString(actorName), reason));
        else if(actor != null)
            mLogger.logWarning(mContext.getString(msg.getBan() ? R.string.chat_notify_kick_ban : R.string.chat_notify_kick, MessageFormatter.highlightString(actorName), reason, MessageFormatter.highlightString(userName)));
        else
            mLogger.logInfo(mContext.getString(R.string.chat_notify_disconnected, MessageFormatter.highlightString(userName)));

        if (user != null) {
            user.setChannel(null);
        }
        mObserver.onUserRemoved(user, reason);
    }

    @Override
    public void messagePermissionDenied(final Mumble.PermissionDenied msg) {
        final String reason;
        switch (msg.getType()) {
            case ChannelName:
                reason = mContext.getString(R.string.deny_reason_channel_name);
                break;
            case TextTooLong:
                reason = mContext.getString(R.string.deny_reason_text_too_long);
                break;
            case TemporaryChannel:
                reason = mContext.getString(R.string.deny_reason_no_operation_temp);
                break;
            case MissingCertificate:
                reason = mContext.getString(R.string.deny_reason_no_certificate);
                break;
            case UserName:
                reason = mContext.getString(R.string.deny_reason_invalid_username);
                break;
            case ChannelFull:
                reason = mContext.getString(R.string.deny_reason_channel_full);
                break;
            case NestingLimit:
                reason = mContext.getString(R.string.deny_reason_channel_nesting);
                break;
            default:
                if(msg.hasReason()) reason = mContext.getString(R.string.deny_reason_other, msg.getReason());
                else reason = mContext.getString(R.string.perm_denied);

        }
        mObserver.onPermissionDenied(reason);
    }

    @Override
    public void messageTextMessage(Mumble.TextMessage msg) {
        User sender = mUsers.get(msg.getActor());

        if(sender != null && sender.isLocalIgnored())
            return;

        List<Channel> channels = new ArrayList<Channel>(msg.getChannelIdCount());
        for(int channelId : msg.getChannelIdList()) channels.add(mChannels.get(channelId));
        List<Channel> trees = new ArrayList<Channel>(msg.getTreeIdCount());
        for(int treeId : msg.getTreeIdList()) trees.add(mChannels.get(treeId));
        List<User> users = new ArrayList<User>(msg.getSessionCount());
        for(int userId : msg.getSessionList()) users.add(mUsers.get(userId));

        String actorName = sender != null ? sender.getName() : mContext.getString(R.string.server);

        Message message = new Message(msg.getActor(), actorName, channels, trees, users, msg.getMessage());
        mObserver.onMessageLogged(message);
    }

    @Override
    public void messageServerSync(Mumble.ServerSync msg) {
        mSession = msg.getSession();
        mLogger.logInfo(msg.getWelcomeText());
    }

    @Override
    public void messageServerConfig(Mumble.ServerConfig msg) {
        mServerSettings = new ServerSettings(msg);
    }
}
