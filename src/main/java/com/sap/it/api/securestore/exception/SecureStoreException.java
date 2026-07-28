package com.sap.it.api.securestore.exception;

/** Lokaler Nachbau von com.sap.it.api.securestore.exception.SecureStoreException. */
public class SecureStoreException extends Exception {

    private static final long serialVersionUID = 1L;

    public SecureStoreException(String message) {
        super(message);
    }

    public SecureStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
