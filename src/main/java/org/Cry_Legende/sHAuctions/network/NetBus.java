package org.Cry_Legende.sHAuctions.network;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Ce dont l'HDV en réseau a besoin de SHNetwork. Seule {@link ShNetworkBus} connaît SHNetwork : sans lui, aucune
 * classe qui touche à son API n'est chargée.
 */
public interface NetBus {

    /** Candidature à une tâche de meneur. */
    interface Leader {

        boolean isLeader();

        void release();
    }

    /** Nom de ce serveur sur le réseau. */
    String serverName();

    /** Serveur où le joueur est connecté, ou null s'il n'est connecté nulle part. */
    String serverOf(UUID uuid);

    /** Publie sur un canal, à destination des autres serveurs. Ne bloque jamais. */
    void publish(String channel, JsonObject message);

    /** Écoute un canal ; le gestionnaire est appelé sur le fil principal. */
    void listen(String channel, Consumer<JsonObject> handler);

    /** Se porte candidat pour une tâche qui ne tourne que sur un serveur à la fois. */
    Leader leader(String task, Runnable onAcquire, Runnable onLose);
}
