package com.equadis.portal.audit;

import com.equadis.portal.tenant.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP layer of the audit trail. No business logic here.
 */
@RestController
@RequestMapping("/api/audit-events")
class AuditController {

    private final AuditService auditService;

    AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** GET /api/audit-events?entityId=... - who changed this offer, partnership... and when. */
    @GetMapping
    List<AuditEventView> history(Actor actor, @RequestParam UUID entityId) {
        return auditService.history(actor, entityId);
    }
}
