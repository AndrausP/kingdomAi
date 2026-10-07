package com.kingdomsai.client;

import com.google.gson.JsonObject;

/** Estado do Manager no cliente (vindo do servidor). */
public final class ClientState {
    public static volatile boolean managerOn;
    public static volatile JsonObject snapshot;

    private ClientState() {}
}
