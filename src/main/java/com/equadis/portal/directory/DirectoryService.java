package com.equadis.portal.directory;

import com.equadis.portal.directory.DirectoryRepository.StoredRetailerContact;
import com.equadis.portal.shared.error.ErrorCode;
import com.equadis.portal.shared.error.PortalException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules need to know about suppliers and retailers (the "directory").
 *
 * <p>Suppliers and retailers are seeded in this proof of concept (the brief allows it), so
 * this module only reads.
 */
@Service
public class DirectoryService {

    private final DirectoryRepository directoryRepository;
    private final WebhookSecretCipher webhookSecretCipher;

    public DirectoryService(DirectoryRepository directoryRepository, WebhookSecretCipher webhookSecretCipher) {
        this.directoryRepository = directoryRepository;
        this.webhookSecretCipher = webhookSecretCipher;
    }

    /** @throws PortalException SUPPLIER_NOT_FOUND when it does not exist in this marketplace */
    public void requireSupplier(UUID supplierId) {
        if (!directoryRepository.supplierExists(supplierId)) {
            throw PortalException.notFound(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier", supplierId);
        }
    }

    /** @throws PortalException RETAILER_NOT_FOUND when it does not exist in this marketplace */
    public void requireRetailer(UUID retailerId) {
        if (!directoryRepository.retailerExists(retailerId)) {
            throw PortalException.notFound(ErrorCode.RETAILER_NOT_FOUND, "Retailer", retailerId);
        }
    }

    /** @return how to reach this retailer, with its webhook secret decrypted */
    public Optional<RetailerContact> findRetailerContact(UUID retailerId) {
        return directoryRepository.findRetailerContact(retailerId).map(this::toRetailerContact);
    }

    private RetailerContact toRetailerContact(StoredRetailerContact stored) {
        String webhookSecret = stored.encryptedWebhookSecret() == null
                ? null
                : webhookSecretCipher.decrypt(stored.id(), stored.encryptedWebhookSecret());
        return new RetailerContact(stored.id(), stored.name(), stored.contactEmail(), stored.webhookUrl(), webhookSecret);
    }
}
