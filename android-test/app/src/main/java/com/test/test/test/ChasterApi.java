package com.test.test.test;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small synchronous REST client. Call it only from ChasterWorker's background thread. */
public final class ChasterApi {
    private static final String BASE = "https://api.chaster.app";
    private final String bearer;

    public ChasterApi(String bearer) {
        if (bearer == null || bearer.trim().isEmpty()) throw new IllegalArgumentException("Jeton Chaster absent");
        this.bearer = bearer.trim();
    }

    public JSONArray activeLocks() throws Exception {
        Object result = request("GET", "/locks?status=active", null);
        if (!(result instanceof JSONArray)) throw new ApiException("Réponse invalide de Chaster");
        return (JSONArray) result;
    }

    public ChasterLock lock(String id) throws Exception {
        Object result = request("GET", "/locks/" + segment(id), null);
        if (!(result instanceof JSONObject)) throw new ApiException("Détail du verrou invalide");
        return ChasterLock.fromJson((JSONObject) result);
    }

    public void unlock(String id) throws Exception {
        request("POST", "/locks/" + segment(id) + "/unlock", null);
    }

    public ChasterLock createLock(long minSeconds, long maxSeconds, String combination) throws Exception {
        JSONObject code = new JSONObject(); code.put("code", combination);
        Object codeResult = request("POST", "/combinations/code", code);
        String combinationId = findString(codeResult, "combinationId", "_id", "id");
        if (combinationId == null) throw new ApiException("Chaster n'a pas renvoyé l'identifiant de combinaison");

        JSONObject body = new JSONObject();
        body.put("lockType", "chastity");
        body.put("minDuration", minSeconds);
        body.put("maxDuration", maxSeconds);
        body.put("maxLimitDuration", JSONObject.NULL);
        body.put("minLimitDuration", JSONObject.NULL);
        body.put("displayRemainingTime", true);
        body.put("limitLockTime", false);
        body.put("combinationId", combinationId);
        body.put("extensions", new JSONArray());
        body.put("allowSessionOffer", true);
        body.put("isTestLock", false);
        body.put("hideTimeLogs", false);
        Object result = request("POST", "/locks", body);
        String lockId = findString(result, "lockId", "_id", "id");
        if (lockId != null) return lock(lockId);
        if (result instanceof JSONObject) return ChasterLock.fromJson((JSONObject) result);
        JSONArray locks = activeLocks();
        ChasterLock created = ChasterLock.firstActive(locks);
        if (created == null) throw new ApiException("Verrou créé, mais introuvable dans la liste active");
        return created;
    }

    public void offerTo(String lockId, String username) throws Exception {
        JSONObject body = new JSONObject(); body.put("keyholder", username);
        request("POST", "/session-offer/lock/" + segment(lockId), body);
    }

    public ChasterLock refresh(String lockId) throws Exception { return lock(lockId); }

    private Object request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Authorization", "Bearer " + bearer);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = stream == null ? "" : read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) throw new ApiException(message(status, response));
        if (response.trim().isEmpty()) return null;
        String trimmed = response.trim();
        try { return trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed); }
        catch (JSONException e) { throw new ApiException("Réponse JSON Chaster invalide"); }
    }

    private static String message(int status, String body) {
        try {
            JSONObject error = new JSONObject(body);
            String detail = error.optString("message", "");
            if (!detail.isEmpty()) return "Chaster HTTP " + status + " : " + detail;
        } catch (JSONException ignored) { }
        return "Chaster HTTP " + status;
    }

    private static String read(InputStream stream) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String segment(String value) { return value.replace("%", "%25").replace("/", "%2F"); }
    private static String findString(Object value, String... keys) {
        if (!(value instanceof JSONObject)) return null;
        JSONObject json = (JSONObject) value;
        for (String key : keys) { String found = json.optString(key, ""); if (!found.isEmpty()) return found; }
        return null;
    }

    public static final class ApiException extends Exception {
        ApiException(String message) { super(message); }
    }
}
