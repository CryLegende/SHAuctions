package org.Cry_Legende.sHAuctions.config;

import org.bukkit.configuration.InvalidConfigurationException;

import java.io.IOException;

/** Fichier de configuration illisible : porte le nom du fichier et l'erreur à montrer à l'expéditeur du reload. */
public class ConfigLoadException extends Exception {

    private final String file;

    public ConfigLoadException(String file, Exception cause) {
        super(summary(cause), cause);
        this.file = file;
    }

    public String getFile() {
        return file;
    }

    /** Erreur de lecture sur une seule ligne, sans l'extrait de YAML qui l'accompagne. */
    private static String summary(Exception cause) {
        String text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        text = text.replaceAll("\\s+", " ").trim();
        if (cause instanceof InvalidConfigurationException || cause instanceof IOException) {
            return text.length() > 220 ? text.substring(0, 220) + "..." : text;
        }
        return text;
    }
}
