package com.sap.it.api.securestore;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sap.it.api.securestore.exception.SecureStoreException;

/**
 * Lokaler Nachbau von com.sap.it.api.securestore.SecureStoreService.
 *
 * Die Credentials werden aus der Testkonfiguration befuellt
 * (Abschnitt "credentials" in der config.json).
 */
public class SecureStoreService {

    private final Map<String, UserCredential> credentials = new LinkedHashMap<String, UserCredential>();

    public void register(String alias, String username, String password) {
        credentials.put(alias, new UserCredential(username, password));
    }

    public UserCredential getUserCredential(String alias) throws SecureStoreException {
        UserCredential credential = credentials.get(alias);
        if (credential == null) {
            throw new SecureStoreException("Kein Credential mit Alias '" + alias
                    + "' in der Testkonfiguration hinterlegt (Abschnitt \"credentials\").");
        }
        return credential;
    }
}
