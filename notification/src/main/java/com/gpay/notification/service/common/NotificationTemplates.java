package com.gpay.notification.service.common;

import com.gpay.notification.constant.NotificationType;
import com.gpay.notification.dto.NotificationDTO.PaymentEvent;
import com.gpay.notification.entity.Notification;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

/**
 * User-facing copy (Bahasa Indonesia) for each notification type.
 * Text is rendered once at ingest and stored, so the inbox shows what the push said even if copy changes later.
 */
public final class NotificationTemplates {
    private static final Locale ID = Locale.of("id", "ID");

    private NotificationTemplates() {}

    public static Notification topupSuccess(PaymentEvent e) {
        return build(e, e.userId(), NotificationType.TOPUP_SUCCESS, "Top up berhasil",
                "Saldo " + formatAmount(e.amount(), e.currency()) + " sudah masuk ke GPay kamu.");
    }

    public static Notification transferSent(PaymentEvent e) {
        return build(e, e.userId(), NotificationType.TRANSFER_SENT, "Transfer berhasil",
                "Kamu berhasil mengirim " + formatAmount(e.amount(), e.currency()) + ".");
    }

    public static Notification transferReceived(PaymentEvent e) {
        return build(e, e.counterpartyUserId(), NotificationType.TRANSFER_RECEIVED, "Dana masuk",
                "Kamu menerima transfer " + formatAmount(e.amount(), e.currency()) + ".");
    }

    /** IDR → "Rp1.250.000" (decimals only when non-zero: "Rp10.000,50"); other currencies → "USD 12,50". */
    static String formatAmount(BigDecimal amount, String currency) {
        // DecimalFormat is not thread-safe: one instance per call
        String number = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(ID)).format(amount);
        return "IDR".equals(currency) ? "Rp" + number : currency + " " + number;
    }

    private static Notification build(PaymentEvent e, UUID recipient, NotificationType type, String title, String body) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .eventId(e.eventId())
                .userId(recipient)
                .type(type)
                .title(title)
                .body(body)
                .amount(e.amount())
                .currency(e.currency())
                .transactionId(e.transactionId())
                .createdAt(e.occurredAt())
                .build();
    }
}
