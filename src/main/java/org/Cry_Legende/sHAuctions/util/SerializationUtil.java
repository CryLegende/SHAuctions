package org.Cry_Legende.sHAuctions.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger; // Assurez-vous d'utiliser java.util.logging.Logger

public class SerializationUtil {

    private static Logger logger; // Initialisé par le plugin principal

    // Méthode pour initialiser le logger depuis la classe principale du plugin
    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    /**
     * Sérialise un ItemStack en une chaîne Base64.
     *
     * @param itemStack L'ItemStack à sérialiser.
     * @return Une chaîne encodée en Base64 représentant l'ItemStack, ou null en cas d'erreur.
     */
    public static String serializeItemStack(ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeObject(itemStack);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());

        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.SEVERE, "Error serializing ItemStack: " + itemStack.getType(), e);
            } else {
                System.err.println("Error serializing ItemStack (logger not initialized): " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Désérialise une chaîne Base64 en un ItemStack.
     *
     * @param base64String La chaîne Base64 à désérialiser.
     * @return L'ItemStack désérialisé, ou null en cas d'erreur ou si la chaîne est invalide.
     */
    public static ItemStack deserializeItemStack(String base64String) {
        if (base64String == null || base64String.isEmpty()) {
            return null;
        }
        try {
            byte[] decodedData = Base64.getDecoder().decode(base64String);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedData);
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

                return (ItemStack) dataInput.readObject();

            }
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            // IllegalArgumentException peut être levée par Base64.getDecoder().decode() si la chaîne n'est pas valide
            if (logger != null) {
                logger.log(Level.SEVERE, "Error deserializing ItemStack from Base64 string", e);
            } else {
                System.err.println("Error deserializing ItemStack (logger not initialized): " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Sérialise un tableau d'ItemStack en une chaîne Base64.
     * Utile pour stocker des inventaires entiers, bien que non directement utilisé pour les enchères individuelles.
     *
     * @param items Le tableau d'ItemStack à sérialiser.
     * @return Une chaîne encodée en Base64, ou null en cas d'erreur.
     */
    public static String serializeItemStacks(ItemStack[] items) {
        if (items == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {

            dataOutput.writeInt(items.length); // Écrire la taille du tableau en premier
            for (ItemStack item : items) {
                dataOutput.writeObject(item); // Peut écrire des objets null si un slot est vide
            }
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());

        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.SEVERE, "Error serializing ItemStack array", e);
            } else {
                System.err.println("Error serializing ItemStack array (logger not initialized): " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Désérialise une chaîne Base64 en un tableau d'ItemStack.
     *
     * @param base64String La chaîne Base64 à désérialiser.
     * @return Un tableau d'ItemStack, ou null en cas d'erreur.
     */
    public static ItemStack[] deserializeItemStacks(String base64String) {
        if (base64String == null || base64String.isEmpty()) {
            return null;
        }
        try {
            byte[] decodedData = Base64.getDecoder().decode(base64String);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(decodedData);
                 BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {

                int size = dataInput.readInt();
                ItemStack[] items = new ItemStack[size];
                for (int i = 0; i < size; i++) {
                    items[i] = (ItemStack) dataInput.readObject(); // Peut lire des objets null
                }
                return items;

            }
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            if (logger != null) {
                logger.log(Level.SEVERE, "Error deserializing ItemStack array from Base64 string", e);
            } else {
                System.err.println("Error deserializing ItemStack array (logger not initialized): " + e.getMessage());
            }
            return null;
        }
    }
}