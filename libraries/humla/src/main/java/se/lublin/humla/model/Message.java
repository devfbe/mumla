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

package se.lublin.humla.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A text message from a Mumble server.
 * NOTE: Always prefer getActorName(). getActor() cannot be relied on, as the actor may no longer be
 * on the server.
 */
public class Message implements IMessage {
    // Final: built once, then read from any thread; the constructor publishes it safely.
    private final int mActor;
    private final String mActorName;
    private final List<Channel> mChannels;
    private final List<Channel> mTrees;
    private final List<User> mUsers;
    private final String mMessage;
    private final long mReceivedTime;

    public Message(String message) {
        this(-1, null, new ArrayList<Channel>(), new ArrayList<Channel>(), new ArrayList<User>(),
                message);
    }

    public Message(int actor, String actorName, List<Channel> channels, List<Channel> trees, List<User> users, String message) {
        mActor = actor;
        mActorName = actorName;
        mChannels = channels;
        mTrees = trees;
        mUsers = users;
        mMessage = message;
        mReceivedTime = new Date().getTime();
    }
    @Override
    public int getActor() {
        return mActor;
    }

    @Override
    public String getActorName() {
        return mActorName;
    }

    @Override
    public List<Channel> getTargetChannels() {
        return Collections.unmodifiableList(mChannels);
    }

    @Override
    public List<Channel> getTargetTrees() {
        return Collections.unmodifiableList(mTrees);
    }

    @Override
    public List<User> getTargetUsers() {
        return Collections.unmodifiableList(mUsers);
    }

    @Override
    public String getMessage() {
        return mMessage;
    }

    @Override
    public long getReceivedTime() {
        return mReceivedTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Message message = (Message) o;

        if (mActor != message.mActor) return false;
        if (mReceivedTime != message.mReceivedTime) return false;
        if (mActorName != null ? !mActorName.equals(message.mActorName) : message.mActorName != null)
            return false;
        if (mChannels != null ? !mChannels.equals(message.mChannels) : message.mChannels != null)
            return false;
        if (mMessage != null ? !mMessage.equals(message.mMessage) : message.mMessage != null)
            return false;
        if (mTrees != null ? !mTrees.equals(message.mTrees) : message.mTrees != null) return false;
        if (mUsers != null ? !mUsers.equals(message.mUsers) : message.mUsers != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = mActor;
        result = 31 * result + (mActorName != null ? mActorName.hashCode() : 0);
        result = 31 * result + (mChannels != null ? mChannels.hashCode() : 0);
        result = 31 * result + (mTrees != null ? mTrees.hashCode() : 0);
        result = 31 * result + (mUsers != null ? mUsers.hashCode() : 0);
        result = 31 * result + (mMessage != null ? mMessage.hashCode() : 0);
        result = 31 * result + (int) (mReceivedTime ^ (mReceivedTime >>> 32));
        return result;
    }

    /**
     * The type of message this object represents.
     * @deprecated
     */
    public enum Type {
        INFO,
        WARNING,
        TEXT_MESSAGE
    }
}
