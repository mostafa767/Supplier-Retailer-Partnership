package com.equadis.portal.shared.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The single source of time for the whole application.
 *
 * <p>Two kinds of time exist in this domain and are never mixed:
 * <ul>
 *     <li><b>moments</b> (created, published, audit) - {@link java.time.Instant} /
 *         {@code TIMESTAMPTZ}, obtained with {@code clock.instant()};</li>
 *     <li><b>business calendar dates</b> (partnership period, offer validity) -
 *         {@link java.time.LocalDate} / {@code DATE}, in the Europe/Paris calendar.</li>
 * </ul>
 * The only bridge between them is "what day is it today?", answered by
 * {@code LocalDate.now(clock)} - and the clock's zone is Europe/Paris, so 00:30 in Paris is
 * already "tomorrow" even though it is still "yesterday" in UTC.
 *
 * <p>Code never calls {@code LocalDate.now()} / {@code Instant.now()} directly: they would use
 * the server's zone and could not be controlled by tests ("is renewal allowed on day 30 but
 * refused on day 31?"). SQL never uses {@code CURRENT_DATE} either; "today" is passed as a
 * parameter.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${portal.business-zone:Europe/Paris}") ZoneId businessZone) {
        return Clock.system(businessZone);
    }
}
