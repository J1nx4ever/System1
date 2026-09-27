package com.mine.dispatch.capability.access;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class PasswordHasher {
    private static final int ITERATIONS = 210_000;
    private static final int BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + enc(salt) + "$" + enc(derive(password, salt, ITERATIONS));
    }

    static boolean verify(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", 4);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, Integer.parseInt(parts[1])));
        } catch (RuntimeException exception) { return false; }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, BYTES * 8);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) { throw new IllegalStateException("password hashing failed", exception); }
    }
    private static String enc(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private PasswordHasher() {}
}
