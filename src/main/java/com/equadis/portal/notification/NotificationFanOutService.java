package com.equadis.portal.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Supplier S has news" -> one PENDING notification per partner retailer and channel.
 *
 * <p>Nothing is sent here: the notifications become due later, so that news can pile up into
 * one digest.
 */
@Service
class NotificationFanOutService {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanOutService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationFanOutService(NotificationRepository notificationRepository, NotificationProperties properties, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return number of new pending notifications created */
    @Transactional(propagation = Propagation.MANDATORY)
    int queueForActivePartnersOf(UUID supplierId) {
        Instant now = clock.instant();
        int created = notificationRepository.insertPendingForActivePartners(supplierId, LocalDate.now(clock), now,
                now.plus(properties.dueDelay(Channel.EMAIL)), now.plus(properties.dueDelay(Channel.WEBHOOK)));
        log.info("Supplier {} has news: {} new pending retailer notification(s)", supplierId, created);
        return created;
    }
}
