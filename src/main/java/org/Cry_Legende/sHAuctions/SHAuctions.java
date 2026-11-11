package org.Cry_Legende.sHAuctions;

import org.Cry_Legende.sHAuctions.command.AdminCommand;
import org.Cry_Legende.sHAuctions.command.AuctionCommand;
import org.Cry_Legende.sHAuctions.config.ConfigLoadException;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MenuConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
import org.Cry_Legende.sHAuctions.dialog.DialogListener;
import org.Cry_Legende.sHAuctions.dialog.DialogManager;
import org.Cry_Legende.sHAuctions.economy.EconomyProvider;
import org.Cry_Legende.sHAuctions.gui.AbstractMenu; // Import pour le cast
import org.Cry_Legende.sHAuctions.gui.GUIManager;
import org.Cry_Legende.sHAuctions.listener.ChatInputListener;
import org.Cry_Legende.sHAuctions.listener.InventoryClickListener;
import org.Cry_Legende.sHAuctions.listener.PlayerJoinListener;
import org.Cry_Legende.sHAuctions.listener.MenuRefreshListener;
import org.Cry_Legende.sHAuctions.manager.AuctionManager;
import org.Cry_Legende.sHAuctions.manager.ChatInputManager;
import org.Cry_Legende.sHAuctions.manager.PermissionManager;
import org.Cry_Legende.sHAuctions.manager.SharedFlows;
import org.Cry_Legende.sHAuctions.model.MenuDefinition;
import org.Cry_Legende.sHAuctions.network.NetBus;
import org.Cry_Legende.sHAuctions.network.ShNetworkBus;
import org.Cry_Legende.sHAuctions.task.ExpirationTask;
import org.Cry_Legende.sHAuctions.task.RealTimeLoreUpdateTask;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder; // Pour la vérification générale
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;

public class SHAuctions extends JavaPlugin {

    private static SHAuctions instance;

    private ConfigManager configManager;
    private MenuConfigManager menuConfigManager;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private EconomyProvider economyProvider;
    private AuctionManager auctionManager;
    private GUIManager guiManager;
    private PermissionManager permissionManager;
    private ChatInputManager chatInputManager;
    private org.Cry_Legende.sHAuctions.wanted.WantedManager wantedManager;
    // null si l'API des dialogues est absente du serveur (saisie par chat uniquement)
    private DialogManager dialogManager;
    private ExpirationTask expirationTask;
    private MenuRefreshListener menuRefreshListener;
    private RealTimeLoreUpdateTask realTimeLoreUpdateTask;
    // Mode réseau (SHNetwork présent) : base MariaDB commune, échéances sur le serveur meneur ; null sinon
    private SharedFlows sharedFlows;

    @Override
    public void onEnable() {
        instance = this;
        long startTime = System.currentTimeMillis();
        PluginDescriptionFile pdf = getDescription();

        getLogger().info("------------------------------------");
        getLogger().info(ChatColor.GREEN + pdf.getName() + " v" + pdf.getVersion());
        getLogger().info("Author: " + String.join(", ", pdf.getAuthors()));
        getLogger().info("------------------------------------");

        SerializationUtil.init(getLogger());

        if (!loadConfigurations()) {
            getLogger().severe("Critical error: Failed to load configurations. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Configurations loaded successfully.");

        economyProvider = new EconomyProvider(this);
        if (!economyProvider.setupEconomy()) {
            if (configManager.getBoolean("economy.use_vault", true)) {
                getLogger().severe("Vault not found or no economy plugin detected, but 'economy.use_vault' is true.");
                getLogger().severe("SHAuctions requires an economy plugin to function fully.");
                getLogger().warning("Plugin will run with limited (or no) economic functionality.");
            } else {
                getLogger().info("Vault economy integration is disabled in config.yml.");
            }
        } else {
            getLogger().info("Vault & Economy plugin hooked successfully: " + economyProvider.getEconomyName());
        }

        databaseManager = new DatabaseManager(this);
        NetBus bus = null;
        if (networkWanted()) {
            // Sur le réseau, l'HDV ne retombe jamais sur la base SQLite locale : sur un serveur copié d'un autre, elle
            // contient des annonces déjà vendues ailleurs.
            try {
                bus = ShNetworkBus.open();
            } catch (RuntimeException | LinkageError e) {
                getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable : HDV désactivé sur ce serveur.", e);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            if (!databaseManager.initializeShared(
                configManager.getString("reseau.base-de-donnees.url", ""),
                configManager.getString("reseau.base-de-donnees.utilisateur", ""),
                configManager.getString("reseau.base-de-donnees.mot-de-passe", ""),
                Math.max(500, configManager.getInt("reseau.base-de-donnees.delai-ms", 3000)),
                bus.serverName())) {
                getLogger().severe("Base partagée du mode réseau indisponible : HDV désactivé sur ce serveur.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("SHNetwork détecté : hôtel des ventes commun à tout le réseau (MariaDB).");
        } else if (!databaseManager.initialize()) {
            getLogger().severe("Critical error: Failed to initialize database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Database initialized successfully.");

        permissionManager = new PermissionManager(this);
        guiManager = new GUIManager(this);
        chatInputManager = new ChatInputManager();
        auctionManager = new AuctionManager(this);
        wantedManager = new org.Cry_Legende.sHAuctions.wanted.WantedManager(this);
        if (bus != null) {
            sharedFlows = new SharedFlows(this, auctionManager, bus, databaseManager.getSharedAuctions(),
                configManager.getString("reseau.canal", "shauctions"));
            auctionManager.setShared(sharedFlows);
        }
        getLogger().info("Core managers initialized.");

        try {
            if (getCommand("hdv") != null) {
                getCommand("hdv").setExecutor(new AuctionCommand(this));
            } else {
                getLogger().severe("Command 'hdv' is not defined in plugin.yml! Command will not be registered.");
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to register command 'hdv'.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getCommand("shauctions") != null) {
            AdminCommand admin = new AdminCommand(this);
            getCommand("shauctions").setExecutor(admin);
            getCommand("shauctions").setTabCompleter(admin);
        } else {
            getLogger().severe("Command 'shauctions' is not defined in plugin.yml! Admin command will not be registered.");
        }
        getLogger().info("Commands registered.");

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new InventoryClickListener(this), this);
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new ChatInputListener(this), this);
        registerDialogs(pm);
        menuRefreshListener = new MenuRefreshListener(this);
        pm.registerEvents(menuRefreshListener, this);
        if (sharedFlows != null) {
            sharedFlows.enable();
        }
        getLogger().info("Listeners registered.");

        scheduleExpirationTask();

        realTimeLoreUpdateTask = new RealTimeLoreUpdateTask(this);
        // Rafraîchissement du lore toutes les 2s (40 ticks) : le temps restant en secondes reste
        // lisible tout en divisant par 2 la charge par rapport à 20 ticks.
        realTimeLoreUpdateTask.runTaskTimer(this, 40L, 40L);
        getLogger().info("RealTimeLoreUpdateTask started.");


        long endTime = System.currentTimeMillis();
        getLogger().info(ChatColor.GREEN + pdf.getName() + " has been enabled successfully in " + (endTime - startTime) + "ms!");
        getLogger().info("------------------------------------");
    }

    @Override
    public void onDisable() {
        PluginDescriptionFile pdf = getDescription();

        if (dialogManager != null) {
            dialogManager.shutdown();
        }
        getLogger().info("------------------------------------");
        getLogger().info(ChatColor.RED + pdf.getName() + " disabling...");

        if (expirationTask != null) {
            expirationTask.cancel();
            expirationTask = null;
            getLogger().info("ExpirationTask cancelled.");
        }
        if (sharedFlows != null) {
            sharedFlows.disable();
            sharedFlows = null;
        }
        if (realTimeLoreUpdateTask != null && !realTimeLoreUpdateTask.isCancelled()) {
            realTimeLoreUpdateTask.cancel();
            getLogger().info("RealTimeLoreUpdateTask cancelled.");
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        if (guiManager != null) {
            // Clôturer les inventaires ouverts par CE plugin
            for (Player player : Bukkit.getOnlinePlayers()) {
                InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof AbstractMenu) {
                    AbstractMenu openMenu = (AbstractMenu) holder;
                    // Vérifier si le menu appartient bien à cette instance du plugin
                    // (AbstractMenu a un champ 'plugin' qui est l'instance de SHAuctions)
                    if (openMenu.getPluginReference() == this) { // MODIFIÉ ICI
                        player.closeInventory();
                        getLogger().fine("Closed SHAuctions GUI for " + player.getName() + " during disable.");
                    }
                }
            }
            guiManager.getOpenMenus().clear();
        }

        getLogger().info(ChatColor.RED + pdf.getName() + " has been disabled.");
        getLogger().info("------------------------------------");
        instance = null;
    }

    /**
     * Lance (ou relance) la tâche des échéances avec l'intervalle courant de {@code tasks.expiration_check_interval_seconds}.
     * Thread principal, comme l'achat et l'annulation : ces trois opérations ne peuvent plus s'entrelacer sur la même
     * enchère (sinon item acheté ET renvoyé en invendus).
     */
    private void scheduleExpirationTask() {
        if (expirationTask != null) {
            expirationTask.cancel();
            expirationTask = null;
        }
        long intervalTicks = configManager.getLong("tasks.expiration_check_interval_seconds", 300) * 20L;
        if (intervalTicks <= 0) {
            getLogger().warning("Expiration check interval is zero or negative. ExpirationTask will not run.");
            return;
        }
        expirationTask = new ExpirationTask(this);
        expirationTask.runTaskTimer(this, 20L * 60, intervalTicks);
        getLogger().info("ExpirationTask started (Interval: " + (intervalTicks / 20) + "s).");
    }

    /**
     * Recharge config.yml, messages.yml et les menus. Chaque fichier est lu dans un objet à part : au premier fichier
     * illisible rien n'est remplacé et l'expéditeur reçoit le nom du fichier et l'erreur. Les données (base, ventes,
     * invendus, recherches) ne sont jamais touchées.
     *
     * @return vrai si tout a été rechargé
     */
    public boolean reloadAll(CommandSender sender) {
        long start = System.currentTimeMillis();
        YamlConfiguration newConfig;
        YamlConfiguration newMessages;
        Map<String, MenuDefinition> newMenus;
        try {
            newConfig = configManager.read();
            newMessages = messageManager.read();
            newMenus = menuConfigManager.readMenusStrict();
        } catch (ConfigLoadException e) {
            getLogger().severe("Reload annulé : " + e.getFile() + " illisible (" + e.getMessage() + "). Configuration précédente conservée.");
            messageManager.sendMessage(sender, "reload_file_error",
                Map.of("file", e.getFile(), "error", e.getMessage()));
            return false;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Reload annulé : erreur inattendue. Configuration précédente conservée.", e);
            messageManager.sendMessage(sender, "reload_failure");
            return false;
        }
        configManager.apply(newConfig);
        messageManager.apply(newMessages);
        menuConfigManager.apply(newMenus);
        scheduleExpirationTask();
        long duration = System.currentTimeMillis() - start;
        messageManager.sendMessage(sender, "reload_success", Map.of("duration", String.valueOf(duration)));
        messageManager.sendMessage(sender, "reload_restart_note");
        getLogger().info("Configuration reloaded by " + sender.getName() + " in " + duration + "ms.");
        return true;
    }

    /** Mode réseau voulu : SHNetwork chargé et {@code reseau.actif} vrai (par défaut). */
    private boolean networkWanted() {
        return configManager.getBoolean("reseau.actif", true) && getServer().getPluginManager().isPluginEnabled("SHNetwork");
    }

    /**
     * Vrai si ce serveur règle les échéances : toujours sur un serveur seul, seulement le meneur en mode réseau.
     */
    public boolean isExpiryOwner() {
        return sharedFlows == null || sharedFlows.isExpiryOwner();
    }

    /**
     * Saisie par dialogue natif (1.21.6+). Si l'API des dialogues n'existe pas sur ce serveur, le plugin garde la saisie
     * par chat : un dialogue n'est jamais indispensable.
     */
    private void registerDialogs(PluginManager pm) {
        try {
            DialogManager manager = new DialogManager(this);
            pm.registerEvents(new DialogListener(this, manager), this);
            dialogManager = manager;
            getLogger().info("Saisie par dialogue prête (clients 1.21.6 et plus ; actif: "
                + configManager.getBoolean("dialogues.actif", true) + ").");
        } catch (RuntimeException | LinkageError e) {
            dialogManager = null;
            getLogger().log(Level.WARNING, "API des dialogues indisponible : saisie par chat uniquement.", e);
        }
    }

    private boolean loadConfigurations() {
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            menuConfigManager = new MenuConfigManager(this);
            menuConfigManager.loadMenus();
            messageManager = new MessageManager(this);
            messageManager.loadMessages();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "An unexpected error occurred while loading configurations", e);
            return false;
        }
    }

    public static SHAuctions getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MenuConfigManager getMenuConfigManager() {
        return menuConfigManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public EconomyProvider getEconomyProvider() {
        return economyProvider;
    }

    public AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public GUIManager getGuiManager() {
        return guiManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /** Onglet « Recherches » de l'HDV. */
    public org.Cry_Legende.sHAuctions.wanted.WantedManager getWantedManager() {
        return wantedManager;
    }

    public MenuRefreshListener getMenuRefreshListener() {
        return menuRefreshListener;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    /**
     * Gestionnaire de saisie par dialogue, ou null si l'API des dialogues est indisponible.
     */
    public DialogManager getDialogManager() {
        return dialogManager;
    }
}
