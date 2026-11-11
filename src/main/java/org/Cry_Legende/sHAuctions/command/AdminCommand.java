package org.Cry_Legende.sHAuctions.command;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.manager.PermissionManager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /shauctions : administration du plugin, console comprise. Chaque sous-commande a sa permission
 * {@code shauctions.admin.<nom>}. plan, launch et skip gèrent les enchères du serveur.
 */
public class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "plan", "launch", "skip");

    private final SHAuctions plugin;

    public AdminCommand(SHAuctions plugin) {
        this.plugin = plugin;
    }

    private boolean can(CommandSender sender, String sub) {
        return sender.hasPermission("shauctions.admin." + sub);
    }

    private List<String> allowed(CommandSender sender) {
        List<String> result = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (can(sender, sub)) {
                result.add(sub);
            }
        }
        return result;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        MessageManager messages = plugin.getMessageManager();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            List<String> allowed = allowed(sender);
            if (allowed.isEmpty()) {
                messages.sendMessage(sender, "no_permission");
            } else {
                messages.sendMessage(sender, "admin_usage", Map.of("commands", String.join("|", allowed)));
            }
            return true;
        }
        if (!can(sender, sub)) {
            messages.sendMessage(sender, "no_permission");
            return true;
        }
        switch (sub) {
            case "reload" -> plugin.reloadAll(sender);
            case "info" -> info(sender, args);
            case "plan" -> plugin.getServerAuctions().sendPlan(sender);
            case "launch" -> {
                if (args.length < 2) {
                    messages.sendMessage(sender, "server_launch_usage");
                } else {
                    plugin.getServerAuctions().launchNow(sender, args[1], args.length > 2 ? args[2] : null);
                }
            }
            default -> plugin.getServerAuctions().skipNext(sender);
        }
        return true;
    }

    /** /shauctions info <joueur> : ventes, enchères et invendus du joueur, avec ses limites s'il est connecté. */
    private void info(CommandSender sender, String[] args) {
        MessageManager messages = plugin.getMessageManager();
        if (args.length < 2) {
            messages.sendMessage(sender, "info_usage");
            return;
        }
        // Jamais getOfflinePlayer(nom) : la résolution par le nom passe par Mojang
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID uuid;
        String name;
        if (online != null) {
            uuid = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[1]);
            if (cached == null) {
                messages.sendMessage(sender, "info_unknown_player", Map.of("player", args[1]));
                return;
            }
            uuid = cached.getUniqueId();
            name = cached.getName() == null ? args[1] : cached.getName();
        }
        DatabaseManager database = plugin.getDatabaseManager();
        if (database == null || database.getAuctionDAO() == null) {
            messages.sendMessage(sender, "generic_error_occurred", Map.of("error_details", "base indisponible"));
            return;
        }

        // Limites effectives : seulement pour un joueur connecté (elles viennent de ses permissions)
        String salesLimit = "?";
        String bidsLimit = "?";
        if (online != null) {
            PermissionManager permissions = plugin.getPermissionManager();
            salesLimit = limit(permissions.getPlayerMaxListings(online));
            bidsLimit = limit(permissions.getPlayerMaxBidListings(online));
        }
        final String finalSalesLimit = salesLimit;
        final String finalBidsLimit = bidsLimit;
        final String shown = name;

        // Accès base hors du thread principal (SQLite, ou MariaDB partagée en mode réseau)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int sales = database.getAuctionDAO().countSaleAuctionsBySeller(uuid);
                int bids = database.getAuctionDAO().countBidAuctionsBySeller(uuid);
                int expired = database.getExpiredItemDAO().countUnclaimedExpiredItemsByOwner(uuid);
                int sold = database.getSoldItemDAO().countSalesBySeller(uuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messages.sendMessage(sender, "info_header", Map.of("player", shown));
                    messages.sendMessage(sender, "info_sales", Map.of("count", String.valueOf(sales), "limit", finalSalesLimit));
                    messages.sendMessage(sender, "info_bids", Map.of("count", String.valueOf(bids), "limit", finalBidsLimit));
                    messages.sendMessage(sender, "info_expired", Map.of("count", String.valueOf(expired)));
                    messages.sendMessage(sender, "info_sold", Map.of("count", String.valueOf(sold)));
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error reading auction data of " + shown + " for /shauctions info:", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                    messages.sendMessage(sender, "generic_error_occurred", Map.of("error_details", "voir la console")));
            }
        });
    }

    private String limit(int value) {
        return value == Integer.MAX_VALUE ? "∞" : String.valueOf(value);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return allowed(sender).stream().filter(sub -> sub.startsWith(start)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info") && can(sender, "info")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("launch") && can(sender, "launch")) {
            String start = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>(plugin.getServerAuctions().lotIds());
            ids.add("random");
            return ids.stream().filter(id -> id.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        }
        return List.of();
    }
}
