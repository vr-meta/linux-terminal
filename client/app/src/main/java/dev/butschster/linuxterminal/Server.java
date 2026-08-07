package dev.butschster.linuxterminal;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A machine to open shells on, either found on this network or typed in by hand.
 *
 * <p>Both kinds are kept because they answer different needs. Discovery covers the
 * machine on the desk, which changes address whenever the router feels like it. A typed
 * address covers a server somewhere else — which no broadcast will ever reach, and which
 * is the case that makes this a client-server product rather than a gadget.
 */
public class Server {

    public String name;
    public String host;
    public int port;

    public String user = "";
    public String os = "";
    public String cwd = "";

    /** True when this one answered a probe just now, rather than being remembered. */
    public boolean discovered;

    public Server(String name, String host, int port) {
        this.name = name == null || name.isEmpty() ? host : name;
        this.host = host;
        this.port = port;
    }

    public String address() {
        return host + ":" + port;
    }

    /** One line under the name: who and what, when the server said so. */
    public String detail() {
        StringBuilder text = new StringBuilder(address());
        if (!user.isEmpty()) text.append("   ").append(user);
        if (!os.isEmpty()) text.append("   ").append(os);
        return text.toString();
    }

    // ------------------------------------------------------------------- storage

    private static final String PREFS = "servers";
    private static final String KEY = "saved";

    /**
     * Saved servers, in the order they were added.
     *
     * <p>SharedPreferences rather than a file or a database: this is a handful of
     * addresses, and anything heavier would be a dependency bought for nothing.
     */
    public static List<Server> saved(Context context) {
        List<Server> servers = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                Server server = new Server(entry.optString("name"), entry.optString("host"),
                        entry.optInt("port", 9103));
                servers.add(server);
            }
        } catch (Exception ignored) {
        }
        return servers;
    }

    public static void save(Context context, List<Server> servers) {
        JSONArray array = new JSONArray();
        for (Server server : servers) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("name", server.name);
                entry.put("host", server.host);
                entry.put("port", server.port);
                array.put(entry);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, array.toString()).apply();
    }

    public static void remember(Context context, Server server) {
        List<Server> servers = saved(context);
        for (Server known : servers) {
            if (known.host.equals(server.host) && known.port == server.port) {
                known.name = server.name;
                save(context, servers);
                return;
            }
        }
        servers.add(server);
        save(context, servers);
    }

    public static void forget(Context context, Server server) {
        List<Server> servers = saved(context);
        servers.removeIf(known -> known.host.equals(server.host) && known.port == server.port);
        save(context, servers);
    }
}
