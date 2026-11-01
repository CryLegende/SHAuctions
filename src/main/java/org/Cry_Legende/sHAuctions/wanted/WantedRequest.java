package org.Cry_Legende.sHAuctions.wanted;

import java.util.UUID;

/**
 * Une recherche affichée dans l'onglet « Recherches » de l'HDV : ce qu'un joueur cherche, en texte libre.
 * Horodatages en secondes Unix (comme le reste de SHAuctions).
 */
public record WantedRequest(long id, UUID ownerUuid, String ownerName, String text, long createdAt, long expiresAt) {

    public boolean isOwnedBy(UUID uuid) {
        return ownerUuid.equals(uuid);
    }
}
