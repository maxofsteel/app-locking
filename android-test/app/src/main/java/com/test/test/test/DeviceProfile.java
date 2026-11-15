package com.test.test.test;

/** Explicit device capabilities derived from Lockink 1.5.7, not guessed from GATT. */
public enum DeviceProfile {
    A1001("AA-A1001", Family.FIXED, true, true, true, false, false),
    A1002("AA-A1002", Family.OLD, true, false, false, false, false),
    A1003("AA-A1003", Family.FIXED, false, false, true, false, false),
    A1004("AA-A1004", Family.FIXED, false, false, true, true, false),
    A1005("AA-A1005", Family.SHORT, false, false, true, true, false),
    A1006("AA-A1006", Family.FIXED, false, false, true, true, false),
    A1007("AA-A1007", Family.FIXED, false, false, true, true, false),
    A1008("AA-A1008", Family.FIXED, false, false, false, true, true),
    A1009("AA-A1009", Family.FIXED, false, false, false, true, true),
    A1010("AA-A1010", Family.FIXED, false, false, false, true, false),
    A1011("AA-A1011", Family.FIXED, false, false, false, true, false),
    A1012("AA-A1012", Family.FIXED, true, true, true, false, false),
    A1013("AA_A1013", Family.AES, false, false, true, false, false),
    A1014("AA-A1014", Family.FIXED, false, false, true, true, false),
    A1015("AA-A1015", Family.FIXED, true, true, true, true, false),
    A1016("AA-A1016", Family.FIXED, false, false, false, true, true),
    A1017("AA-A1017", Family.FIXED, false, false, false, true, true);

    public enum Family { FIXED, OLD, SHORT, AES }
    public final String bleName;
    public final Family family;
    public final boolean unlock, lock, electric, movement, light;

    DeviceProfile(String name, Family family, boolean unlock, boolean lock,
                  boolean electric, boolean movement, boolean light) {
        this.bleName = name;
        this.family = family;
        this.unlock = unlock;
        this.lock = lock;
        this.electric = electric;
        this.movement = movement;
        this.light = light;
    }

    public static DeviceProfile fromName(String name) {
        for (DeviceProfile p : values()) if (p.bleName.equals(name)) return p;
        return null;
    }

    @Override public String toString() { return bleName; }
}
