package com.test.test.test;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground console focused on proven lock and battery functions. */
public final class MainActivity extends Activity implements BleClient.Listener {
    private BleClient ble;
    private BluetoothAdapter adapter;
    private DeviceProfile profile;
    private BluetoothDevice selected;
    private byte[] token;
    private String chasterToken, pendingUnlockLockId;
    private byte[] pendingUnlockCommand;
    private boolean openCheckPending;
    private ChasterLock activeLock;
    private TokenVault tokenVault;
    private ExecutorService chasterWorker;
    private boolean batteryRequested, connectionPending;
    private boolean scanPending, autoConnectSearching;
    private SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoScanTimeout = this::onAutoScanTimeout;
    private final Runnable autoBatteryRead = this::readBatteryIfConnected;
    private final Runnable chasterTicker = () -> { if (activeLock != null) showChasterLock(activeLock); };
    private TextView status, connectedStatus, selection, connectedSelection, battery, journal;
    private TextView chasterStatus;
    private Button connectButton, disconnectButton, scanButton, stopScanButton;
    private Button chasterRefresh, createLockButton, offerButton;
    private EditText keyholderInput;
    private LinearLayout disconnectedPanel, connectedPanel, devices, commands, debugPanel, chasterPanel;
    private final Map<String, Button> deviceButtons = new LinkedHashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root);
        setContentView(scroll);

        chasterPanel = section(root, "Chaster");
        chasterStatus = text("Connexion Chaster en cours…"); chasterPanel.addView(chasterStatus);
        chasterRefresh = button(chasterPanel, "Actualiser Chaster", this::refreshChaster);
        createLockButton = button(chasterPanel, "Créer un verrou Chaster", this::showCreateLockDialog);
        createLockButton.setVisibility(View.GONE);
        keyholderInput = new EditText(this); keyholderInput.setSingleLine(true);
        keyholderInput.setHint("Username du keyholder"); chasterPanel.addView(keyholderInput);
        offerButton = button(chasterPanel, "Offrir la session", this::offerSession);
        offerButton.setVisibility(View.GONE);

        LinearLayout boxPanel = section(root, "Boîte à clés");
        disconnectedPanel = column(boxPanel);
        status = text("Déconnecté"); disconnectedPanel.addView(status);
        selection = text("Aucune serrure sélectionnée"); disconnectedPanel.addView(selection);

        scanButton = button(disconnectedPanel, "Rechercher les serrures", () -> requestScan(false));
        stopScanButton = button(disconnectedPanel, "Arrêter la recherche", () -> {
            autoConnectSearching = false; main.removeCallbacks(autoScanTimeout); ble.stopScan();
        });
        stopScanButton.setEnabled(false);
        devices = column(disconnectedPanel);

        connectButton = button(disconnectedPanel, "Se connecter", () -> {
            if (selected != null && profile != null && prepareBluetooth()) {
                connectionPending = true; updateConnectionButtons(); ble.connectProfile(selected, profile);
            }
        });
        connectButton.setVisibility(View.GONE);

        connectedPanel = column(boxPanel); connectedPanel.setVisibility(View.GONE);
        connectedStatus = text("Connecté"); connectedPanel.addView(connectedStatus);
        connectedSelection = text(""); connectedPanel.addView(connectedSelection);
        battery = text("Batterie : en attente"); connectedPanel.addView(battery);
        disconnectButton = button(connectedPanel, "Déconnecter", () -> {
            connectionPending = false; ble.disconnect(); updateConnectionButtons();
        });
        commands = column(connectedPanel);

        Button logToggle = button(root, "Afficher le journal technique", () -> { });
        debugPanel = column(root); debugPanel.setVisibility(View.GONE);
        logToggle.setOnClickListener(v -> {
            boolean show = debugPanel.getVisibility() != View.VISIBLE;
            debugPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            logToggle.setText(show ? "Masquer le journal technique" : "Afficher le journal technique");
        });
        button(debugPanel, "Copier le journal", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Bluetooth", journal.getText()));
            Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show();
        });
        button(debugPanel, "Effacer le journal", () -> journal.setText(""));
        journal = text(""); journal.setTextIsSelectable(true); debugPanel.addView(journal);

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        ble = new BleClient(this, adapter, this);
        if (adapter == null) log("Ce téléphone ne fournit pas d'adaptateur Bluetooth.");
        preferences = getSharedPreferences("remembered_lock", MODE_PRIVATE);
        keyholderInput.setText(preferences.getString("last_keyholder", ""));
        tokenVault = new TokenVault(this);
        chasterWorker = Executors.newSingleThreadExecutor();
        loadChasterToken();
        if (!preferences.contains("address")) {
            SharedPreferences previousPreferences = getPreferences(MODE_PRIVATE);
            if (previousPreferences.contains("address")) {
                preferences.edit()
                        .putString("address", previousPreferences.getString("address", ""))
                        .putString("name", previousPreferences.getString("name", ""))
                        .commit();
            }
        }
        if (preferences.contains("address")) {
            status.setText("Recherche de la dernière serrure connectée…");
            log("Serrure mémorisée : " + preferences.getString("name", "inconnue")
                    + " · " + preferences.getString("address", "adresse absente"));
            requestScan(true);
        }
    }

    private void loadChasterToken() {
        chasterStatus.setText("Chargement du jeton Chaster…");
        chasterWorker.execute(() -> {
            try {
                String loaded = tokenVault.loadOrImport();
                runOnUiThread(() -> {
                    chasterToken = loaded;
                    if (loaded == null) { chasterStatus.setText("Jeton absent : vérifiez chaster.jeton puis relancez installtest.sh."); return; }
                    refreshChaster();
                });
            } catch (Exception e) {
                runOnUiThread(() -> chasterStatus.setText("Jeton indisponible : " + safeMessage(e)));
            }
        });
    }

    private void refreshChaster() {
        if (chasterToken == null) { chasterStatus.setText("Jeton Chaster absent."); return; }
        chasterRefresh.setEnabled(false); chasterStatus.setText("Lecture de la session Chaster…");
        chasterWorker.execute(() -> {
            try {
                ChasterLock found = ChasterLock.firstActive(new ChasterApi(chasterToken).activeLocks());
                runOnUiThread(() -> showChasterLock(found));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    chasterStatus.setText("Chaster indisponible · " + safeMessage(e) + " · ouverture normale refusée");
                    chasterRefresh.setEnabled(true); createLockButton.setVisibility(View.GONE);
                });
            }
        });
    }

    private void showChasterLock(ChasterLock lock) {
        main.removeCallbacks(chasterTicker);
        activeLock = lock; chasterRefresh.setEnabled(true);
        if (lock == null) {
            chasterStatus.setText("Aucun verrou Chaster actif.");
            createLockButton.setVisibility(View.VISIBLE); offerButton.setVisibility(View.GONE);
            return;
        }
        createLockButton.setVisibility(View.GONE);
        offerButton.setVisibility(lock.allowOffer ? View.VISIBLE : View.GONE);
        String state;
        if ("unlocked".equals(lock.status)) state = "Déverrouillable · Chaster indique que le verrou est terminé.";
        else if (lock.ready && !lock.frozen) state = "Déverrouillable · Chaster l'autorise.";
        else if (!lock.canViewTime || lock.endDate.isEmpty()) state = "Verrouillé · durée restante masquée par Chaster.";
        else state = "Verrouillé · fin prévue " + formatRemaining(lock.endDate) + ".";
        if (lock.frozen) state = "Verrouillé · session gelée.";
        chasterStatus.setText(lock.title + "\n" + state);
        if (!keyholderInput.hasFocus() && lock.keyholder != null && !lock.keyholder.isEmpty()) keyholderInput.setText(lock.keyholder);
        if ("locked".equals(lock.status) && lock.canViewTime && !lock.endDate.isEmpty())
            main.postDelayed(chasterTicker, 60_000);
    }

    private void showCreateLockDialog() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        EditText min = field("Durée minimale (jours)");
        EditText max = field("Durée maximale (jours)");
        EditText code = field("Code de combinaison à conserver pour la boîte");
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String boxName = profile != null ? profile.bleName : preferences.getString("name", "");
        code.setText(boxName);
        code.setSelection(code.length());
        form.addView(min); form.addView(max); form.addView(code);
        new AlertDialog.Builder(this).setTitle("Créer un verrou Chaster")
                .setMessage("Le verrou Chaster et le verrouillage de la boîte sont indépendants.")
                .setView(form).setNegativeButton("Annuler", null)
                .setPositiveButton("Créer", (dialog, which) -> {
                    try {
                        long minSeconds = Math.multiplyExact(Long.parseLong(min.getText().toString().trim()), 86_400L);
                        long maxSeconds = Math.multiplyExact(Long.parseLong(max.getText().toString().trim()), 86_400L);
                        String combination = code.getText().toString().trim();
                        if (minSeconds < 0 || maxSeconds <= 0 || minSeconds > maxSeconds || combination.isEmpty())
                            throw new IllegalArgumentException("Durées invalides ou code manquant.");
                        confirmCreateLock(minSeconds / 86_400L, maxSeconds / 86_400L,
                                minSeconds, maxSeconds, combination);
                    } catch (Exception e) { Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show(); }
                }).show();
    }

    private void confirmCreateLock(long minDays, long maxDays, long minSeconds, long maxSeconds, String combination) {
        String duration = minDays == maxDays
                ? formatDays(minDays)
                : formatDays(minDays) + " à " + formatDays(maxDays);
        new AlertDialog.Builder(this).setTitle("Confirmer la durée")
                .setMessage("Chaster choisira au hasard la durée initiale dans cette plage :\n" + duration
                        + "\n\nVérifie bien les jours avant de créer le verrou.")
                .setNegativeButton("Modifier", null)
                .setPositiveButton("Créer le verrou", (dialog, which) -> createLock(minSeconds, maxSeconds, combination))
                .show();
    }

    private static String formatDays(long days) {
        String count = java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE).format(days);
        if (days < 365) return count + " jours";
        java.text.NumberFormat years = java.text.NumberFormat.getNumberInstance(java.util.Locale.FRANCE);
        years.setMaximumFractionDigits(1);
        return count + " jours (≈ " + years.format(days / 365.2425) + " ans)";
    }

    private EditText field(String hint) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private void createLock(long minSeconds, long maxSeconds, String combination) {
        chasterStatus.setText("Création du verrou Chaster…"); createLockButton.setEnabled(false);
        chasterWorker.execute(() -> {
            try {
                ChasterLock created = new ChasterApi(chasterToken).createLock(minSeconds, maxSeconds, combination);
                runOnUiThread(() -> {
                    createLockButton.setEnabled(true); showChasterLock(created);
                    Toast.makeText(this, "Verrou Chaster créé. Conservez le code de combinaison.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { createLockButton.setEnabled(true); chasterStatus.setText("Création impossible : " + safeMessage(e)); });
            }
        });
    }

    private void offerSession() {
        if (activeLock == null) { chasterStatus.setText("Aucun verrou à offrir."); return; }
        String username = keyholderInput.getText().toString().trim();
        if (username.isEmpty()) {
            if (!activeLock.offerToken.isEmpty()) shareOffer(activeLock.offerToken);
            else chasterStatus.setText("Saisissez un username pour créer une offre Chaster.");
            return;
        }
        String lockId = activeLock.id;
        chasterWorker.execute(() -> {
            try {
                ChasterApi api = new ChasterApi(chasterToken);
                api.offerTo(lockId, username);
                ChasterLock updated = api.refresh(lockId);
                preferences.edit().putString("last_keyholder", username).apply();
                runOnUiThread(() -> {
                    showChasterLock(updated);
                    if (!updated.offerToken.isEmpty()) shareOffer(updated.offerToken);
                    else chasterStatus.append("\nOffre envoyée à " + username + "; Chaster n'a pas renvoyé de lien partageable.");
                });
            } catch (Exception e) { runOnUiThread(() -> chasterStatus.setText("Offre refusée par Chaster : " + safeMessage(e))); }
        });
    }

    private void shareOffer(String offerToken) {
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "https://chaster.app/keyholding-request/" + android.net.Uri.encode(offerToken));
        startActivity(Intent.createChooser(share, "Partager l'offre Chaster"));
    }

    private void requestGatedOpen() {
        if (!ble.isReady() || profile == null || openCheckPending) return;
        if (chasterToken == null) { chasterStatus.setText("Jeton Chaster absent · ouverture refusée."); return; }
        openCheckPending = true;
        chasterStatus.setText("Vérification Chaster avant ouverture…");
        chasterWorker.execute(() -> {
            try {
                ChasterApi api = new ChasterApi(chasterToken);
                ChasterLock fresh = activeLock == null ? null : api.refresh(activeLock.id);
                if (fresh == null || !fresh.permitsOpening()) {
                    runOnUiThread(() -> {
                        openCheckPending = false;
                        showChasterLock(fresh);
                        chasterStatus.append("\nOuverture refusée : Chaster ne l'autorise pas actuellement.");
                    });
                    return;
                }
                runOnUiThread(() -> {
                    showChasterLock(fresh);
                    if (!ble.isReady()) { openCheckPending = false; chasterStatus.append("\nConnexion Bluetooth perdue : ouverture refusée."); return; }
                    pendingUnlockLockId = "locked".equals(fresh.status) ? fresh.id : null;
                    byte[] open = Protocol.lock(profile, false);
                    pendingUnlockCommand = pendingUnlockLockId == null ? null : open.clone();
                    openCheckPending = pendingUnlockCommand != null;
                    if (!command(open)) { pendingUnlockLockId = null; pendingUnlockCommand = null; openCheckPending = false; }
                    chasterStatus.append("\nOrdre d'ouverture envoyé.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { openCheckPending = false; chasterStatus.setText("Vérification Chaster impossible : " + safeMessage(e) + " · ouverture refusée"); });
            }
        });
    }

    private void finishChasterUnlock(String lockId) {
        if (lockId == null || chasterToken == null) return;
        chasterWorker.execute(() -> {
            try {
                new ChasterApi(chasterToken).unlock(lockId);
                ChasterLock latest = new ChasterApi(chasterToken).refresh(lockId);
                runOnUiThread(() -> showChasterLock(latest));
            } catch (Exception e) {
                runOnUiThread(() -> chasterStatus.setText("BLE a accepté l'écriture, mais clôture Chaster à synchroniser : " + safeMessage(e)));
            }
        });
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage(); return message == null || message.isEmpty() ? "erreur inconnue" : message;
    }

    private static String formatRemaining(String endDate) {
        try {
            String prefix = endDate.length() >= 19 ? endDate.substring(0, 19) : endDate;
            java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long seconds = (parser.parse(prefix).getTime() - System.currentTimeMillis()) / 1000;
            if (seconds <= 0) return "échéance atteinte, attente de confirmation Chaster";
            long days = seconds / 86400; long hours = (seconds % 86400) / 3600; long mins = (seconds % 3600) / 60;
            return days + " j " + hours + " h " + mins + " min";
        } catch (Exception e) { return "date indisponible"; }
    }

    private void requestScan(boolean automatic) {
        scanPending = true;
        autoConnectSearching = automatic;
        if (!prepareBluetooth()) return;
        scanPending = false;
        ble.disconnect();
        devices.removeAllViews(); deviceButtons.clear();
        if (!automatic) {
            selected = null; profile = null; selection.setText("Aucune serrure sélectionnée");
            connectButton.setVisibility(View.GONE); commands.removeAllViews();
        }
        status.setText(automatic ? "Recherche de la dernière serrure connectée…" : "Recherche des serrures…");
        if (automatic) main.postDelayed(autoScanTimeout, 12_000);
        ble.startScan();
    }

    private void connectSelected() {
        if (selected != null && profile != null && prepareBluetooth()) {
            connectionPending = true; updateConnectionButtons(); ble.connectProfile(selected, profile);
        }
    }

    private void onAutoScanTimeout() {
        if (!autoConnectSearching) return;
        autoConnectSearching = false;
        ble.stopScan();
        status.setText("Serrure mémorisée introuvable. Vous pouvez lancer une recherche manuelle.");
    }

    private TextView text(String value) { TextView t = new TextView(this); t.setText(value); return t; }
    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout section = column(parent);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) section.getLayoutParams();
        params.width = LinearLayout.LayoutParams.MATCH_PARENT;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.bottomMargin = dp(16);
        section.setLayoutParams(params);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(247, 248, 250)); background.setCornerRadius(dp(14));
        section.setBackground(background); section.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView heading = text(title); heading.setTextSize(19); heading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.bottomMargin = dp(8); section.addView(heading, 0, headingParams);
        return section;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); parent.addView(c); return c;
    }
    private interface Action { void run() throws GeneralSecurityException; }
    private Button button(LinearLayout parent, String title, Action action) {
        Button b = new Button(this); b.setText(title); parent.addView(b);
        b.setOnClickListener(v -> {
            try { action.run(); }
            catch (IllegalArgumentException | GeneralSecurityException | SecurityException e) {
                log("Erreur : " + e.getMessage());
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        return b;
    }

    private boolean prepareBluetooth() {
        if (adapter == null) { log("Bluetooth indisponible"); return false; }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, 1); return false;
            }
            LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
            boolean enabled = location != null && (location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!enabled) {
                log("Android 6–11 exige le service de localisation pour scanner le BLE ; cette app ne lit pas la position.");
                startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), 3); return false;
            }
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2); return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (requestCode == 1 && granted && scanPending) requestScan(autoConnectSearching);
        else if (requestCode == 1) {
            scanPending = false; autoConnectSearching = false;
            log("Permission Bluetooth refusée ; autorisez-la puis relancez la recherche.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!scanPending) return;
        if (requestCode == 2 && resultCode == RESULT_OK) requestScan(autoConnectSearching);
        else if (requestCode == 3 && locationEnabled()) requestScan(autoConnectSearching);
        else {
            scanPending = false; autoConnectSearching = false;
            status.setText("Recherche annulée ; Bluetooth et permissions requis.");
        }
    }

    private boolean locationEnabled() {
        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        return location != null && (location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    @Override public void device(BluetoothDevice device, String name, int rssi) {
        DeviceProfile found = DeviceProfile.fromName(name);
        if (found == null || (!found.lock && !found.unlock)) return;
        String address = device.getAddress();
        if (autoConnectSearching && address.equals(preferences.getString("address", null))
                && name.equals(preferences.getString("name", null))) {
            main.removeCallbacks(autoScanTimeout);
            autoConnectSearching = false;
            ble.stopScan();
            profile = found; selected = device;
            selection.setText(name + " · appareil mémorisé");
            connectedSelection.setText(name + " · " + address);
            connectButton.setVisibility(View.VISIBLE); connectButton.setText("Se connecter à " + name);
            renderCommands();
            updateConnectionButtons();
            log("Serrure mémorisée retrouvée ; connexion automatique.");
            connectSelected();
            return;
        }
        Button b = deviceButtons.get(address);
        if (b == null) {
            b = button(devices, "", () -> {
                ble.stopScan(); profile = found; selected = device;
                status.setText("Serrure détectée");
                selection.setText(name + " · " + address);
                connectedSelection.setText(name + " · " + address);
                connectButton.setVisibility(View.VISIBLE); connectButton.setText("Se connecter à " + name);
                renderCommands();
                updateConnectionButtons();
                log("Sélection : " + name + " " + address);
            });
            deviceButtons.put(address, b);
        }
        b.setText(name + " · " + address + " · " + rssi + " dBm");
    }

    @Override public void state(String message, boolean ready) {
        status.setText(message);
        connectedStatus.setText(message);
        disconnectedPanel.setVisibility(ready ? View.GONE : View.VISIBLE);
        connectedPanel.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (message.startsWith("Connexion à") || message.startsWith("Découverte")
                || message.startsWith("Abonnement")) connectionPending = true;
        else if (ready || message.equals("Déconnecté") || message.startsWith("Erreur :")) connectionPending = false;
        if (ready && selected != null && profile != null) {
            boolean saved = preferences.edit().putString("address", selected.getAddress())
                    .putString("name", profile.bleName).commit();
            selection.setText(profile.bleName + " · mémorisée sur ce téléphone");
            connectedSelection.setText(profile.bleName + " · " + selected.getAddress());
            if (!saved) log("Échec d'enregistrement local de la serrure mémorisée.");
            battery.setText("Batterie : lecture automatique…");
            main.removeCallbacks(autoBatteryRead);
            main.post(autoBatteryRead);
        }
        if (!ready && (message.equals("Déconnecté") || message.startsWith("Erreur :"))) {
            if (openCheckPending) {
                openCheckPending = false; pendingUnlockLockId = null; pendingUnlockCommand = null;
                if (chasterStatus != null) chasterStatus.setText("Bluetooth déconnecté ; aucune clôture Chaster envoyée.");
            }
            main.removeCallbacks(autoBatteryRead);
            token = null; batteryRequested = false; battery.setText("Batterie : en attente de connexion");
        }
        enableTree(commands, ready);
        updateConnectionButtons();
        log(message);
    }

    private void updateConnectionButtons() {
        boolean ready = ble != null && ble.isReady();
        connectButton.setEnabled(selected != null && !ready && !connectionPending);
        connectButton.setVisibility(selected != null && !ready ? View.VISIBLE : View.GONE);
    }

    private void enableTree(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof LinearLayout) {
            LinearLayout group = (LinearLayout) v;
            for (int i = 0; i < group.getChildCount(); i++) enableTree(group.getChildAt(i), enabled);
        }
    }

    @Override public void scanning(boolean active) { scanButton.setEnabled(!active); stopScanButton.setEnabled(active); }
    @Override public void writeCompleted(byte[] value, boolean success) {
        if (pendingUnlockLockId == null || pendingUnlockCommand == null
                || !java.util.Arrays.equals(value, pendingUnlockCommand)) return;
        String lockId = pendingUnlockLockId; pendingUnlockLockId = null;
        pendingUnlockCommand = null; openCheckPending = false;
        if (success) finishChasterUnlock(lockId);
        else chasterStatus.setText("Écriture BLE échouée ; verrou Chaster conservé.");
    }

    @Override public void log(String message) {
        if (journal != null) journal.append(DateFormat.getTimeInstance().format(new Date()) + " " + message + "\n");
    }

    private void readBattery() throws GeneralSecurityException {
        if (profile == null) return;
        batteryRequested = true; battery.setText("Batterie : lecture en cours…");
        boolean accepted;
        if (profile.family == DeviceProfile.Family.AES && token == null) {
            log("Lecture batterie AES : demande préalable du jeton BLE.");
            accepted = ble.send(Protocol.requestToken(System.currentTimeMillis() / 1000));
        } else accepted = ble.send(Protocol.battery(profile, token));
        if (!accepted) { batteryRequested = false; battery.setText("Batterie : demande non envoyée"); }
    }

    private void readBatteryIfConnected() {
        if (!ble.isReady() || batteryRequested) return;
        try { readBattery(); }
        catch (GeneralSecurityException | IllegalArgumentException e) {
            battery.setText("Batterie : lecture impossible"); log(e.getMessage());
        }
    }

    @Override public void received(byte[] value) {
        if (profile == null) return;
        try {
            Protocol.Reply reply = Protocol.decode(profile, value, batteryRequested);
            if (reply.plaintext != null) log("RX clair AES " + Protocol.hex(reply.plaintext));
            if (reply.token != null) {
                token = reply.token; log("Jeton BLE reçu pour cette connexion.");
                if (batteryRequested) ble.send(Protocol.battery(profile, token));
            }
            if (reply.battery != null) {
                batteryRequested = false;
                battery.setText("Batterie : " + reply.battery + " %");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) { log("Notification non décodée : " + e.getMessage()); }
    }

    private boolean command(byte[] data) {
        batteryRequested = false;
        boolean sent = ble.send(data);
        if (!sent) Toast.makeText(this, "Commande non envoyée : vérifier la connexion", Toast.LENGTH_LONG).show();
        return sent;
    }

    private void renderCommands() {
        commands.removeAllViews();
        if (profile.unlock) button(commands, "Ouvrir · vérifier Chaster", this::requestGatedOpen);
        if (profile.lock) button(commands, "Fermer", () -> command(Protocol.lock(profile, true)));
        enableTree(commands, ble.isReady());
    }

    @Override protected void onStop() {
        // Keep an active GATT link when the user briefly switches apps or opens system settings.
        if (ble != null) ble.stopScan();
        super.onStop();
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(autoScanTimeout);
        main.removeCallbacks(autoBatteryRead);
        main.removeCallbacks(chasterTicker);
        if (ble != null) ble.close();
        if (chasterWorker != null) chasterWorker.shutdownNow();
        super.onDestroy();
    }
}
