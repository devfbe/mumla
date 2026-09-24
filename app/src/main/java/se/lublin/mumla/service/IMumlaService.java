package se.lublin.mumla.service;

import java.util.List;

import se.lublin.humla.IHumlaService;

/** Mumla's additions to {@link IHumlaService}. */
public interface IMumlaService extends IHumlaService {
    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    List<IChatMessage> getMessageLog();

    void clearMessageLog();

    void setSuppressNotifications(boolean suppressNotifications);
}
