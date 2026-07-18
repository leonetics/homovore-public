package dev.leonetic.manager.account;

import com.google.gson.JsonObject;

public class Account {
    private final String name;
    private final String uuid;
    private final String accessToken;
    private final String authData;
    private final long addedAt;

    public Account(String name, String uuid, String accessToken, String authData, long addedAt) {
        this.name = name;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.authData = authData;
        this.addedAt = addedAt;
    }

    public String getName() { return name; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }
    public String getAuthData() { return authData; }
    public long getAddedAt() { return addedAt; }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        if (uuid != null) object.addProperty("uuid", uuid);
        if (accessToken != null && !accessToken.isEmpty()) object.addProperty("accessToken", TokenCrypto.encrypt(accessToken));
        if (authData != null && !authData.isEmpty()) object.addProperty("authData", TokenCrypto.encrypt(authData));
        object.addProperty("addedAt", addedAt);
        return object;
    }

    public static Account fromJson(JsonObject object) {
        if (object == null || !object.has("name")) return null;
        String name = object.get("name").getAsString();
        String uuid = object.has("uuid") && !object.get("uuid").isJsonNull() ? object.get("uuid").getAsString() : "";
        String accessToken = object.has("accessToken") && !object.get("accessToken").isJsonNull() ? TokenCrypto.decrypt(object.get("accessToken").getAsString()) : "";
        String authData = object.has("authData") && !object.get("authData").isJsonNull() ? TokenCrypto.decrypt(object.get("authData").getAsString()) : "";
        long addedAt = object.has("addedAt") ? object.get("addedAt").getAsLong() : System.currentTimeMillis();
        return new Account(name, uuid, accessToken, authData, addedAt);
    }
}
