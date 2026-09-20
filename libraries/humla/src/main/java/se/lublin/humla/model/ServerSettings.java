package se.lublin.humla.model;

import se.lublin.humla.protobuf.Mumble;

public class ServerSettings implements IServerSettings{
    // Final rather than volatile: written once in the constructor and never again, which is the
    // stronger of the two guarantees and the one ModelHandler's volatile mServerSettings reference
    // needs in order to mean anything. GuardedModelVisibilityTest demands one or the other.
    private final boolean mAllowHtml;
    private final int mMessageLength;
    private final int mImageMessageLength;
    private final int mMaxBandwidth;
    private final int mMaxUsers;
    private final String mWelcomeText;

    public ServerSettings(Mumble.ServerConfig msg){
        mAllowHtml = msg.getAllowHtml();
        mMessageLength = msg.getMessageLength();
        mImageMessageLength = msg.getImageMessageLength();
        mMaxBandwidth = msg.getMaxBandwidth();
        mMaxUsers = msg.getMaxUsers();
        mWelcomeText = msg.getWelcomeText();
    }

    @Override
    public boolean getAllowHtml() {
        return mAllowHtml;
    }

    @Override
    public int getMessageLength() {
        return mMessageLength;
    }

    @Override
    public int getImageMessageLength() {
        return mImageMessageLength;
    }

    @Override
    public int getMaxBandwidth() {
        return mMaxBandwidth;
    }

    @Override
    public int getMaxUsers() {
        return mMaxUsers;
    }

    @Override
    public String getWelcomeText() {
        return mWelcomeText;
    }
}
