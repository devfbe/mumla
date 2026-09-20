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

import org.jetbrains.annotations.Nullable;

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
 * Handles network messages related to the user-channel tree model.
 * This includes channels, users, messages, and permissions.
 * Created by andrew on 18/07/13.
 *
 * <p><b>Threading.</b> Every message* method runs on the "humla-protocol" thread and is the only
 * thing that writes anything here. The getters are called from the main thread through
 * IHumlaSession, and ChannelSearchProvider reaches getChannel/getUsers from a binder thread. That
 * one-writer rule is what makes the compound accesses below safe, not the maps: "look the channel
 * up, and put a new one if it is missing" is a read-check-write that a ConcurrentHashMap does not
 * make atomic either. A second writing thread would have to turn each of those into computeIfAbsent
 * and would still leave messageChannelState's read-modify-write of a Channel racing with itself.
 * The concurrent maps are here so that a reader never sees a half-rehashed table, and the volatile
 * fields so that a reader never sees a half-built object.
 */
public class ModelHandler extends HumlaTCPMessageListener.Stub {
    private static final String TAG = ModelHandler.class.getName();

    /**
     * How far below the root a channel may be placed. The server picks every parent id, so the
     * depth of the tree is server-controlled, and every walk over it recurses once per level -
     * {@link Channel#getSubchannelUserCount()} from {@code ChannelListAdapter} (:438) and
     * {@code constructNodes} (:450) both do, on the main thread, where {@code updateChannels()}
     * catches {@code IllegalStateException} and nothing else. A chain of a few thousand channels
     * is enough to turn that into a {@code StackOverflowError}, which no catch in the tree stops.
     * 256 is far beyond any real tree and far below any stack.
     */
    public static final int MAX_CHANNEL_DEPTH = 256;

    private final Context mContext;
    private final Map<Integer, Channel> mChannels;
    private final Map<Integer, User> mUsers;
    private final List<Integer> mLocalMuteHistory;
    private final List<Integer> mLocalIgnoreHistory;
    private final IHumlaObserver mObserver;
    private final HumlaLogger mLogger;
    // Written on the protocol thread, read from the main thread through IHumlaSession:
    // getServerSettings() (HumlaService:1243) and getPermissions() (:928). An unsafely published
    // ServerSettings reference can be seen half-initialised. mSession is protocol-thread-only
    // today; it is volatile so that the class has one rule rather than two, and
    // GuardedModelVisibilityTest keeps the rule from rotting.
    private volatile ServerSettings mServerSettings;
    private volatile int mPermissions;
    private volatile int mSession;

    public ModelHandler(Context context, IHumlaObserver observer, HumlaLogger logger,
                        @Nullable List<Integer> localMuteHistory,
                        @Nullable List<Integer> localIgnoreHistory) {
        mContext = context;
        // ConcurrentHashMap, not HashMap: getChannel()/getUser() are called from the main thread
        // while the protocol thread puts, and a HashMap read during a rehash returns null for a
        // key that is present (measured: 20 rounds out of 20). getChannel() returning a spurious
        // null makes ChannelListAdapter.updateChannels() skip a root channel silently.
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
     * Whether {@code channel} may be hung under {@code parent}, which is the one thing a
     * {@code ChannelState} frame can ask for that the model cannot represent. Both refusals leave
     * the channel where it is - named, in the map, and reachable, with no parent if it never had
     * one, which is the same state as a channel whose parent has not arrived yet.
     *
     * <p>A refused parent is a tree that is wrong in one place. An accepted one is a process that
     * dies: the walk over the tree recurses per level and catches nothing that an
     * {@code Error} passes through.
     *
     * <p>Two separate refusals, because they are two separate frames:
     * <ul>
     *   <li>{@code parent} is {@code channel} or hangs below it - the frame would make the channel
     *       its own ancestor. One frame is enough ({@code id == parent}), two are enough for a
     *       longer knot, and the walk over a tree with a cycle in it never ends.</li>
     *   <li>{@code parent} already sits {@link #MAX_CHANNEL_DEPTH} below the root. The tree stays
     *       finite, so the walk terminates, but the recursion runs out of stack long before the
     *       server runs out of channel ids.</li>
     * </ul>
     *
     * <p>Identity rather than equality: two {@link Channel} objects with the same id are equal, and
     * the question here is about the objects that are actually linked together.
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
            // The server picks the parent id, and it can name a channel we have no ChannelState
            // for yet. Dereferencing that null killed the process once parsing moved to the
            // humla-protocol thread, which installs no uncaught-exception handler. A stub is what
            // this class already does for an unknown channel on the user path: the real
            // ChannelState lands on the same object later and fills in its name.
            //
            // The lookup belongs here and not above the block that creates the channel: a frame
            // whose channel id IS its parent id would miss there, and createStubChannel would then
            // put a fresh nameless channel over the one this frame has just named and announced.
            Channel parent = mChannels.get(msg.getParent());
            if(parent == null) parent = createStubChannel(msg.getParent());
            if(mayHang(channel, parent)) {
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
                // Don't add this channel to the other channel's link list- this update occurs on
                // server synchronization, and we will get a message for the other channels' links
                // later.
            }
            // One replacement rather than a clear followed by adds: the main thread must not see
            // the emptied list in between.
            channel.setLinks(links);
        }

        // Unlike a parent, a link to a channel we do not know is skipped rather than stubbed: it
        // is an attribute of a channel we already have, not a place in the tree that the rest
        // hangs off. Channel.addLink/removeLink tolerate the null, the second call in each pair
        // would not.
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
                // Add user to root channel by default. This works because for some reason, we don't get a channel ID when the user joins into root.
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

            /*
             * TODO:
             * - Check if user is local muted in database, if so re-mute them here
             * - Check if user is friend, if so indicate
             */
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

//            if(self != null && ((user.getChannelId() == self.getChannelId()) || (actor.getSessionId() == self.getSessionId()))) {
//                if(user.getSessionId() == self.getSessionId()) {
//                    if(msg.hasMute() && msg.hasDeaf() && user.isMuted() && user.isDeafened()) {
//                        mLogger.logInfo();
//                    }
//                }
//            }

            /*
             * TODO: logging
             * Base this off of Messages.cpp:353
             */
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

            // Notify the user of other users' current channel changes
            if (self != null && sessionChannel != null && old != null && !self.equals(user)) {
                // TODO add logic for other user moving self
                String actorString = actor != null ? MessageFormatter.highlightString(actor.getName()) : mContext.getString(R.string.the_server);
                if(!sessionChannel.equals(channel) && sessionChannel.equals(old)) {
                    // User moved out of self's channel
                    if(actor != null && actor.getSession() == user.getSession()) {
                        // By themselves
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(channel.getName())));
                    } else {
                        // By external actor
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_left_channel_by, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(channel.getName()), actorString));
                    }
                } else if(sessionChannel.equals(channel)) {
                    // User moved into self's channel
                    if(actor != null && actor.getSession() == user.getSession()) {
                        // By themselves
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel, MessageFormatter.highlightString(user.getName())));
                    } else {
                        // By external actor
                        mLogger.logInfo(mContext.getString(R.string.chat_notify_user_joined_channel_by, MessageFormatter.highlightString(user.getName()), MessageFormatter.highlightString(old.getName()), actorString));
                    }
                }
            }

            /*
             * TODO: logging
             * Base this off of Messages.cpp:454
             */
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

        // TODO? hackish fix of crash that was happening. The original logic
        // here is possible flawed, regarding presence of session, actor etc.
        // Consult Mumble.proto and official client?
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
