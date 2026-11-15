package com.test.test.test;

import org.json.JSONArray;
import org.json.JSONObject;

/** The wearer fields needed to present and safely gate a local BLE opening. */
public final class ChasterLock {
    public final String id, title, status, endDate, offerToken, keyholder;
    public final boolean ready, frozen, canViewTime, allowOffer;

    private ChasterLock(JSONObject json) {
        id = json.optString("_id", "");
        title = json.optString("title", "Verrou Chaster");
        status = json.optString("status", "unknown");
        endDate = optionalString(json, "endDate");
        offerToken = optionalString(json, "offerToken");
        ready = json.optBoolean("isReadyToUnlock", false);
        frozen = json.optBoolean("isFrozen", false);
        canViewTime = json.optBoolean("isAllowedToViewTime", false)
                && json.optBoolean("displayRemainingTime", false);
        allowOffer = json.optBoolean("allowSessionOffer", false);
        JSONObject holder = json.optJSONObject("keyholder");
        keyholder = holder == null ? "" : optionalString(holder, "username");
    }

    public boolean permitsOpening() {
        return "unlocked".equals(status) || ("locked".equals(status) && ready && !frozen);
    }

    public static ChasterLock firstActive(JSONArray locks) {
        for (int i = 0; i < locks.length(); i++) {
            JSONObject item = locks.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("status", "");
            if ("locked".equals(status) || "unlocked".equals(status)) return new ChasterLock(item);
        }
        return null;
    }

    public static ChasterLock fromJson(JSONObject json) { return new ChasterLock(json); }

    private static String optionalString(JSONObject json, String key) {
        Object value = json.opt(key);
        return value == null || value == JSONObject.NULL ? "" : value.toString();
    }
}
