package org.Cry_Legende.sHAuctions.dialog;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;

/**
 * Reçoit les clics des boutons de dialogue (clés {@code shauctions:...}) et les départs de joueurs.
 *
 * <p>Ce listener ne fait que lire l'événement : tout le traitement (jeton, état, économie, base de données)
 * est confié à {@link DialogManager} sur le thread principal. Aucune exception ne sort d'ici.</p>
 */
public final class DialogListener implements Listener {

    private final SHAuctions plugin;
    private final DialogManager manager;

    public DialogListener(SHAuctions plugin, DialogManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        try {
            Key id = event.getIdentifier();
            // Les clics des autres plugins (SHLoginDialog, ...) ne nous concernent pas.
            if (!DialogManager.NAMESPACE.equals(id.namespace())) {
                return;
            }
            if (!(event.getCommonConnection() instanceof PlayerGameConnection game)) {
                return;
            }
            Player player = game.getPlayer();
            DialogResponseView view = event.getDialogResponseView();
            String amount = read(view, DialogManager.INPUT_AMOUNT);
            String reason = read(view, DialogManager.INPUT_REASON);
            manager.onClick(player, id.value(), amount, reason);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Clic de dialogue ignoré (erreur inattendue).", e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.forget(event.getPlayer().getUniqueId());
    }

    private static String read(DialogResponseView view, String key) {
        if (view == null) {
            return null;
        }
        try {
            return view.getText(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
