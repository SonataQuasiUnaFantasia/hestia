package dev.hestia.web.entity;

import lombok.Getter;

/** Formats d'entrée acceptés, tels que détectés dans le contenu du fichier (jamais d'après son nom). */
@Getter
public enum FormatImage {
    JPEG("jpg"),
    PNG("png"),
    WEBP("webp");

    /** Extension du fichier original stocké. */
    private final String extension;

    FormatImage(String extension) {
        this.extension = extension;
    }
}
