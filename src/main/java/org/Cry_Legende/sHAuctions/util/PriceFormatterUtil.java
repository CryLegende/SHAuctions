package org.Cry_Legende.sHAuctions.util;

import org.Cry_Legende.sHAuctions.SHAuctions;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.logging.Level;

public class PriceFormatterUtil {

    private static DecimalFormat priceFormatter = null;
    private static String lastFormatPattern = "";

    private static void initializeFormatter() {
        if (SHAuctions.getInstance() == null || SHAuctions.getInstance().getConfigManager() == null) {
            // Ne peut pas initialiser si le plugin n'est pas prêt, utiliser un formateur par défaut très basique
            if (priceFormatter == null) { // Initialiser une seule fois au cas où
                priceFormatter = (DecimalFormat) NumberFormat.getNumberInstance(Locale.US);
                priceFormatter.applyPattern("#,##0.00"); // Un défaut sûr
                if (SHAuctions.getInstance() != null)
                    SHAuctions.getInstance().getLogger().warning("PriceFormatterUtil: Plugin or ConfigManager not ready during init, using basic default.");
            }
            return;
        }

        String currentPattern = SHAuctions.getInstance().getConfigManager().getString("formatting.price_format", "#,##0.00");
        if (priceFormatter == null || !lastFormatPattern.equals(currentPattern)) {
            try {
                NumberFormat nf = NumberFormat.getNumberInstance(Locale.US); // Forcer le point décimal
                priceFormatter = (DecimalFormat) nf;
                priceFormatter.applyPattern(currentPattern);
                lastFormatPattern = currentPattern;
                SHAuctions.getInstance().getLogger().fine("PriceFormatter initialized with pattern: " + currentPattern);
            } catch (IllegalArgumentException e) {
                SHAuctions.getInstance().getLogger().log(Level.WARNING, "Invalid price_format pattern in config.yml: '" + currentPattern + "'. Using default #,##0.00.", e);
                NumberFormat nfFallback = NumberFormat.getNumberInstance(Locale.US);
                priceFormatter = (DecimalFormat) nfFallback;
                priceFormatter.applyPattern("#,##0.00");
                lastFormatPattern = "#,##0.00";
            }
        }
    }

    public static String formatPrice(double price) {
        initializeFormatter(); // S'assure que le formateur est à jour
        if (priceFormatter == null) { // Double vérification si l'initialisation a échoué silencieusement
            return String.valueOf(price); // Fallback très basique
        }
        String base = priceFormatter.format(price);

        // Pour les gros prix, ajouter une forme abrégée entre parenthèses (ex: "1,000,000,000 (1 Md)").
        if (SHAuctions.getInstance() != null && SHAuctions.getInstance().getConfigManager() != null) {
            boolean enabled = SHAuctions.getInstance().getConfigManager().getBoolean("formatting.abbreviate_large_prices", true);
            double threshold = SHAuctions.getInstance().getConfigManager().getDouble("formatting.abbreviate_threshold", 1_000_000d);
            if (enabled && Math.abs(price) >= threshold) {
                return base + " (" + abbreviate(price) + ")";
            }
        }
        return base;
    }

    /**
     * Abrège un montant : milliers (k), millions (M), milliards (Md).
     */
    public static String abbreviate(double amount) {
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
        String num = String.format(Locale.US, "%.2f", value);
        if (num.contains(".")) {
            num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return num + " " + suffix;
    }

    /**
     * Formate un prix et ajoute le symbole de la monnaie.
     * Préférer EconomyProvider.formatCurrency() si l'économie est active.
     *
     * @param price Le prix.
     * @return Le prix formaté avec le symbole.
     */
    public static String formatPriceWithSymbol(double price) {
        String formattedPrice = formatPrice(price);
        String symbol = "$"; // Symbole par défaut
        if (SHAuctions.getInstance() != null && SHAuctions.getInstance().getConfigManager() != null) {
            symbol = SHAuctions.getInstance().getConfigManager().getString("economy.currency_symbol", "$");
        }
        // Gérer le placement du symbole (pourrait être configurable)
        // Exemple simple: après avec un espace
        return formattedPrice + " " + symbol;
    }
}
