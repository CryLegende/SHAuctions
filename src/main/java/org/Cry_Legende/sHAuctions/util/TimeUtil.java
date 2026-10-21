package org.Cry_Legende.sHAuctions.util;

import org.Cry_Legende.sHAuctions.SHAuctions; // Pour accéder à ConfigManager si besoin pour les formats

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class TimeUtil {

    /**
     * Formate une durée en secondes en une chaîne lisible (ex: "2j 3h 15m 30s").
     * S'adapte pour n'afficher que les unités pertinentes.
     *
     * @param totalSeconds La durée totale en secondes.
     * @return Une chaîne formatée de la durée.
     */
    public static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) {
            return "N/A"; // Ou une autre indication pour une durée invalide
        }
        if (totalSeconds == 0) {
            return "0s"; // Ou "Maintenant", "Expiré" selon le contexte
        }

        long days = TimeUnit.SECONDS.toDays(totalSeconds);
        totalSeconds -= TimeUnit.DAYS.toSeconds(days);
        long hours = TimeUnit.SECONDS.toHours(totalSeconds);
        totalSeconds -= TimeUnit.HOURS.toSeconds(hours);
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds);
        totalSeconds -= TimeUnit.MINUTES.toSeconds(minutes);
        long seconds = totalSeconds;

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        // Récupérer les suffixes depuis la config (optionnel, sinon utiliser des valeurs fixes)
        // Pour l'instant, utilisons des valeurs fixes pour la simplicité.
        // On pourrait les rendre configurables plus tard.
        String daySuffix = SHAuctions.getInstance().getConfigManager().getString("time_remaining_format.days", "j");
        String hourSuffix = SHAuctions.getInstance().getConfigManager().getString("time_remaining_format.hours", "h");
        String minuteSuffix = SHAuctions.getInstance().getConfigManager().getString("time_remaining_format.minutes", "m");
        String secondSuffix = SHAuctions.getInstance().getConfigManager().getString("time_remaining_format.seconds", "s");


        if (days > 0) {
            sb.append(days).append(daySuffix);
            hasContent = true;
        }
        if (hours > 0) {
            if (hasContent) sb.append(" ");
            sb.append(hours).append(hourSuffix);
            hasContent = true;
        }
        if (minutes > 0) {
            if (hasContent) sb.append(" ");
            sb.append(minutes).append(minuteSuffix);
            hasContent = true;
        }
        // Toujours afficher les secondes si c'est la seule unité restante ou si le total est < 1 minute
        if (seconds > 0 || !hasContent) {
            if (hasContent) sb.append(" ");
            sb.append(seconds).append(secondSuffix);
        }

        // Si la chaîne est vide (ce qui ne devrait pas arriver avec la logique ci-dessus pour 0s), retourner "0s"
        if (sb.length() == 0 && totalSeconds == 0) { // totalSeconds est 0 ici à cause des soustractions successives
            return "0" + secondSuffix;
        }


        return sb.toString();
    }

    /**
     * Formate un timestamp Unix (en secondes) en une chaîne de date/heure lisible
     * en utilisant le format défini dans config.yml.
     *
     * @param unixTimestampSecondes Le timestamp Unix en secondes.
     * @return Une chaîne de date/heure formatée, ou une chaîne d'erreur si le format est invalide.
     */
    public static String formatDate(long unixTimestampSecondes) {
        if (unixTimestampSecondes <= 0) {
            return "Date invalide";
        }
        try {
            String dateFormatPattern = SHAuctions.getInstance().getConfigManager().getString("date_format", "dd/MM/yyyy HH:mm:ss");
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormatPattern);
            Date date = new Date(unixTimestampSecondes * 1000L); // SimpleDateFormat attend des millisecondes
            return sdf.format(date);
        } catch (IllegalArgumentException e) {
            SHAuctions.getInstance().getLogger().warning("Invalid date_format pattern in config.yml: " + e.getMessage());
            // Fallback to a default format
            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yy HH:mm");
            Date date = new Date(unixTimestampSecondes * 1000L);
            return sdf.format(date);
        }
    }

    /**
     * Convertit une chaîne de durée (ex: "1h30m", "2d", "10s") en secondes.
     * Supporte j/d (jours), h (heures), m (minutes), s (secondes).
     *
     * @param durationString La chaîne de durée.
     * @return La durée en secondes, ou -1 si la chaîne est invalide.
     */
    public static long parseDurationToSeconds(String durationString) {
        if (durationString == null || durationString.trim().isEmpty()) {
            return -1;
        }

        long totalSeconds = 0;
        String currentNumber = "";
        durationString = durationString.toLowerCase().trim();

        for (char c : durationString.toCharArray()) {
            if (Character.isDigit(c)) {
                currentNumber += c;
            } else {
                if (currentNumber.isEmpty() && (c == 'j' || c == 'd' || c == 'h' || c == 'm' || c == 's')) {
                    // Permet "h" ou "m" sans chiffre avant (implique 1) si currentNumber est vide
                    // Mais pour être plus strict, on pourrait exiger un chiffre.
                    // Pour l'instant, soyons plus flexible : s'il y a une unité sans chiffre, on ignore cette partie.
                    // Si on veut que 'h' signifie '1h', il faut ajouter '1' à currentNumber.
                    // Pour l'instant, on ignore si pas de nombre.
                    if (currentNumber.isEmpty()) { // Si toujours vide, c'est une unité sans nombre.
                        // On pourrait retourner -1 ici pour indiquer une erreur de format.
                        // Ou juste ignorer cette unité.
                        continue;
                    }
                }
                if (currentNumber.isEmpty()) continue; // Si pas de nombre avant l'unité, on l'ignore.


                long value;
                try {
                    value = Long.parseLong(currentNumber);
                } catch (NumberFormatException e) {
                    return -1; // Nombre invalide
                }

                switch (c) {
                    case 'j': // jour
                    case 'd': // day
                        totalSeconds += value * 24 * 60 * 60;
                        break;
                    case 'h': // heure
                        totalSeconds += value * 60 * 60;
                        break;
                    case 'm': // minute
                        totalSeconds += value * 60;
                        break;
                    case 's': // seconde
                        totalSeconds += value;
                        break;
                    default:
                        // Caractère inconnu après un nombre, peut être une erreur de format
                        // ou la fin de la chaîne si le dernier segment est juste un nombre (ex: "10" -> 10 secondes par défaut?)
                        // Pour l'instant, si c'est un caractère inconnu, c'est une erreur.
                        return -1;
                }
                currentNumber = ""; // Réinitialiser pour le prochain segment
            }
        }

        // S'il reste un nombre à la fin sans unité (ex: /hdv sell <prix> 10 -> 10 heures par défaut?)
        // Ou si la chaîne entière n'est qu'un nombre
        if (!currentNumber.isEmpty()) {
            try {
                long value = Long.parseLong(currentNumber);
                // Décider d'une unité par défaut si aucune n'est spécifiée.
                // Par exemple, si c'est juste "30", est-ce 30 secondes ou 30 heures ?
                // Le configManager pourrait avoir une "default_duration_unit_if_unspecified"
                // Pour l'instant, si c'est juste un nombre, on peut le considérer comme des secondes.
                // Ou, pour la commande /hdv sell, on pourrait dire que l'unité par défaut est l'heure.
                // La commande sell devra gérer cette interprétation.
                // Cette fonction parse, elle ne décide pas de l'unité par défaut si absente.
                // Si on veut que "10" soit 10 secondes, on ajouterait:
                // totalSeconds += value;
                // Mais c'est ambigu. Pour l'instant, un nombre seul sans unité à la fin est une erreur de format.
                return -1; // Un nombre seul à la fin sans unité est considéré comme mal formaté ici.
                // La commande appelante devra s'en charger.
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        if (totalSeconds == 0 && !durationString.matches("0[jdms]?")) { // Si le total est 0 mais que la chaîne n'était pas "0" ou "0s" etc.
            return -1; // Eviter "abc" -> 0s
        }


        return totalSeconds;
    }

    /**
     * Parse une durée saisie par un joueur de façon souple, partagée par /hdv sell et /hdv bid
     * (cohérence des deux commandes) :
     * <ul>
     *     <li>Format avec unités (ex: "1h30m", "2j", "45s") via {@link #parseDurationToSeconds(String)}.</li>
     *     <li>Nombre nu (ex: "12") interprété comme des HEURES.</li>
     * </ul>
     *
     * @param input La chaîne saisie.
     * @return La durée en secondes, ou -1 si le format est invalide.
     */
    public static long parseDurationFlexible(String input) {
        long parsed = parseDurationToSeconds(input);
        if (parsed != -1) {
            return parsed;
        }
        // Repli : un nombre nu est interprété comme des heures.
        try {
            long hours = Long.parseLong(input.trim());
            return hours > 0 ? hours * 3600L : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}