package dev.hestia.web.service;

/** Fichier refusé à l'upload ; le message est destiné à l'utilisateur. */
public class ImageRefuseeException extends RuntimeException {

    public ImageRefuseeException(String message) {
        super(message);
    }

    public ImageRefuseeException(String message, Throwable cause) {
        super(message, cause);
    }
}
