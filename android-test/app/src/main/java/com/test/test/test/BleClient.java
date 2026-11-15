package com.test.test.test;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import java.util.ArrayDeque;
import java.util.UUID;

/** Android's GATT API, one operation in flight, all mutable state on the main thread. */
@SuppressWarnings("deprecation")
public final class BleClient {
    private static final UUID SERVICE = UUID.fromString("00008ac0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("00008ac1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("00008ac2-0000-1000-8000-00805f9b34fb");
    private static final UUID AES_SERVICE = UUID.fromString("8653000a-43e6-47b7-9cb0-5fc21d4ae340");
    private static final UUID AES_WRITE = UUID.fromString("8653000c-43e6-47b7-9cb0-5fc21d4ae340");
    private static final UUID AES_NOTIFY = UUID.fromString("8653000b-43e6-47b7-9cb0-5fc21d4ae340");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void device(BluetoothDevice device, String name, int rssi);
        void state(String state, boolean ready);
        void scanning(boolean active);
        void log(String text);
        void received(byte[] value);
        void writeCompleted(byte[] value, boolean success);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private int scanGeneration;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic, notifyCharacteristic;
    private boolean ready, writing;
    private int writeType;

    public BleClient(Context context, BluetoothAdapter adapter, Listener listener) {
        this.context = context.getApplicationContext(); this.adapter = adapter; this.listener = listener;
    }

    public boolean isReady() { return ready; }

    public void startScan() {
        stopScan();
        try {
            if (adapter == null || !adapter.isEnabled()) { listener.log("Activer le Bluetooth"); return; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { listener.log("Scanner BLE indisponible"); return; }
            final int generation = ++scanGeneration;
            scan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> {
                        if (generation != scanGeneration) return;
                        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                        try {
                            if (name == null) name = result.getDevice().getName();
                            if (DeviceProfile.fromName(name) != null)
                                listener.device(result.getDevice(), name, result.getRssi());
                        } catch (SecurityException e) { fail("Permission Bluetooth retirée"); }
                    });
                }
                @Override public void onScanFailed(int error) {
                    main.post(() -> { if (generation == scanGeneration) {
                        stopScan(); listener.log("Échec scan Android : " + error);
                    } });
                }
            };
            scanner.startScan(scan);
            listener.scanning(true);
            listener.log("Recherche des 17 noms BLE reconnus ; arrêter le scan après sélection.");
        } catch (SecurityException | IllegalStateException e) { stopScan(); listener.log("Scan : " + e.getMessage()); }
    }

    public void stopScan() {
        ++scanGeneration;
        try { if (scanner != null && scan != null) scanner.stopScan(scan); }
        catch (SecurityException | IllegalStateException e) { listener.log("Arrêt scan : " + e.getMessage()); }
        scan = null; scanner = null;
        listener.scanning(false);
    }

    public void connect(BluetoothDevice device, DeviceProfile profile) {
        stopScan(); disconnect();
        listener.state("Connexion à " + profile.bleName + "…", false);
        try {
            gatt = Build.VERSION.SDK_INT >= 23
                    ? device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    : device.connectGatt(context, false, callback);
            if (gatt == null) fail("Android n'a pas créé la connexion GATT");
        } catch (SecurityException | IllegalArgumentException e) { fail("Connexion : " + e.getMessage()); }
    }

    public void disconnect() {
        ready = false; writing = false; writes.clear();
        writeCharacteristic = null; notifyCharacteristic = null;
        BluetoothGatt previous = gatt; gatt = null;
        if (previous != null) {
            try { previous.disconnect(); } catch (SecurityException e) { listener.log("Permission de déconnexion retirée"); }
            try { previous.close(); } catch (SecurityException e) { listener.log("Permission de fermeture retirée"); }
        }
        listener.state("Déconnecté", false);
    }

    public void close() { stopScan(); disconnect(); main.removeCallbacksAndMessages(null); }
    private void fail(String text) {
        listener.log(text);
        disconnect();
        listener.state("Erreur : " + text, false);
    }

    public boolean send(byte[] value) {
        if (!ready || gatt == null) { listener.log("Connexion GATT non prête"); return false; }
        writes.add(value.clone()); pump(); return ready;
    }

    private void pump() {
        if (writing || !ready || writes.isEmpty()) return;
        byte[] value = writes.peek();
        writing = true;
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                accepted = gatt.writeCharacteristic(writeCharacteristic, value, writeType) == 0;
            } else {
                writeCharacteristic.setWriteType(writeType);
                writeCharacteristic.setValue(value);
                accepted = gatt.writeCharacteristic(writeCharacteristic);
            }
            if (!accepted) {
                writes.remove(); writing = false;
                listener.writeCompleted(value.clone(), false);
                fail("Écriture refusée par Android ; file annulée"); return;
            }
            listener.log("TX " + Protocol.hex(value));
        } catch (SecurityException | IllegalStateException e) { fail("Écriture : " + e.getMessage()); }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int state) {
            main.post(() -> {
                if (current != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    String reason = status == 147
                            ? "Délai GATT dépassé (147) : serrure hors portée ou plus disponible pour connexion"
                            : "Connexion GATT : statut " + status;
                    fail(reason + ", état " + state); return;
                }
                if (state == BluetoothProfile.STATE_DISCONNECTED) { fail("Connexion terminée par le périphérique"); return; }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    listener.state("Découverte des services…", false);
                    try {
                        // The reference app requests this connection priority after connectGatt.
                        current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        if (!current.discoverServices()) fail("Découverte refusée par Android");
                    }
                    catch (SecurityException e) { fail("Permission Bluetooth retirée"); }
                }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            main.post(() -> {
                if (current != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Découverte GATT : " + status); return; }
                try {
                    for (BluetoothGattService s : current.getServices()) {
                        listener.log("Service " + s.getUuid());
                        for (BluetoothGattCharacteristic c : s.getCharacteristics())
                            listener.log("  " + c.getUuid() + " propriétés=0x" + Integer.toHexString(c.getProperties()));
                    }
                    // Profile chosen from the advertised name, never by stale cached characteristics.
                    BluetoothGattService s = current.getService(expectedAes ? AES_SERVICE : SERVICE);
                    if (s == null) { fail("Service attendu absent pour ce modèle"); return; }
                    writeCharacteristic = s.getCharacteristic(expectedAes ? AES_WRITE : WRITE);
                    notifyCharacteristic = s.getCharacteristic(expectedAes ? AES_NOTIFY : NOTIFY);
                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        fail("Caractéristiques attendues absentes"); return;
                    }
                    int properties = writeCharacteristic.getProperties();
                    writeType = expectedAes ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    int required = expectedAes ? BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            : BluetoothGattCharacteristic.PROPERTY_WRITE;
                    if ((properties & required) == 0) { fail("Type d'écriture attendu non annoncé"); return; }
                    if ((notifyCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                        fail("Notifications non annoncées"); return;
                    }
                    BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD);
                    if (descriptor == null || !current.setCharacteristicNotification(notifyCharacteristic, true)) {
                        fail("Notifications indisponibles"); return;
                    }
                    listener.state("Abonnement aux notifications…", false);
                    boolean accepted;
                    if (Build.VERSION.SDK_INT >= 33)
                        accepted = current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
                    else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        accepted = current.writeDescriptor(descriptor);
                    }
                    if (!accepted) fail("Écriture CCCD refusée");
                } catch (SecurityException | IllegalStateException e) { fail("Services : " + e.getMessage()); }
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            main.post(() -> {
                if (current != gatt || !CCCD.equals(descriptor.getUuid())
                        || descriptor.getCharacteristic() != notifyCharacteristic) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Abonnement CCCD : " + status); return; }
                ready = true; listener.state("Connecté · notifications actives", true);
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (current != gatt || c != writeCharacteristic || !writing) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    byte[] failed = writes.poll(); writing = false;
                    if (failed != null) listener.writeCompleted(failed.clone(), false);
                    fail("Écriture GATT : " + status); return;
                }
                byte[] completed = writes.poll(); writing = false;
                if (completed != null) listener.writeCompleted(completed.clone(), true);
                listener.log("Écriture terminée côté Android (aucune confirmation mécanique).");
                pump();
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) deliver(current, c, c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            deliver(current, c, value);
        }
        private void deliver(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            if (value == null) return;
            byte[] snapshot = value.clone();
            main.post(() -> {
                if (current != gatt || c != notifyCharacteristic) return;
                listener.log("RX " + Protocol.hex(snapshot)); listener.received(snapshot);
            });
        }
    };

    private boolean expectedAes;
    public void connectProfile(BluetoothDevice device, DeviceProfile profile) {
        expectedAes = profile.family == DeviceProfile.Family.AES;
        connect(device, profile);
    }
}
