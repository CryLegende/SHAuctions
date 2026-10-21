package org.Cry_Legende.sHAuctions.util;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PlaceholderUtil {

    /**
     * Applique une map de placeholders à une chaîne de caractères.
     *
     * @param text         La chaîne originale.
     * @param placeholders La map de placeholders (clé) et leurs valeurs de remplacement.
     * @return La chaîne avec les placeholders remplacés.
     */
    public static String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Applique une map de placeholders à chaque chaîne d'une liste.
     *
     * @param list         La liste de chaînes originales.
     * @param placeholders La map de placeholders.
     * @return Une nouvelle liste avec les placeholders remplacés dans chaque chaîne.
     */
    public static List<String> applyPlaceholdersInList(List<String> list, Map<String, String> placeholders) {
        if (list == null || placeholders == null || placeholders.isEmpty()) {
            return list;
        }
        return list.stream()
            .map(line -> applyPlaceholders(line, placeholders))
            .collect(Collectors.toList());
    }

    /**
     * Gère les marqueurs de type de vente dans une ligne de lore.
     * <ul>
     *     <li>{@code {sale}} : ligne réservée aux ventes directes.</li>
     *     <li>{@code {bid}} : ligne réservée aux enchères.</li>
     * </ul>
     * Le marqueur est retiré si le type correspond, sinon la ligne est masquée.
     *
     * @param line  La ligne de lore (peut contenir {sale} ou {bid}).
     * @param isBid true si l'item est une enchère (BID), false pour une vente directe (SALE).
     * @return La ligne sans marqueur, ou {@code null} si la ligne doit être masquée pour ce type.
     */
    public static String stripTypeMarker(String line, boolean isBid) {
        if (line == null) return null;
        if (line.contains("{sale}")) {
            if (isBid) return null;
            return line.replace("{sale}", "");
        }
        if (line.contains("{bid}")) {
            if (!isBid) return null;
            return line.replace("{bid}", "");
        }
        return line;
    }
}
