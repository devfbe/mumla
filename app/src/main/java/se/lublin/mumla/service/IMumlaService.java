package se.lublin.mumla.service;

import java.util.List;

import kotlinx.coroutines.flow.StateFlow;

import se.lublin.humla.IHumlaService;

/** Mumla's additions to {@link IHumlaService}. */
public interface IMumlaService extends IHumlaService {
    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    /** The chat history, newest last; a new list per change. */
    StateFlow<List<IChatMessage>> getMessageLog();

    void clearMessageLog();

    void setSuppressNotifications(boolean suppressNotifications);
}
