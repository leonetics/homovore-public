package dev.leonetic.manager.account;

import java.util.Base64;

public class TokenCrypto {
    private static final byte[] KEY = {-83, 45, 112, 67, 99, 18, -55, 93, 7, 32, 108, -44, 79, -18, 14, 120};

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        byte[] data = plain.getBytes();
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
        }
        return Base64.getEncoder().encodeToString(result);
    }

    public static String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        byte[] data = Base64.getDecoder().decode(encoded);
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
        }
        return new String(result);
    }
}
