package org.Cry_Legende.sHAuctions.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.Cry_Legende.sHAuctions.SHAuctions;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Level;

public class EconomyProvider {

    private final SHAuctions plugin;
    private Economy economy = null;
    private boolean vaultEnabled;

    public EconomyProvider(SHAuctions plugin) {
        this.plugin = plugin;
        this.vaultEnabled = plugin.getConfigManager().getBoolean("economy.use_vault", true);
    }

    public boolean setupEconomy() {
        if (!this.vaultEnabled) {
            plugin.getLogger().info("Vault economy integration is disabled in config.yml.");
            return false;
        }
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault plugin not found! Economy features will be unavailable.");
            this.vaultEnabled = false;
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("No economy plugin found by Vault! Economy features will be unavailable.");
            this.vaultEnabled = false;
            return false;
        }
        economy = rsp.getProvider();
        if (economy == null) {
            plugin.getLogger().warning("Vault found an economy provider, but it's null! Economy features will be unavailable.");
            this.vaultEnabled = false;
            return false;
        }
        plugin.getLogger().info("Successfully hooked into Vault economy: " + economy.getName());
        return true;
    }

    public String getEconomyName() {
        if (!isEconomyReady()) return "None (Disabled or Not Found)";
        return economy.getName();
    }

    public boolean isEconomyReady() {
        return this.vaultEnabled && this.economy != null;
    }

    public boolean hasEnough(OfflinePlayer player, double amount) { // Changé Player en OfflinePlayer pour plus de flexibilité
        if (!isEconomyReady()) {
            return true;
        }
        if (amount < 0) return true;
        return economy.has(player, amount);
    }

    /**
     * Retire de l'argent du compte d'un joueur EN LIGNE.
     *
     * @param player Le joueur en ligne.
     * @param amount Le montant à retirer (doit être positif).
     * @return true si la transaction a réussi.
     */
    public boolean withdraw(Player player, double amount) {
        if (!isEconomyReady()) {
            plugin.getLogger().warning("Attempted to withdraw money while economy is not ready!");
            return false;
        }
        if (amount <= 0) {
            plugin.getLogger().fine("Attempted to withdraw zero or negative amount: " + amount + " from player " + player.getName());
            return amount == 0;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Failed to withdraw " + formatCurrency(amount) + " from " + player.getName() + ". Reason: " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    /**
     * Retire de l'argent du compte d'un joueur (EN LIGNE OU HORS LIGNE).
     *
     * @param player Le joueur (OfflinePlayer).
     * @param amount Le montant à retirer (doit être positif).
     * @return true si la transaction a réussi.
     */
    public boolean withdraw(OfflinePlayer player, double amount) { // << SURCHARGE AJOUTÉE
        if (!isEconomyReady()) {
            plugin.getLogger().warning("Attempted to withdraw money while economy is not ready!");
            return false;
        }
        if (amount <= 0) {
            plugin.getLogger().fine("Attempted to withdraw zero or negative amount: " + amount + " from OfflinePlayer " + player.getName());
            return amount == 0;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount); // Vault supporte OfflinePlayer ici
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Failed to withdraw " + formatCurrency(amount) + " from OfflinePlayer " + player.getName() + ". Reason: " + response.errorMessage);
        }
        return response.transactionSuccess();
    }


    public boolean deposit(OfflinePlayer player, double amount) {
        if (!isEconomyReady()) {
            plugin.getLogger().warning("Attempted to deposit money while economy is not ready!");
            return false;
        }
        if (amount <= 0) {
            plugin.getLogger().fine("Attempted to deposit zero or negative amount: " + amount + " to OfflinePlayer " + player.getName());
            return amount == 0;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Failed to deposit " + formatCurrency(amount) + " to " + player.getName() + ". Reason: " + response.errorMessage);
        }
        return response.transactionSuccess();
    }

    public String formatCurrency(double amount) {
        if (!isEconomyReady()) {
            return String.format("%.2f %s", amount, plugin.getConfigManager().getString("economy.currency_symbol", "$"));
        }
        return economy.format(amount);
    }

    /**
     * Formate un montant en ajoutant, pour les gros prix, une forme abrégée entre parenthèses.
     * Ex: "1 000 000 000 SHC (1 Md)". Le seuil de déclenchement est configurable
     * (economy.detailed_format_threshold, défaut 1 000 000). En dessous du seuil, identique à
     * {@link #formatCurrency(double)}.
     */
    public String formatCurrencyDetailed(double amount) {
        String base = formatCurrency(amount);
        double threshold = plugin.getConfigManager().getDouble("economy.detailed_format_threshold", 1_000_000d);
        if (Math.abs(amount) < threshold) {
            return base;
        }
        return base + " (" + abbreviate(amount) + ")";
    }

    /**
     * Abrège un montant : milliers (k), millions (M), milliards (Md).
     */
    public String abbreviate(double amount) {
        double abs = Math.abs(amount);
        String suffix;
        double value;
        if (abs >= 1_000_000_000d) {
            value = amount / 1_000_000_000d;
            suffix = "Md";
        } else if (abs >= 1_000_000d) {
            value = amount / 1_000_000d;
            suffix = "M";
        } else if (abs >= 1_000d) {
            value = amount / 1_000d;
            suffix = "k";
        } else {
            return String.valueOf((long) amount);
        }
        // Jusqu'à 2 décimales, sans zéros inutiles.
        String num = String.format(java.util.Locale.FRANCE, "%.2f", value);
        if (num.contains(",")) {
            num = num.replaceAll("0+$", "").replaceAll(",$", "");
        }
        return num + " " + suffix;
    }

    public String getCurrencySymbol() {
        return plugin.getConfigManager().getString("economy.currency_symbol", "$");
    }
}
