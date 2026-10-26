package org.Cry_Legende.sHAuctions.command.subcommands;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.util.TimeUtil;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SellCommand implements SubCommand {

    private final SHAuctions plugin;
    private final AuctionManager auctionManager;
    private final MessageManager messageManager;
    private final ConfigManager configManager;

    public SellCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.auctionManager = plugin.getAuctionManager();
        this.messageManager = plugin.getMessageManager();
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public String getName() {
        return "sell";
    }

    @Override
    public List<String> getAliases() {
        return List.of("s", "vendre");
    }

    @Override
    public String getDescription() {
        return "Met en vente l'item que vous tenez en main.";
    }

    @Override
    public String getUsage() {
        return "<prix> [durée]";
    }

    @Override
    public String getPermission() {
        return "shauctions.sell";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender; // Garanti par isPlayerOnly() et la vérification dans AuctionCommand

        if (args.length < 1) {
            messageManager.sendMessage(player, "sell_usage"); // "Usage: /hdv sell <prix> [durée_optionnelle]"
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            messageManager.sendMessage(player, "no_item_in_hand");
            return;
        }

        double price;
        try {
            price = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            messageManager.sendMessage(player, "invalid_price", Map.of(
                "min_price", String.valueOf(configManager.getDouble("auction_limits.min_price")),
                "max_price", String.valueOf(configManager.getDouble("auction_limits.max_price"))
            ));
            return;
        }

        // NaN et l'infini passent les comparaisons min/max : annonce invendable
        if (price <= 0 || !Double.isFinite(price)) { // Une vérification supplémentaire, AuctionManager fera le min_price
            messageManager.sendMessage(player, "invalid_price", Map.of(
                "min_price", String.valueOf(configManager.getDouble("auction_limits.min_price")),
                "max_price", String.valueOf(configManager.getDouble("auction_limits.max_price"))
            ));
            return;
        }

        long durationSeconds;
        if (args.length >= 2) {
            durationSeconds = TimeUtil.parseDurationFlexible(args[1]);
            if (durationSeconds == -1) {
                messageManager.sendMessage(player, "invalid_duration_format");
                return;
            }
        } else {
            durationSeconds = configManager.getLong("auction_limits.default_duration_seconds", 86400); // 24h par défaut
        }

        // Vérifier les limites de durée min/max
        long minDuration = configManager.getLong("auction_limits.min_duration_seconds", 3600);
        long maxDurationConf = configManager.getLong("auction_limits.max_duration_seconds", 604800);
        if (durationSeconds < minDuration || durationSeconds > maxDurationConf) {
            messageManager.sendMessage(player, "invalid_duration", Map.of(
                "min_duration", TimeUtil.formatDuration(minDuration),
                "max_duration", TimeUtil.formatDuration(maxDurationConf)
            ));
            return;
        }

        // Appeler AuctionManager pour créer l'enchère
        // L'AuctionManager s'occupera des autres vérifications (blacklist, limites de prix, etc.)
        // et de retirer l'item de l'inventaire.
        if (!auctionManager.createAuction(player, itemInHand, price, durationSeconds)) {
            // Le message d'erreur spécifique est déjà envoyé par AuctionManager
        }
        // Si createAuction est true, le message de succès est aussi envoyé par AuctionManager
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // Proposer un exemple de prix ou "<prix>"
            return Collections.singletonList("<prix>");
        }
        if (args.length == 2) {
            // Proposer des exemples de durée
            List<String> suggestions = new ArrayList<>();
            suggestions.add("1h");
            suggestions.add("12h");
            suggestions.add("1j"); // Ou "1d"
            suggestions.add("30m");
            suggestions.add("[durée]");
            return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }
}
