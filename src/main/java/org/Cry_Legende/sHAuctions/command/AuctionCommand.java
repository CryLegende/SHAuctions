package org.Cry_Legende.sHAuctions.command;

import org.Cry_Legende.sHAuctions.SHAuctions;
import org.Cry_Legende.sHAuctions.command.subcommands.*; // Importer les futures sous-commandes
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.gui.GUIManager;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AuctionCommand implements CommandExecutor, TabCompleter {

    private final SHAuctions plugin;
    private final MessageManager messageManager;
    private final GUIManager guiManager;
    private final Map<String, SubCommand> subCommands = new HashMap<>();

    public AuctionCommand(SHAuctions plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessageManager();
        this.guiManager = plugin.getGuiManager();

        // Enregistrer les sous-commandes
        // (Elles seront créées dans des fichiers séparés)
        registerSubCommand(new SellCommand(plugin));
        registerSubCommand(new BidCommand(plugin));
        registerSubCommand(new ReloadCommand(plugin));
        registerSubCommand(new HistoryCommand(plugin));
        registerSubCommand(new ExpiredCommand(plugin));
        registerSubCommand(new OwnSalesCommand(plugin));
        registerSubCommand(new AnnounceCommand(plugin));
        // Annulation staff via clic droit dans le GUI (aucun ID n'est exposé).
        // ... autres sous-commandes
    }

    private void registerSubCommand(SubCommand subCommand) {
        subCommands.put(subCommand.getName().toLowerCase(), subCommand);
        for (String alias : subCommand.getAliases()) {
            subCommands.put(alias.toLowerCase(), subCommand);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Action par défaut : ouvrir le GUI principal de l'HDV si c'est un joueur
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!player.hasPermission("shauctions.use")) {
                    messageManager.sendMessage(player, "no_permission");
                    return true;
                }
                guiManager.openAuctionHouseMenu(player, 1); // Ouvre la page 1
            } else {
                messageManager.sendMessage(sender, "player_only_command"); // Ou afficher une aide pour la console
            }
            return true;
        }

        String subCommandName = args[0].toLowerCase();
        SubCommand subCommand = subCommands.get(subCommandName);

        if (subCommand == null) {
            // Si la sous-commande n'existe pas, on pourrait ouvrir le GUI principal aussi
            // ou afficher un message d'aide.
            if (sender instanceof Player) {
                Player player = (Player) sender;
                if (!player.hasPermission("shauctions.use")) {
                    messageManager.sendMessage(player, "no_permission");
                    return true;
                }
                guiManager.openAuctionHouseMenu(player, 1);
            } else {
                sendHelpMessage(sender);
            }
            return true;
        }

        if (!sender.hasPermission(subCommand.getPermission())) {
            messageManager.sendMessage(sender, "no_permission");
            return true;
        }

        if (subCommand.isPlayerOnly() && !(sender instanceof Player)) {
            messageManager.sendMessage(sender, "player_only_command");
            return true;
        }

        // Retirer le nom de la sous-commande des arguments
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        subCommand.execute(sender, subArgs);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Proposer les noms des sous-commandes
            return subCommands.values().stream()
                .distinct() // Éviter les doublons dus aux alias
                .filter(sc -> !sc.isHidden()) // Masquer les commandes internes (ex: announce)
                .map(SubCommand::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                .filter(name -> sender.hasPermission(subCommands.get(name.toLowerCase()).getPermission())) // Ne montrer que si perm
                .sorted()
                .collect(Collectors.toList());
        } else if (args.length > 1) {
            // Déléguer la complétion à la sous-commande si elle existe
            SubCommand subCommand = subCommands.get(args[0].toLowerCase());
            if (subCommand != null && sender.hasPermission(subCommand.getPermission())) {
                String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
                return subCommand.onTabComplete(sender, subArgs);
            }
        }
        return Collections.emptyList();
    }

    private void sendHelpMessage(CommandSender sender) {
        // Construire un message d'aide listant les sous-commandes disponibles
        messageManager.sendMessageNoPrefix(sender, "&6--- SHAuctions Help ---");
        subCommands.values().stream()
            .distinct()
            .filter(sc -> !sc.isHidden())
            .filter(sc -> sender.hasPermission(sc.getPermission()))
            .forEach(sc -> {
                String usage = sc.getUsage().isEmpty() ? "" : " " + sc.getUsage();
                messageManager.sendMessageNoPrefix(sender, "&e/hdv " + sc.getName() + usage + " &7- " + sc.getDescription());
            });
        messageManager.sendMessageNoPrefix(sender, "&6---------------------");
    }
}
