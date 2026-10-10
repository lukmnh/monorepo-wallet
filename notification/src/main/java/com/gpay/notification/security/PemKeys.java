package com.gpay.notification.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads the JWT verification key from an X.509 PEM file ("BEGIN PUBLIC KEY"). Fails fast on startup. */
public final class PemKeys {
    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private PemKeys() {}

    public static PublicKey readRsaPublicKey(Path path) {
        try {
            String pem = Files.readString(path);
            if (!pem.contains(BEGIN)) {
                throw new IllegalStateException(path + " is not a PEM public key (expected '" + BEGIN + "')");
            }
            String base64 = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load JWT public key from " + path + ": " + e.getMessage(), e);
        }
    }
}
