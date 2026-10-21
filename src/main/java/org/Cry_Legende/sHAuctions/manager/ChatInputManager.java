package org.Cry_Legende.sHAuctions.manager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les saisies de chat en attente (enchères et raisons d'annulation).
 *
 * <p>Le clic sur un item d'enchère (ou l'annulation staff) place le joueur en état "en attente
 * de saisie". Le prochain message chat du joueur est intercepté par {@code ChatInputListener}.
 * La carte est un {@link ConcurrentHashMap} car elle est lue/écrite depuis le thread de chat
 * asynchrone ; le traitement effectif (économie/DB) est ensuite planifié sur le thread principal.</p>
 */
public class ChatInputManager {

    public enum InputType {
        /**
         * Le joueur doit saisir un montant d'enchère.
         */
        BID,
        /**
         * Le staff doit saisir une raison d'annulation.
         */
        CANCEL_REASON
    }

    public static class PendingInput {
        private final InputType type;
        private final long auctionId;
        private final long createdAtMillis;

        public PendingInput(InputType type, long auctionId) {
            this.type = type;
            this.auctionId = auctionId;
            this.createdAtMillis = System.currentTimeMillis();
        }

        public InputType getType() {
            return type;
        }

        public long getAuctionId() {
            return auctionId;
        }

        public long getCreatedAtMillis() {
            return createdAtMillis;
        }
    }

    // Délai au-delà duquel une saisie en attente est considérée périmée (60 secondes).
    private static final long TIMEOUT_MILLIS = 60_000L;
    // Délai pour la proposition d'annonce après une mise en enchère (120 secondes).
    private static final long ANNOUNCE_TIMEOUT_MILLIS = 120_000L;

    private final ConcurrentHashMap<UUID, PendingInput> pending = new ConcurrentHashMap<>();
    // Enchères en attente d'une décision d'annonce (clic Annoncer/Ignorer). Valeur = id de l'enchère + horodatage.
    private final ConcurrentHashMap<UUID, long[]> pendingAnnounce = new ConcurrentHashMap<>();

    /**
     * Propose au joueur d'annoncer l'enchère qu'il vient de lister.
     */
    public void awaitAnnounce(UUID playerUuid, long auctionId) {
        pendingAnnounce.put(playerUuid, new long[]{auctionId, System.currentTimeMillis()});
    }

    /**
     * Retire et renvoie l'id de l'enchère à annoncer (-1 si aucune ou périmée).
     */
    public long consumeAnnounce(UUID playerUuid) {
        long[] entry = pendingAnnounce.remove(playerUuid);
        if (entry == null) return -1;
        if (System.currentTimeMillis() - entry[1] > ANNOUNCE_TIMEOUT_MILLIS) return -1;
        return entry[0];
    }

    /**
     * Annule la proposition d'annonce en attente.
     */
    public void cancelAnnounce(UUID playerUuid) {
        pendingAnnounce.remove(playerUuid);
    }

    /**
     * Met le joueur en attente de saisie d'un montant d'enchère pour l'enchère donnée.
     */
    public void awaitBid(UUID playerUuid, long auctionId) {
        pending.put(playerUuid, new PendingInput(InputType.BID, auctionId));
    }

    /**
     * Met le joueur (staff) en attente de saisie d'une raison d'annulation.
     */
    public void awaitCancelReason(UUID playerUuid, long auctionId) {
        pending.put(playerUuid, new PendingInput(InputType.CANCEL_REASON, auctionId));
    }

    public boolean hasPending(UUID playerUuid) {
        return pending.containsKey(playerUuid);
    }

    /**
     * Consulte la saisie en attente sans la retirer (null si aucune ou périmée).
     */
    public PendingInput peek(UUID playerUuid) {
        PendingInput input = pending.get(playerUuid);
        if (input == null) return null;
        if (System.currentTimeMillis() - input.getCreatedAtMillis() > TIMEOUT_MILLIS) {
            pending.remove(playerUuid, input);
            return null;
        }
        return input;
    }

    /**
     * Retire et renvoie la saisie en attente (null si aucune ou périmée).
     */
    public PendingInput consume(UUID playerUuid) {
        PendingInput input = pending.remove(playerUuid);
        if (input == null) return null;
        if (System.currentTimeMillis() - input.getCreatedAtMillis() > TIMEOUT_MILLIS) {
            return null;
        }
        return input;
    }

    /**
     * Annule la saisie en attente d'un joueur.
     */
    public void cancel(UUID playerUuid) {
        pending.remove(playerUuid);
    }
}
