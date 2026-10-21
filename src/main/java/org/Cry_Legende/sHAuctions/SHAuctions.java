package org.Cry_Legende.sHAuctions;

import org.Cry_Legende.sHAuctions.command.AuctionCommand;
import org.Cry_Legende.sHAuctions.config.ConfigManager;
import org.Cry_Legende.sHAuctions.config.MenuConfigManager;
import org.Cry_Legende.sHAuctions.config.MessageManager;
import org.Cry_Legende.sHAuctions.database.DatabaseManager;
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
import org.Cry_Legende.sHAuctions.task.ExpirationTask;
import org.Cry_Legende.sHAuctions.task.RealTimeLoreUpdateTask;
import org.Cry_Legende.sHAuctions.util.SerializationUtil;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder; // Pour la vérification générale
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
    private ExpirationTask expirationTask;
    private MenuRefreshListener menuRefreshListener;
    private RealTimeLoreUpdateTask realTimeLoreUpdateTask;

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
        if (!databaseManager.initialize()) {
            getLogger().severe("Critical error: Failed to initialize database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Database initialized successfully.");

        permissionManager = new PermissionManager(this);
        guiManager = new GUIManager(this);
        chatInputManager = new ChatInputManager();
        auctionManager = new AuctionManager(this);
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
        getLogger().info("Commands registered.");

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new InventoryClickListener(this), this);
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new ChatInputListener(this), this);
        menuRefreshListener = new MenuRefreshListener(this);
        pm.registerEvents(menuRefreshListener, this);
        getLogger().info("Listeners registered.");

        expirationTask = new ExpirationTask(this);
        long expirationCheckIntervalTicks = configManager.getLong("tasks.expiration_check_interval_seconds", 300) * 20L;
        if (expirationCheckIntervalTicks <= 0) {
            getLogger().warning("Expiration check interval is zero or negative. ExpirationTask will not run.");
        } else {
            // Thread principal, comme l'achat et l'annulation : ces trois opérations ne peuvent plus
            // s'entrelacer sur la même enchère (sinon item acheté ET renvoyé en invendus).
            expirationTask.runTaskTimer(this, 20L * 60, expirationCheckIntervalTicks);
            getLogger().info("ExpirationTask started (Interval: " + (expirationCheckIntervalTicks / 20) + "s).");
        }

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
        getLogger().info("------------------------------------");
        getLogger().info(ChatColor.RED + pdf.getName() + " disabling...");

        if (expirationTask != null && !expirationTask.isCancelled()) {
            expirationTask.cancel();
            getLogger().info("ExpirationTask cancelled.");
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

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }
}
