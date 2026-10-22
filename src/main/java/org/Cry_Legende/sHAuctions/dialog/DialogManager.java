package org.Cry_Legende.sHAuctions.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.dao.AuctionDAO;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.Cry_Legende.sHAuctions.util.ItemUtil;
import org.Cry_Legende.sHAuctions.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Saisie par dialogue natif (clients 1.21.6 et plus) du montant d'une enchère et de la raison d'une annulation staff.
 *
 * <p>Le dialogue remplace le « tapez dans le chat / tapez annuler » : les clients plus anciens, ou la configuration
 * {@code dialogues.actif: false}, gardent exactement le chemin chat ({@code ChatInputListener}), qui n'est pas touché.</p>
 *
 * <p>Sécurité : un joueur n'a qu'une saisie en attente. Chaque dialogue affiché porte un jeton aléatoire inscrit dans la
 * clé de ses boutons ({@code shauctions:<type>/<jeton>/<action>}). Un clic n'est pris en compte que si la clé est
 * connue, le jeton est celui du dialogue en cours du joueur, le type correspond et la saisie n'est pas périmée ; elle
 * est alors retirée (usage unique) avant tout traitement. Le montant n'est jamais cru : il est parsé comme au chat,
 * puis transmis à {@code AuctionManager#placeBid}, qui refait toutes les vérifications (enchère ouverte, vendeur,
 * meilleur enchérisseur, minimum, incrément, solde, concurrence). Tout se passe sur le thread principal.</p>
 */
public final class DialogManager {

    /** Espace de noms des clés de boutons ; le listener ignore tout le reste. */
    public static final String NAMESPACE = "shauctions";
    /** Clés des champs de saisie dans la réponse du client. */
    public static final String INPUT_AMOUNT = "amount";
    public static final String INPUT_REASON = "reason";

    private static final String ACTION_OK = "ok";
    private static final String ACTION_CANCEL = "cancel";

    private static final int WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;
    /** Un montant tient largement en 32 caractères (« 1 000 000 000 000,50 »). */
    private static final int AMOUNT_MAX_LENGTH = 32;
    /** Limite d'un message de chat Minecraft : la même pour la raison d'annulation. */
    private static final int REASON_MAX_LENGTH = 256;
    private static final long DEFAULT_TTL_SECONDS = 300L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    // Noms d'objets : codes section (§) et couleurs hexadécimales « §x§R§R§G§G§B§B ».
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    // Mêmes mots-clés que ChatInputListener : taper l'un d'eux dans le champ annule aussi la saisie.
    private static final String[] CANCEL_KEYWORDS = {"annuler", "cancel", "annulé", "annule"};

    private enum Kind {
        BID("bid", INPUT_AMOUNT, AMOUNT_MAX_LENGTH, "dialogues.enchere."),
        REASON("reason", INPUT_REASON, REASON_MAX_LENGTH, "dialogues.annulation.");

        final String id;
        final String input;
        final int maxLength;
        final String path;

        Kind(String id, String input, int maxLength, String path) {
            this.id = id;
            this.input = input;
            this.maxLength = maxLength;
            this.path = path;
        }

        static Kind of(String id) {
            for (Kind kind : values()) {
                if (kind.id.equals(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Saisie en attente : ce que le joueur a vu à l'ouverture, pour détecter une enchère qui a changé. */
    private record Pending(Kind kind, long auctionId, UUID seller, long listTimestamp, String token, long expiresAtMillis) {
    }

    private final SHAuctions plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    // Dernier jeton consommé par joueur : un double clic qui arrive après le premier est ignoré sans bruit.
    private final ConcurrentHashMap<UUID, String> lastConsumed = new ConcurrentHashMap<>();

    public DialogManager(SHAuctions plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ disponibilité

    /**
     * Ce client peut-il recevoir un dialogue ? Sans ViaVersion, tous les clients sont natifs (1.21.11).
     */
    public boolean supports(Player player) {
        ConfigManager config = plugin.getConfigManager();
        if (!config.getBoolean("dialogues.actif", true)) {
            return false;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            return true;
        }
        try {
            return ViaSupport.protocol(player) >= config.getInt("dialogues.min-protocol", 771);
        } catch (LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ouverture

    /**
     * Ouvre le dialogue de mise. Renvoie false si le chemin chat doit être utilisé (client trop ancien, dialogues
     * désactivés, enchère introuvable ou erreur d'affichage) : l'appelant continue alors comme avant.
     */
    public boolean openBid(Player viewer, AuctionItem clicked) {
        return open(viewer, clicked, Kind.BID, true, null, null);
    }

    /**
     * Ouvre le dialogue de saisie de la raison d'une annulation staff (même règle de repli que {@link #openBid}).
     */
    public boolean openCancelReason(Player viewer, AuctionItem clicked) {
        return open(viewer, clicked, Kind.REASON, true, null, null);
    }

    private boolean open(Player viewer, AuctionItem clicked, Kind kind, boolean closeInventory, Component error, String prefill) {
        Pending created = null;
        try {
            if (clicked == null || !viewer.isOnline() || !supports(viewer)) {
                return false;
            }
            AuctionItem live = plugin.getDatabaseManager().getAuctionDAO().getAuctionById(clicked.getId());
            // Enchère disparue ou remplacée entre l'affichage du menu et le clic : le chemin chat gère ce cas.
            if (live == null || !sameAuction(live, clicked.getSellerUuid(), clicked.getListTimestamp())) {
                return false;
            }
            long ttl = Math.max(10L, plugin.getConfigManager().getLong("dialogues.expiration-secondes", DEFAULT_TTL_SECONDS));
            created = new Pending(kind, live.getId(), live.getSellerUuid(), live.getListTimestamp(), newToken(),
                    System.currentTimeMillis() + ttl * 1000L);
            Dialog dialog = build(created, live, error, prefill);

            pending.put(viewer.getUniqueId(), created);
            // Une éventuelle saisie par chat restée en attente ne doit pas intercepter la suite.
            plugin.getChatInputManager().cancel(viewer.getUniqueId());
            if (closeInventory) {
                viewer.closeInventory();
            }
            viewer.showDialog(dialog);
            return true;
        } catch (RuntimeException | LinkageError e) {
            if (created != null) {
                pending.remove(viewer.getUniqueId(), created);
            }
            plugin.getLogger().log(Level.WARNING, "Dialogue impossible pour " + viewer.getName() + ", repli sur le chat.", e);
            return false;
        }
    }

    private Dialog build(Pending state, AuctionItem live, Component error, String prefill) {
        MessageManager messages = plugin.getMessageManager();
        String base = state.kind().path;
        Map<String, Component> placeholders = placeholders(live);

        List<DialogBody> body = new ArrayList<>();
        if (error != null) {
            body.add(DialogBody.plainMessage(error, WIDTH));
        }
        for (String line : messages.getRawList(base + "corps")) {
            body.add(DialogBody.plainMessage(render(line, placeholders), WIDTH));
        }

        DialogInput input = DialogInput.text(state.kind().input, render(messages.getRaw(base + "champ", ""), placeholders))
                .width(WIDTH)
                .maxLength(state.kind().maxLength)
                .initial(prefill == null ? "" : prefill)
                .build();

        ActionButton ok = button(messages.getRaw(base + "valider", "&a&lValider"), state, ACTION_OK, placeholders);
        ActionButton cancel = button(messages.getRaw(base + "annuler", "&c&lAnnuler"), state, ACTION_CANCEL, placeholders);

        DialogBase dialogBase = DialogBase.builder(render(messages.getRaw(base + "titre", ""), placeholders))
                // Échap = bouton « Annuler » : le client lance son action, la saisie est annulée proprement.
                .canCloseWithEscape(true)
                // L'écran reste affiché tant que le serveur n'a pas répondu (fermeture ou nouvel écran) : pas de double clic.
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(List.of(input))
                .build();
        return Dialog.create(factory -> factory.empty().base(dialogBase).type(DialogType.confirmation(ok, cancel)));
    }

    private ActionButton button(String label, Pending state, String action, Map<String, Component> placeholders) {
        Key key = Key.key(NAMESPACE, state.kind().id + "/" + state.token() + "/" + action);
        return ActionButton.builder(render(label, placeholders))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(key, null))
                .build();
    }

    private Map<String, Component> placeholders(AuctionItem auction) {
        ConfigManager config = plugin.getConfigManager();
        MessageManager messages = plugin.getMessageManager();
        double minIncrement = config.getDouble("bids.min_bid_increment", 1.0);
        double nextMin = auction.hasBid() ? auction.getHighestBid() + minIncrement : auction.getStartingPrice();
        Map<String, Component> map = new HashMap<>();
        map.put("item_name", SECTION.deserialize(itemName(auction.getItemStack())));
        map.put("seller", Component.text(String.valueOf(auction.getSellerName())));
        map.put("min_bid", Component.text(plugin.getEconomyProvider().formatCurrency(nextMin)));
        map.put("current_bid", Component.text(auction.hasBid()
                ? plugin.getEconomyProvider().formatCurrency(auction.getHighestBid())
                : config.getString("bids.no_bid_placeholder", "Aucune offre")));
        map.put("highest_bidder", Component.text(auction.hasBid() && auction.getHighestBidderName() != null
                ? auction.getHighestBidderName()
                : config.getString("bids.no_bidder_placeholder", "—")));
        map.put("time_left", Component.text(TimeUtil.formatDuration(
                Math.max(0L, auction.getExpireTimestamp() - System.currentTimeMillis() / 1000L))));
        map.put("max", Component.text(String.valueOf(REASON_MAX_LENGTH)));
        return map;
    }

    private static String itemName(ItemStack stack) {
        if (stack == null) {
            return "Objet Inconnu";
        }
        String name = ItemUtil.getItemName(stack);
        return name != null && !name.isEmpty() ? name : ItemUtil.getMaterialNameFallback(stack.getType());
    }

    /** Texte de messages.yml (codes &amp; et &amp;#RRGGBB) dont les %variables% sont remplacées par des composants. */
    private static Component render(String template, Map<String, Component> placeholders) {
        Component out = LEGACY.deserialize(template == null ? "" : template);
        for (Map.Entry<String, Component> entry : placeholders.entrySet()) {
            out = out.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + entry.getKey() + "%")
                    .replacement(entry.getValue())
                    .build());
        }
        return out;
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

    private static boolean sameAuction(AuctionItem auction, UUID seller, long listTimestamp) {
        return auction.getSellerUuid().equals(seller) && auction.getListTimestamp() == listTimestamp;
    }

    // ------------------------------------------------------------------ clics

    /**
     * Point d'entrée des clics (appelé par le listener, éventuellement hors du thread principal).
     *
     * @param value  partie « valeur » de la clé du bouton : {@code <type>/<jeton>/<action>}
     * @param amount contenu du champ de mise (null si absent)
     * @param reason contenu du champ de raison (null si absent)
     */
    void onClick(Player player, String value, String amount, String reason) {
        sync(() -> {
            try {
                handle(player, value, amount, reason);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Erreur pendant la saisie par dialogue de " + player.getName() + " :", e);
                pending.remove(player.getUniqueId());
                safeClose(player);
                try {
                    plugin.getMessageManager().sendMessage(player, "dialog_error");
                } catch (RuntimeException ignored) {
                    // le joueur est peut-être parti : rien d'autre à faire
                }
            }
        });
    }

    private void handle(Player player, String value, String amountText, String reasonText) {
        if (!player.isOnline()) {
            return;
        }
        String[] parts = value == null ? new String[0] : value.split("/", -1);
        if (parts.length != 3) {
            return;
        }
        Kind kind = Kind.of(parts[0]);
        String token = parts[1];
        String action = parts[2];
        if (kind == null || (!ACTION_OK.equals(action) && !ACTION_CANCEL.equals(action))) {
            return;
        }
        UUID uuid = player.getUniqueId();
        MessageManager messages = plugin.getMessageManager();

        Pending state = pending.get(uuid);
        if (state == null) {
            // Double clic déjà traité : rien à dire.
            if (token.equals(lastConsumed.get(uuid))) {
                return;
            }
            // Écran resté ouvert après un rechargement, une fin d'enchère ou une expiration : on le ferme.
            safeClose(player);
            messages.sendMessage(player, "dialog_expired");
            return;
        }
        // Clé inconnue, jeton d'un ancien dialogue ou autre type : ce n'est pas le dialogue en cours, on ignore.
        if (state.kind() != kind || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), state.token().getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        // Usage unique : le premier traitement retire la saisie, un second clic ne trouvera plus rien.
        if (!pending.remove(uuid, state)) {
            return;
        }
        lastConsumed.put(uuid, state.token());

        if (System.currentTimeMillis() > state.expiresAtMillis()) {
            safeClose(player);
            messages.sendMessage(player, "dialog_expired");
            return;
        }
        if (ACTION_CANCEL.equals(action)) {
            safeClose(player);
            messages.sendMessage(player, "chat_input_cancelled");
            return;
        }

        String raw = (kind == Kind.BID ? amountText : reasonText);
        raw = raw == null ? "" : raw.trim();
        if (isCancelKeyword(raw)) {
            safeClose(player);
            messages.sendMessage(player, "chat_input_cancelled");
            return;
        }

        AuctionDAO dao = plugin.getDatabaseManager().getAuctionDAO();
        AuctionItem live = dao.getAuctionById(state.auctionId());
        if (live == null) {
            // Même message que placeBid / cancelAuction pour une enchère qui n'existe plus.
            safeClose(player);
            messages.sendMessage(player, kind == Kind.BID ? "item_not_found_or_expired" : "item_not_found");
            return;
        }
        if (!sameAuction(live, state.seller(), state.listTimestamp())) {
            safeClose(player);
            messages.sendMessage(player, "dialog_auction_changed");
            return;
        }

        if (kind == Kind.BID) {
            Double amount = parseAmount(raw);
            if (amount == null) {
                retry(player, live, kind, "dialogues.erreurs.montant-invalide", raw);
                return;
            }
            safeClose(player);
            plugin.getAuctionManager().placeBid(player, state.auctionId(), amount);
        } else {
            if (raw.isEmpty()) {
                retry(player, live, kind, "dialogues.erreurs.raison-vide", raw);
                return;
            }
            if (raw.length() > REASON_MAX_LENGTH || !isAllowedText(raw)) {
                retry(player, live, kind, "dialogues.erreurs.raison-invalide", raw);
                return;
            }
            safeClose(player);
            plugin.getAuctionManager().cancelAuction(player, state.auctionId(), true, raw);
        }
    }

    /** Saisie refusée avant tout traitement : on réaffiche le dialogue avec l'erreur, sans perdre ce que le joueur a tapé. */
    private void retry(Player player, AuctionItem live, Kind kind, String errorPath, String typed) {
        Component error = render(plugin.getMessageManager().getRaw(errorPath, "&cSaisie invalide."), placeholders(live));
        if (!open(player, live, kind, false, error, typed.length() > kind.maxLength ? typed.substring(0, kind.maxLength) : typed)) {
            // Impossible de réafficher (client parti, erreur) : on ne laisse pas l'écran d'attente.
            safeClose(player);
        }
    }

    /**
     * Même lecture du montant qu'au chat (virgule décimale, espaces ignorés, strictement positif), avec en plus le
     * refus de NaN et de l'infini, que le chemin chat laisse passer.
     */
    static Double parseAmount(String raw) {
        try {
            double value = Double.parseDouble(raw.replace(",", ".").replace(" ", ""));
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Mêmes caractères que le chat de Minecraft : ni § ni caractères de contrôle. */
    static boolean isAllowedText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < ' ' || c == '§' || c == 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCancelKeyword(String message) {
        for (String keyword : CANCEL_KEYWORDS) {
            if (keyword.equalsIgnoreCase(message)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ cycle de vie

    /**
     * L'enchère n'existe plus (annulée, expirée, remportée, achetée) : les saisies en attente qui la visaient sont
     * annulées et leur dialogue fermé.
     */
    public void invalidateAuction(long auctionId) {
        sync(() -> {
            for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
                Pending state = entry.getValue();
                if (state.auctionId() != auctionId || !pending.remove(entry.getKey(), state)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    safeClose(player);
                    plugin.getMessageManager().sendMessage(player, "dialog_auction_ended");
                }
            }
        });
    }

    /** Départ du joueur : plus rien n'est en attente pour lui. */
    public void forget(UUID uuid) {
        pending.remove(uuid);
        lastConsumed.remove(uuid);
    }

    /** Arrêt du plugin : on ne laisse aucun joueur devant un dialogue qui ne répondra plus. */
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(pending.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                safeClose(player);
            }
        }
        pending.clear();
        lastConsumed.clear();
    }

    private void safeClose(Player player) {
        try {
            player.closeDialog();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Fermeture du dialogue impossible pour " + player.getName(), e);
        }
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
