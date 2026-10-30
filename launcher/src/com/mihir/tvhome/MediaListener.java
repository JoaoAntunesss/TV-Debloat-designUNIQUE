package com.mihir.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only lets an app read what is playing (MediaSessionManager.getActiveSessions)
 * if it has notification access, which comes with a listener like this one. Granted over ADB:
 *   cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.MediaListener
 */
public class MediaListener extends NotificationListenerService { }
