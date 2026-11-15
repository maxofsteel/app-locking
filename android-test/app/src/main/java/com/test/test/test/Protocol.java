package com.test.test.test;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Pure wire encoding. No Android, network, timers or device state assumptions. */
public final class Protocol {
    private Protocol() { }
    private static final byte[] AES_KEY = {
        (byte) 0x8f, 0x2a, 0x1c, 0x7b, 0x4d, 0x5e, (byte) 0x90, 0x63,
        (byte) 0xc8, (byte) 0xb7, (byte) 0xa6, (byte) 0xd5, (byte) 0xe4, (byte) 0xf3, 0x21, 0x09
    };

    private static byte u8(int value) {
        if (value < 0 || value > 255) throw new IllegalArgumentException("Valeur attendue : 0 à 255");
        return (byte) value;
    }

    public static byte[] fixed(int opcode, int... parameters) {
        if (parameters.length > 6) throw new IllegalArgumentException("Six paramètres maximum");
        byte[] data = new byte[10];
        data[0] = (byte) 0xaa; data[1] = 9; data[2] = u8(opcode); data[9] = (byte) 0xff;
        for (int i = 0; i < parameters.length; i++) data[i + 3] = u8(parameters[i]);
        return data;
    }

    public static byte[] battery(DeviceProfile profile, byte[] token) throws GeneralSecurityException {
        switch (profile.family) {
            case OLD: return new byte[] {(byte) 0xaa, 3};
            case SHORT: return new byte[] {(byte) 0xaa, 0};
            case AES: return aesCommand(token, 1, 0, 1);
            default: return fixed(5);
        }
    }

    public static byte[] lock(DeviceProfile profile, boolean close) {
        if (close ? !profile.lock : !profile.unlock)
            throw new IllegalArgumentException("Commande serrure non établie pour ce modèle");
        if (profile.family == DeviceProfile.Family.OLD)
            // The reference app's ordinary AA-A1002 open action sends voltage 0.
            return new byte[] {(byte) 0xaa, 1, 1, 0};
        return fixed(close ? 3 : 2);
    }

    /** Explicit minutes input; no implicit conversion of seconds or chosen release voltage. */
    public static byte[] timer(DeviceProfile profile, int minutes, int releaseVoltage) {
        if (profile != DeviceProfile.A1001 && profile != DeviceProfile.A1012)
            throw new IllegalArgumentException("Minuterie embarquée non établie");
        if (minutes < 0 || minutes > 65535) throw new IllegalArgumentException("Minutes : 0 à 65535");
        return fixed(4, minutes >>> 8, minutes & 255, 1, 1, releaseVoltage, 2);
    }

    public static byte[] electric(DeviceProfile profile, boolean continuous, int frequency,
                                  int voltage, int duration) {
        if (!profile.electric || profile.family != DeviceProfile.Family.FIXED)
            throw new IllegalArgumentException("Utiliser la commande propre à ce profil");
        return fixed(1, frequency, continuous ? 1 : 0, voltage, duration);
    }

    public static byte[] classic(DeviceProfile profile, int movementFrequency, int movementIntensity,
                                 int electricFrequency, int voltage) {
        if (!profile.movement && !profile.electric)
            throw new IllegalArgumentException("Mode classique non établi");
        if ((!profile.electric && (electricFrequency != 0 || voltage != 0))
                || (!profile.movement && (movementFrequency != 0 || movementIntensity != 0)))
            throw new IllegalArgumentException("Canal non établi pour ce modèle");
        if (profile.family == DeviceProfile.Family.SHORT)
            return new byte[] {(byte) 0xaa, 1, u8(movementFrequency), u8(movementIntensity),
                    u8(electricFrequency), u8(voltage)};
        return fixed(6, movementFrequency, movementIntensity, electricFrequency, voltage);
    }

    public static byte[] berserk(DeviceProfile p, boolean enabled) {
        requireModes(p);
        if (p.family == DeviceProfile.Family.SHORT)
            return new byte[] {(byte) 0xaa, 2, (byte) (enabled ? 1 : 0)};
        return fixed(8, enabled ? 1 : 0);
    }

    public static byte[] random(DeviceProfile p, boolean enabled, int min, int max) {
        requireModes(p);
        u8(min); u8(max);
        if (min > max) throw new IllegalArgumentException("Minimum supérieur au maximum");
        if (p.family == DeviceProfile.Family.SHORT)
            return new byte[] {(byte) 0xaa, 3, (byte) (enabled ? 1 : 0), u8(min), u8(max)};
        return fixed(9, enabled ? 1 : 0, min, max);
    }

    private static void requireModes(DeviceProfile p) {
        if (!p.movement && !p.electric)
            throw new IllegalArgumentException("Mode non établi");
    }

    public static byte[] spin(DeviceProfile p, int intensity, int voltage, int direction) {
        if (p != DeviceProfile.A1005) throw new IllegalArgumentException("Rotation : modèle AA-A1005");
        return new byte[] {(byte) 0xaa, 4, u8(intensity), u8(voltage), u8(direction)};
    }

    public static byte[] light(DeviceProfile p, boolean on) {
        if (!p.light) throw new IllegalArgumentException("Lumière non établie");
        return fixed(14, on ? 1 : 0);
    }

    public static byte[] requestToken(long unixSeconds) throws GeneralSecurityException {
        if (unixSeconds < 0 || unixSeconds > 0xffffffffL)
            throw new IllegalArgumentException("Timestamp hors 32 bits");
        byte[] plain = new byte[16]; plain[1] = (byte) 0xe1; plain[2] = 1;
        for (int i = 0; i < 4; i++) plain[i + 4] = (byte) (unixSeconds >>> (24 - 8 * i));
        return encrypt(plain);
    }

    public static byte[] aesCommand(byte[] token, int channel, int frequency, int voltage)
            throws GeneralSecurityException {
        if (token == null || token.length != 4) throw new IllegalArgumentException("Jeton BLE absent");
        if (channel != 1 && channel != 2) throw new IllegalArgumentException("Canal : 1 ou 2");
        byte[] plain = new byte[16]; plain[1] = (byte) 0xe1; plain[2] = 4;
        System.arraycopy(token, 0, plain, 3, 4);
        plain[7] = u8(channel); plain[8] = u8(frequency); plain[9] = u8(voltage);
        return encrypt(plain);
    }

    public static byte[] encrypt(byte[] plain) throws GeneralSecurityException { return crypt(plain, Cipher.ENCRYPT_MODE); }
    public static byte[] decrypt(byte[] data) throws GeneralSecurityException { return crypt(data, Cipher.DECRYPT_MODE); }
    private static byte[] crypt(byte[] data, int mode) throws GeneralSecurityException {
        if (data == null || data.length != 16) throw new IllegalArgumentException("Bloc AES attendu : 16 octets");
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(mode, new SecretKeySpec(AES_KEY, "AES"));
        return cipher.doFinal(data);
    }

    /** Returns only fields known from the APK; all other bytes remain visible in the log. */
    public static Reply decode(DeviceProfile p, byte[] raw, boolean batteryRequested)
            throws GeneralSecurityException {
        if (raw == null) return new Reply(null, null, null);
        byte[] plain = raw;
        byte[] token = null;
        Integer battery = null;
        if (p.family == DeviceProfile.Family.AES) {
            if (raw.length != 16) return new Reply(null, null, null);
            plain = decrypt(raw);
            if (plain[2] == 1) token = Arrays.copyOfRange(plain, 4, 8);
            if (batteryRequested && plain[2] == 4 && plain[3] == 0) battery = percent(plain[15]);
        } else if (p.family == DeviceProfile.Family.OLD) {
            if (raw.length >= 3 && raw[1] == 3) battery = percent(raw[2]);
        } else if (p.family == DeviceProfile.Family.SHORT) {
            // This family has no response opcode check in the reference client.
            if (batteryRequested && raw.length >= 3) battery = percent(raw[2]);
        } else if (raw.length >= 4 && raw[2] == 5) battery = percent(raw[3]);
        return new Reply(battery, token, p.family == DeviceProfile.Family.AES ? plain : null);
    }

    private static Integer percent(byte value) { int v = value & 255; return v <= 100 ? v : null; }
    public static String hex(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (byte b : data) { if (s.length() > 0) s.append(' '); s.append(String.format(Locale.ROOT, "%02X", b & 255)); }
        return s.toString();
    }

    public static final class Reply {
        public final Integer battery;
        public final byte[] token, plaintext;
        Reply(Integer battery, byte[] token, byte[] plaintext) {
            this.battery = battery; this.token = token; this.plaintext = plaintext;
        }
    }
}
