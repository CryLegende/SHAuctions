package org.Cry_Legende.sHAuctions.command.subcommands;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface SubCommand {

    /**
     * @return Le nom principal de la sous-commande (ex: "sell", "history").
     */
    String getName();

    /**
     * @return Une liste d'alias pour la sous-commande (ex: ["s"] pour "sell").
     */
    List<String> getAliases();

    /**
     * @return Une brève description de ce que fait la sous-commande.
     */
    String getDescription();

    /**
     * @return La chaîne d'utilisation de la sous-commande (ex: "<prix> [durée_h]").
     */
    String getUsage();

    /**
     * @return La permission requise pour exécuter cette sous-commande.
     */
    String getPermission();

    /**
     * @return True si la commande ne peut être exécutée que par un joueur, false sinon.
     */
    boolean isPlayerOnly();

    /**
     * @return True si la commande doit être masquée de l'aide et de la complétion (commande interne
     * déclenchée par un bouton cliquable, ex: /hdv announce). Par défaut false.
     */
    default boolean isHidden() {
        return false;
    }

    /**
     * Exécute la logique de la sous-commande.
     *
     * @param sender L'entité qui a exécuté la commande.
     * @param args   Les arguments fournis après le nom de la sous-commande.
     */
    void execute(CommandSender sender, String[] args);

    /**
     * Gère la complétion par tabulation pour cette sous-commande.
     *
     * @param sender L'entité qui demande la complétion.
     * @param args   Les arguments fournis après le nom de la sous-commande.
     * @return Une liste de suggestions de complétion.
     */
    List<String> onTabComplete(CommandSender sender, String[] args);
}
