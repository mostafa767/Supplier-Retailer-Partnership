package com.equadis.portal.notification;

import com.equadis.portal.directory.RetailerContact;
import com.equadis.portal.offer.DiscountType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Sends the digest as a plain-text e-mail.
 *
 * <p>The e-mail is an invitation, not the full list: a summary per supplier, a few
 * highlights, and a link to the catalogue filtered on "published since", where the retailer
 * can search and page through everything.
 */
@Component
class EmailDigestChannel implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final NotificationProperties properties;
    private final DateTimeFormatter businessDateTime;

    EmailDigestChannel(JavaMailSender mailSender, NotificationProperties properties, Clock clock) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.businessDateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(clock.getZone());
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean isConfiguredFor(RetailerContact retailer) {
        return retailer.contactEmail() != null && !retailer.contactEmail().isBlank();
    }

    @Override
    public void deliver(RetailerContact retailer, Digest digest, UUID deliveryId) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(retailer.contactEmail());
        message.setSubject("You have %d new offer(s) from %d supplier(s)".formatted(digest.totalOffers(), digest.suppliers().size()));
        message.setText(body(retailer, digest, deliveryId));
        mailSender.send(message);
    }

    private String body(RetailerContact retailer, Digest digest, UUID deliveryId) {
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(retailer.name()).append(",\n\n");
        text.append("Your partner suppliers published ").append(digest.totalOffers())
                .append(" new offer(s) since ").append(businessDateTime.format(digest.windowFrom())).append(" (Paris time):\n");
        digest.suppliers().forEach(supplier -> text.append("  - ").append(supplier.supplierName())
                .append(": ").append(supplier.newOffers()).append(" new offer(s)\n"));

        text.append("\nHighlights:\n");
        digest.offers().stream().limit(properties.emailHighlights()).forEach(offer -> text
                .append("  - [").append(offer.supplierName()).append("] ").append(offer.title())
                .append(" - ").append(formatDiscount(offer))
                .append(" (valid ").append(offer.validFrom()).append(" to ").append(offer.validTo()).append(")\n"));

        text.append("\nSee and search all new offers: ").append(properties.catalogueUrl())
                .append("?publishedSince=").append(digest.windowFrom()).append("\n\n");
        text.append("Reference: ").append(deliveryId).append("\n");
        return text.toString();
    }

    private static String formatDiscount(Digest.OfferSummary offer) {
        return offer.discountType() == DiscountType.PERCENTAGE
                ? offer.discountValue().stripTrailingZeros().toPlainString() + "% off"
                : offer.discountValue().toPlainString() + " " + offer.currency() + " off";
    }
}
