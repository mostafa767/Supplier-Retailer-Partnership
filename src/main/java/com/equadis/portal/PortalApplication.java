package com.equadis.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Supplier &amp; Retailer Partnership Portal - proof of concept.
 *
 * <p>The application is a <b>modular monolith</b>: one deployable, split into business modules
 * (one top-level package each) that only talk to each other through their public services:
 * <ul>
 *     <li>{@code tenant} - marketplace isolation (R1) and the identity of the caller,</li>
 *     <li>{@code directory} - suppliers and retailers,</li>
 *     <li>{@code partnership} - partnerships and their lifecycle (BR-03 to BR-07),</li>
 *     <li>{@code offer} - offers and their lifecycle (BR-08, BR-09),</li>
 *     <li>{@code catalogue} - what a retailer can see (BR-10, R4),</li>
 *     <li>{@code subscription} - retailer subscriptions (BR-11),</li>
 *     <li>{@code importing} - CSV offer imports (R3),</li>
 *     <li>{@code outbox} - events recorded in the same transaction as the change,</li>
 *     <li>{@code audit} - who changed what and when (R7),</li>
 *     <li>{@code notification} - digests, e-mail and webhooks (R5),</li>
 *     <li>{@code scheduling} - what triggers the background work,</li>
 *     <li>{@code shared} - cross-cutting technical concerns (errors, time, web middleware).</li>
 * </ul>
 *
 * <p>Every module has the same three layers, and each layer only calls the one below it:
 * <ul>
 *     <li><b>entry points</b> - {@code *Controller} for HTTP, workers for background work:
 *         translate the outside world, no business logic;</li>
 *     <li><b>services</b> - {@code *Service} and the workers' collaborators: use cases, rules,
 *         transactions, authorization;</li>
 *     <li><b>repositories</b> - {@code *Repository}: the only classes that contain SQL.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
