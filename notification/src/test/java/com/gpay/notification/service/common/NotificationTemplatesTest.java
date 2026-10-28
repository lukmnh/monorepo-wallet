package com.gpay.notification.service.common;

import com.gpay.notification.constant.NotificationType;
import com.gpay.notification.constant.PaymentEventType;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;
import com.gpay.notification.entity.Notification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationTemplatesTest {
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();

    @Test
    void formatsRupiahWithDotGroupingAndOptionalDecimals() {
        assertEquals("Rp10.000", NotificationTemplates.formatAmount(new BigDecimal("10000.00"), "IDR"));
        assertEquals("Rp1.250.000", NotificationTemplates.formatAmount(new BigDecimal("1250000"), "IDR"));
        assertEquals("Rp10.000,5", NotificationTemplates.formatAmount(new BigDecimal("10000.50"), "IDR"));
        assertEquals("USD 12,5", NotificationTemplates.formatAmount(new BigDecimal("12.50"), "USD"));
    }

    @Test
    void topupGoesToPayerWithAmountInBody() {
        Notification n = NotificationTemplates.topupSuccess(event(PaymentEventType.TOPUP_SUCCEEDED, null));
        assertEquals(SENDER, n.getUserId());
        assertEquals(NotificationType.TOPUP_SUCCESS, n.getType());
        assertEquals("Saldo Rp100.000 sudah masuk ke GPay kamu.", n.getBody());
    }

    @Test
    void transferNotifiesSenderAndRecipientSeparately() {
        PaymentEvent e = event(PaymentEventType.TRANSFER_SUCCEEDED, RECIPIENT);

        Notification sent = NotificationTemplates.transferSent(e);
        Notification received = NotificationTemplates.transferReceived(e);

        assertEquals(SENDER, sent.getUserId());
        assertEquals("Kamu berhasil mengirim Rp100.000.", sent.getBody());
        assertEquals(RECIPIENT, received.getUserId());
        assertEquals(NotificationType.TRANSFER_RECEIVED, received.getType());
        assertEquals("Kamu menerima transfer Rp100.000.", received.getBody());
        assertEquals(e.occurredAt(), received.getCreatedAt());
    }

    private static PaymentEvent event(PaymentEventType type, UUID counterparty) {
        return new PaymentEvent(UUID.randomUUID(), type, UUID.randomUUID(), SENDER, counterparty,
                new BigDecimal("100000.00"), "IDR", LocalDateTime.of(2026, 10, 10, 9, 30));
    }
}
