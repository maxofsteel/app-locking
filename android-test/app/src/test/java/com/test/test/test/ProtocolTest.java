package com.test.test.test;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolTest {
    private static final byte[] TOKEN = {1, 2, 3, 4};
    @Test public void knownNamesAreExactAndExhaustive() {
        assertEquals(17, DeviceProfile.values().length);
        for (DeviceProfile p : DeviceProfile.values()) assertSame(p, DeviceProfile.fromName(p.bleName));
        assertSame(DeviceProfile.A1013, DeviceProfile.fromName("AA_A1013"));
        assertNull(DeviceProfile.fromName("AA-A1013"));
        assertNull(DeviceProfile.fromName(null));
    }
    @Test public void batteryQueriesMatchEachFamily() throws Exception {
        assertEquals("AA 09 05 00 00 00 00 00 00 FF", Protocol.hex(Protocol.battery(DeviceProfile.A1001, null)));
        assertEquals("AA 03", Protocol.hex(Protocol.battery(DeviceProfile.A1002, null)));
        assertEquals("AA 00", Protocol.hex(Protocol.battery(DeviceProfile.A1005, null)));
        assertEquals("A3 CD B8 13 2B 5A 80 7B 86 1C 49 AE A6 29 CC C7",
                Protocol.hex(Protocol.battery(DeviceProfile.A1013, TOKEN)));
    }
    @Test public void lockCommandsDoNotInventOldClosure() {
        assertEquals("AA 09 02 00 00 00 00 00 00 FF", Protocol.hex(Protocol.lock(DeviceProfile.A1001, false)));
        assertEquals("AA 09 03 00 00 00 00 00 00 FF", Protocol.hex(Protocol.lock(DeviceProfile.A1001, true)));
        assertEquals("AA 01 01 00", Protocol.hex(Protocol.lock(DeviceProfile.A1002, false)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.lock(DeviceProfile.A1002, true));
        assertThrows(IllegalArgumentException.class, () -> Protocol.lock(DeviceProfile.A1013, false));
    }
    @Test public void timerIsBigEndianAndRequiresSupportedDevice() {
        assertEquals("AA 09 04 01 02 01 01 07 02 FF", Protocol.hex(Protocol.timer(DeviceProfile.A1001, 258, 7)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.timer(DeviceProfile.A1001, 65536, 0));
        assertThrows(IllegalArgumentException.class, () -> Protocol.timer(DeviceProfile.A1002, 1, 0));
        assertEquals("AA 09 04 00 01 01 01 00 02 FF", Protocol.hex(Protocol.timer(DeviceProfile.A1012, 1, 0)));
    }
    @Test public void electricAndMotorFieldsKeepTheirWireOrder() {
        assertEquals("AA 09 01 02 01 03 04 00 00 FF", Protocol.hex(Protocol.electric(DeviceProfile.A1001, true, 2, 3, 4)));
        assertEquals("AA 09 06 01 02 03 04 00 00 FF", Protocol.hex(Protocol.classic(DeviceProfile.A1004, 1, 2, 3, 4)));
        assertEquals("AA 01 01 02 03 04", Protocol.hex(Protocol.classic(DeviceProfile.A1005, 1, 2, 3, 4)));
        assertEquals("AA 04 01 02 03", Protocol.hex(Protocol.spin(DeviceProfile.A1005, 1, 2, 3)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.classic(DeviceProfile.A1008, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> Protocol.classic(DeviceProfile.A1012, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> Protocol.electric(DeviceProfile.A1012, false, 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Protocol.electric(DeviceProfile.A1005, false, 1, 2, 3));
    }
    @Test public void modesAndLightHaveDistinctShortAndFixedOpcodes() {
        assertEquals("AA 09 08 01 00 00 00 00 00 FF", Protocol.hex(Protocol.berserk(DeviceProfile.A1004, true)));
        assertEquals("AA 02 00", Protocol.hex(Protocol.berserk(DeviceProfile.A1005, false)));
        assertEquals("AA 03 01 0A 14", Protocol.hex(Protocol.random(DeviceProfile.A1005, true, 10, 20)));
        assertEquals("AA 09 0E 01 00 00 00 00 00 FF", Protocol.hex(Protocol.light(DeviceProfile.A1008, true)));
        assertThrows(IllegalArgumentException.class, () -> Protocol.light(DeviceProfile.A1004, true));
        assertThrows(IllegalArgumentException.class, () -> Protocol.random(DeviceProfile.A1004, true, 20, 10));
    }
    @Test public void invalidOctetsAreRejectedInsteadOfTruncated() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.fixed(1, -1));
        assertThrows(IllegalArgumentException.class, () -> Protocol.fixed(256));
        assertThrows(IllegalArgumentException.class, () -> Protocol.fixed(1, 256));
        assertThrows(IllegalArgumentException.class, () -> Protocol.fixed(1, 1, 2, 3, 4, 5, 6, 7));
    }
    @Test public void aesMatchesIndependentStaticAnalysisVectors() throws Exception {
        assertEquals("A2 7D FF 7D 65 12 5D EF AA 8D 61 3F 15 F8 10 4F", Protocol.hex(Protocol.requestToken(0x65000000L)));
        byte[] plain = Protocol.decrypt(Protocol.aesCommand(TOKEN, 2, 9, 10));
        assertArrayEquals(new byte[] {0, (byte) 0xe1, 4, 1, 2, 3, 4, 2, 9, 10, 0, 0, 0, 0, 0, 0}, plain);
        assertThrows(IllegalArgumentException.class, () -> Protocol.aesCommand(null, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Protocol.aesCommand(TOKEN, 3, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Protocol.requestToken(0x100000000L));
    }
    @Test public void truncatedAndUnknownRepliesDoNotDecodeOrCrash() throws Exception {
        for (DeviceProfile p : DeviceProfile.values()) {
            for (int length = 0; length < 3; length++) assertNull(Protocol.decode(p, new byte[length], true).battery);
            assertNull(Protocol.decode(p, null, true).battery);
        }
        assertNull(Protocol.decode(DeviceProfile.A1001, new byte[] {0, 0, 5}, true).battery);
        assertNull(Protocol.decode(DeviceProfile.A1001, new byte[] {0, 0, 2, 50}, true).battery);
        assertNull(Protocol.decode(DeviceProfile.A1001, new byte[] {0, 0, 5, (byte) 255}, true).battery);
    }
    @Test public void batteryParsingRequiresKnownOpcodeOrPendingRequest() throws Exception {
        assertEquals(Integer.valueOf(75), Protocol.decode(DeviceProfile.A1001, new byte[] {0, 0, 5, 75}, false).battery);
        assertEquals(Integer.valueOf(42), Protocol.decode(DeviceProfile.A1002, new byte[] {0, 3, 42}, true).battery);
        assertNull(Protocol.decode(DeviceProfile.A1005, new byte[] {0, 0, 60}, false).battery);
        assertEquals(Integer.valueOf(60), Protocol.decode(DeviceProfile.A1005, new byte[] {0, 0, 60}, true).battery);
    }
    @Test public void aesTokenAndBatteryUseDifferentOffsets() throws Exception {
        byte[] tokenReply = new byte[16]; tokenReply[2] = 1;
        System.arraycopy(TOKEN, 0, tokenReply, 4, 4);
        assertArrayEquals(TOKEN, Protocol.decode(DeviceProfile.A1013, Protocol.encrypt(tokenReply), true).token);
        byte[] batteryReply = new byte[16]; batteryReply[2] = 4; batteryReply[15] = 87;
        byte[] encoded = Protocol.encrypt(batteryReply);
        assertEquals(Integer.valueOf(87), Protocol.decode(DeviceProfile.A1013, encoded, true).battery);
        assertNull(Protocol.decode(DeviceProfile.A1013, encoded, false).battery);
        batteryReply[3] = 1;
        assertNull(Protocol.decode(DeviceProfile.A1013, Protocol.encrypt(batteryReply), true).battery);
    }
}
