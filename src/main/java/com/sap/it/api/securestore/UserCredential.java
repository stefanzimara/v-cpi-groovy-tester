package com.sap.it.api.securestore;

/** Lokaler Nachbau von com.sap.it.api.securestore.UserCredential. */
public class UserCredential {

    private final String username;
    private final char[] password;

    public UserCredential(String username, String password) {
        this.username = username;
        this.password = password == null ? new char[0] : password.toCharArray();
    }

    public String getUsername() {
        return username;
    }

    public char[] getPassword() {
        return password;
    }
}
