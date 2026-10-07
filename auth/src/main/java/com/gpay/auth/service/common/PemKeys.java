package com.gpay.auth.service.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Loads the RS256 signing key from a PKCS#8 PEM file ("BEGIN PRIVATE KEY"). Fails fast on startup. */
public final class PemKeys {
    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";

    private PemKeys() {}

    public static RSAPrivateCrtKey readRsaPrivateKey(Path path) {
        try {
            String pem = Files.readString(path);
            if (!pem.contains(BEGIN)) {
                throw new IllegalStateException(path + " is not a PKCS#8 PEM private key (expected '" + BEGIN + "')");
            }
            String base64 = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            // CRT form carries the public exponent, so the public key can be derived without a second file
            return (RSAPrivateCrtKey) key;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("Cannot load JWT private key from " + path + ": " + e.getMessage(), e);
        }
    }
}
