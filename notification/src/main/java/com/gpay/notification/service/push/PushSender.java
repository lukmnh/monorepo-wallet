package com.gpay.notification.service.push;

import com.gpay.notification.entity.Notification;

/**
 * Device push channel (FCM / APNs). The inbox row is the source of truth; push is best-effort,
 * so an implementation may drop a message but must never throw back into the caller's transaction.
 */
public interface PushSender {
    void send(Notification notification);
}
