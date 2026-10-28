package com.gpay.notification.service.push;

import com.gpay.notification.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Default channel until device tokens + FCM credentials exist: logs what would be pushed. */
@Slf4j
@Component
public class LoggingPushSender implements PushSender {

    @Override
    public void send(Notification n) {
        log.info("PUSH userId={} type={} txnId={} title=\"{}\" body=\"{}\"",
                n.getUserId(), n.getType(), n.getTransactionId(), n.getTitle(), n.getBody());
    }
}
