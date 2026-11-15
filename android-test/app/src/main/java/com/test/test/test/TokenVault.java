package com.test.test.test;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.util.Base64;

/** Encrypts an imported personal token with an Android Keystore key. */
public final class TokenVault {
    private static final String ALIAS = "chaster_developer_token";
    private static final String PREFS = "chaster_secure";
    private final Context context;

    public TokenVault(Context context) { this.context = context.getApplicationContext(); }

    public String loadOrImport() throws Exception {
        File imported = new File(context.getFilesDir(), "chaster.jeton");
        if (imported.isFile()) {
            byte[] raw = readFile(imported);
            String token = new String(raw, StandardCharsets.UTF_8).trim();
            try {
                if (token.isEmpty()) throw new IllegalArgumentException("Le fichier chaster.jeton est vide");
                save(token);
                return token;
            } finally {
                java.util.Arrays.fill(raw, (byte) 0);
                if (!imported.delete()) throw new IllegalStateException("Impossible d'effacer le jeton importé");
            }
        }
        String encoded = prefs().getString("token", null);
        if (encoded == null) return null;
        byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
        byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public void save(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, payload, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, payload, cipher.getIV().length, encrypted.length);
        if (!prefs().edit().putString("token", Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("Impossible d'enregistrer le jeton chiffré");
        java.util.Arrays.fill(encrypted, (byte) 0); java.util.Arrays.fill(payload, (byte) 0);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private SharedPreferences prefs() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
