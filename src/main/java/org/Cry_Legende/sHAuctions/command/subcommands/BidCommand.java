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
import java.util.List;
import java.util.Map;

public class BidCommand implements SubCommand {

    private final SHAuctions plugin;
    private final AuctionManager auctionManager;
    private final MessageManager messageManager;
    private final ConfigManager configManager;

    public BidCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.auctionManager = plugin.getAuctionManager();
        this.messageManager = plugin.getMessageManager();
        this.configManager = plugin.getConfigManager();
    }

    @Override
    public String getName() {
        return "bid";
    }

    @Override
    public List<String> getAliases() {
        return List.of("enchere", "enchère", "e");
    }

    @Override
    public String getDescription() {
        return "Met l'item que vous tenez aux enchères.";
    }

    @Override
    public String getUsage() {
        return "<prix de départ> [durée]";
    }

    @Override
    public String getPermission() {
        return "shauctions.bid";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        if (args.length < 1) {
            messageManager.sendMessage(player, "bid_usage");
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType().isAir()) {
            messageManager.sendMessage(player, "no_item_in_hand");
            return;
        }

        double startingPrice;
        try {
            startingPrice = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            messageManager.sendMessage(player, "invalid_price", Map.of(
                "min_price", String.valueOf(configManager.getDouble("bids.min_starting_price", configManager.getDouble("auction_limits.min_price"))),
                "max_price", String.valueOf(configManager.getDouble("bids.max_starting_price", configManager.getDouble("auction_limits.max_price")))
            ));
            return;
        }

        if (startingPrice <= 0) {
            messageManager.sendMessage(player, "invalid_price", Map.of(
                "min_price", String.valueOf(configManager.getDouble("bids.min_starting_price", configManager.getDouble("auction_limits.min_price"))),
                "max_price", String.valueOf(configManager.getDouble("bids.max_starting_price", configManager.getDouble("auction_limits.max_price")))
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
            durationSeconds = configManager.getLong("bids.default_duration_seconds",
                configManager.getLong("auction_limits.default_duration_seconds", 86400));
        }

        long minDuration = configManager.getLong("auction_limits.min_duration_seconds", 3600);
        long maxDurationConf = configManager.getLong("auction_limits.max_duration_seconds", 604800);
        if (durationSeconds < minDuration || durationSeconds > maxDurationConf) {
            messageManager.sendMessage(player, "invalid_duration", Map.of(
                "min_duration", TimeUtil.formatDuration(minDuration),
                "max_duration", TimeUtil.formatDuration(maxDurationConf)
            ));
            return;
        }

        auctionManager.createBidAuction(player, itemInHand, startingPrice, durationSeconds);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("<prix de départ>");
        }
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("1h");
            suggestions.add("12h");
            suggestions.add("1j");
            suggestions.add("30m");
            suggestions.add("[durée]");
            return suggestions.stream()
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }
}
