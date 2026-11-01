package org.Cry_Legende.sHAuctions.wanted;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.dialog.DialogManager;
import org.Cry_Legende.sHAuctions.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Onglet « Recherches » de l'HDV : un joueur publie en texte libre ce qu'il cherche (« je cherche du sable »),
 * les autres le voient et le contactent par /mail, même s'il est hors ligne. Aucun argent ni objet ne bouge.
 *
 * <p>Règles (config.yml › recherches) : longueur min/max, mots interdits, nombre de recherches actives par joueur
 * (permission {@code shauctions.recherches.max.<n>} pour plus), délai entre deux publications, durée de vie.
 * Codes couleur retirés du texte. Chaque publication et suppression est écrite dans la console (modération) ;
 * le staff ({@code shauctions.recherches.admin}) peut supprimer n'importe quelle recherche.</p>
 *
 * <p>Saisie par dialogue natif (même règle que les enchères : client 1.21.6+, {@code dialogues.actif}) ou par
 * {@code /hdv recherche <texte>}. Clés de bouton {@code shauctions:wanted/<jeton>/<ok|cancel>}, jeton à usage unique.</p>
 */
public final class WantedManager {

    public static final String DIALOG_PREFIX = "wanted/";
    public static final String INPUT_TEXT = "wanted_text";

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-orx])");
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SHAuctions plugin;
    private final WantedStore store;
    private final Map<UUID, Long> lastCreate = new ConcurrentHashMap<>();
    private final Map<UUID, String> dialogTokens = new ConcurrentHashMap<>();

    public WantedManager(SHAuctions plugin) {
        this.plugin = plugin;
        this.store = new WantedStore(plugin);
        int purged = store.purgeExpired(now());
        if (purged > 0) {
            plugin.getLogger().info("[Recherches] " + purged + " recherche(s) expirée(s) supprimée(s).");
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    public boolean isEnabled() {
        return config().getBoolean("recherches.actif", true);
    }

    public List<WantedRequest> listActive() {
        return store.listActive(now());
    }

    public WantedRequest get(long id) {
        return store.get(id);
    }

    public boolean isStaff(Player player) {
        return player.hasPermission("shauctions.recherches.admin");
    }

    /** Recherches actives autorisées : config, ou plus avec shauctions.recherches.max.<n>. */
    public int maxFor(Player player) {
        int max = config().getInt("recherches.max-par-joueur", 3);
        for (int n = max + 1; n <= 20; n++) {
            if (player.hasPermission("shauctions.recherches.max." + n)) {
                max = n;
            }
        }
        return max;
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return COLOR_CODES.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private static boolean allowedChars(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < ' ' || c == '§' || c == 127) {
                return false;
            }
        }
        return true;
    }

    private String bannedWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : config().getStringList("recherches.mots-interdits")) {
            if (!word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT))) {
                return word;
            }
        }
        return null;
    }

    private void send(Player player, String key, Map<String, String> placeholders) {
        messages().sendMessage(player, key, placeholders);
    }

    /** Publie une recherche ; renvoie true si elle a été enregistrée (sinon le joueur a reçu la raison). */
    public boolean create(Player player, String raw) {
        if (!isEnabled()) {
            send(player, "recherche_desactivee", Map.of());
            return false;
        }
        String text = sanitize(raw);
        int min = config().getInt("recherches.longueur-min", 3);
        int max = Math.min(256, config().getInt("recherches.longueur-max", 80));
        if (text.length() < min) {
            send(player, "recherche_trop_courte", Map.of("min", String.valueOf(min)));
            return false;
        }
        if (text.length() > max) {
            send(player, "recherche_trop_longue", Map.of("max", String.valueOf(max)));
            return false;
        }
        if (!allowedChars(text)) {
            send(player, "recherche_texte_invalide", Map.of());
            return false;
        }
        if (bannedWord(text) != null) {
            send(player, "recherche_mot_interdit", Map.of());
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = now();
        int limit = maxFor(player);
        if (store.countActive(uuid, now) >= limit) {
            send(player, "recherche_limite_atteinte", Map.of("max", String.valueOf(limit)));
            return false;
        }
        long cooldown = config().getLong("recherches.delai-secondes", 30);
        Long last = lastCreate.get(uuid);
        if (last != null && now - last < cooldown && !isStaff(player)) {
            send(player, "recherche_attendre", Map.of("secondes", String.valueOf(cooldown - (now - last))));
            return false;
        }
        long hours = Math.max(1, config().getLong("recherches.duree-heures", 72));
        long id = store.insert(uuid, player.getName(), text, now, now + hours * 3600L);
        if (id < 0) {
            send(player, "recherche_erreur", Map.of());
            return false;
        }
        lastCreate.put(uuid, now);
        plugin.getLogger().info("[Recherches] " + player.getName() + " a publié #" + id + " : " + text);
        send(player, "recherche_publiee", Map.of("duree", TimeUtil.formatDuration(hours * 3600L)));
        return true;
    }

    /** Supprime une recherche du joueur (ou n'importe laquelle pour le staff). */
    public boolean delete(Player player, WantedRequest request) {
        if (!request.isOwnedBy(player.getUniqueId()) && !isStaff(player)) {
            send(player, "recherche_pas_la_votre", Map.of());
            return false;
        }
        if (!store.delete(request.id())) {
            send(player, "recherche_introuvable", Map.of());
            return false;
        }
        plugin.getLogger().info("[Recherches] " + player.getName() + " a supprimé #" + request.id()
            + " (" + request.ownerName() + ") : " + request.text());
        send(player, "recherche_supprimee", Map.of());
        return true;
    }

    /** Message cliquable pour écrire à l'auteur (/mail fonctionne même hors ligne). */
    public void contact(Player player, WantedRequest request) {
        Map<String, String> ph = new HashMap<>();
        ph.put("auteur", request.ownerName());
        ph.put("texte", request.text());
        String header = messages().getMessage("recherche_contact", ph);
        player.sendMessage(SECTION.deserialize(header));
        String mail = config().getString("recherches.commande-contact", "/mail %auteur% ").replace("%auteur%", request.ownerName());
        Component button = SECTION.deserialize(messages().getMessage("recherche_contact_bouton", false, ph))
            .clickEvent(ClickEvent.suggestCommand(mail))
            .hoverEvent(HoverEvent.showText(SECTION.deserialize(messages().getMessage("recherche_contact_survol", false, ph))));
        player.sendMessage(button);
    }

    // ------------------------------------------------------------------ dialogue de publication

    /** Ouvre le dialogue de publication ; sinon explique la commande (client trop ancien, dialogues coupés). */
    public void openCreateDialog(Player player) {
        DialogManager dialogs = plugin.getDialogManager();
        if (dialogs == null || !dialogs.supports(player)) {
            send(player, "recherche_utiliser_commande", Map.of());
            return;
        }
        try {
            String token = newToken();
            int max = Math.min(256, config().getInt("recherches.longueur-max", 80));
            List<DialogBody> body = new ArrayList<>();
            for (String line : messages().getRawList("dialogues.recherche.corps")) {
                body.add(DialogBody.plainMessage(AMP.deserialize(line.replace("%max%", String.valueOf(max))), 250));
            }
            DialogInput input = DialogInput.text(INPUT_TEXT, AMP.deserialize(messages().getRaw("dialogues.recherche.champ", "&7Je cherche...")))
                .width(250).maxLength(max).initial("").build();
            ActionButton ok = button(messages().getRaw("dialogues.recherche.valider", "&a&lPublier"), token, "ok");
            ActionButton cancel = button(messages().getRaw("dialogues.recherche.annuler", "&c&lAnnuler"), token, "cancel");
            DialogBase base = DialogBase.builder(AMP.deserialize(messages().getRaw("dialogues.recherche.titre", "&8Nouvelle recherche")))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(List.of(input))
                .build();
            Dialog dialog = Dialog.create(factory -> factory.empty().base(base).type(DialogType.confirmation(ok, cancel)));
            dialogTokens.put(player.getUniqueId(), token);
            player.closeInventory();
            player.showDialog(dialog);
        } catch (RuntimeException | LinkageError e) {
            dialogTokens.remove(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Dialogue de recherche impossible pour " + player.getName(), e);
            send(player, "recherche_utiliser_commande", Map.of());
        }
    }

    private static ActionButton button(String label, String token, String action) {
        Key key = Key.key(DialogManager.NAMESPACE, DIALOG_PREFIX + token + "/" + action);
        return ActionButton.builder(AMP.deserialize(label)).width(120).action(DialogAction.customClick(key, null)).build();
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** Clic d'un bouton du dialogue (valeur {@code wanted/<jeton>/<action>}), éventuellement hors du thread principal. */
    public void onDialogClick(Player player, String value, String text) {
        Runnable task = () -> {
            String[] parts = value.split("/", -1);
            if (parts.length != 3 || !player.isOnline()) {
                return;
            }
            // Usage unique : seul le jeton du dernier dialogue ouvert est accepté, une seule fois
            if (!dialogTokens.remove(player.getUniqueId(), parts[1])) {
                return;
            }
            if ("ok".equals(parts[2])) {
                if (create(player, text)) {
                    plugin.getGuiManager().openWantedMenu(player, 1);
                }
            } else {
                send(player, "chat_input_cancelled", Map.of());
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void forget(UUID uuid) {
        dialogTokens.remove(uuid);
    }
}
