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

    /**
     * What was agreed when this machine was paired: the token it wants back, and
     * the certificate it was known by.
     *
     * <p>The fingerprint is the identity. A self-signed certificate proves nothing
     * on its own — what makes it this machine rather than whoever answered first
     * is that we wrote it down once and refuse anything else afterwards. Empty
     * means never paired, and pairing is the only thing that fills it.
     */
    public String token = "";
    public String fingerprint = "";

    public boolean paired() {
        return !token.isEmpty() && !fingerprint.isEmpty();
    }

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
                server.user = entry.optString("user", "");
                server.os = entry.optString("os", "");
                server.cwd = entry.optString("cwd", "");
                server.token = entry.optString("token", "");
                server.fingerprint = entry.optString("fingerprint", "");
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
                entry.put("user", server.user);
                entry.put("os", server.os);
                entry.put("cwd", server.cwd);
                entry.put("token", server.token);
                entry.put("fingerprint", server.fingerprint);
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
                // Only ever filled in, never blanked: a rediscovery of a machine
                // carries no token, and letting that overwrite what pairing agreed
                // would silently unpair every server the moment it was seen again.
                if (!server.user.isEmpty()) known.user = server.user;
                if (!server.os.isEmpty()) known.os = server.os;
                if (!server.cwd.isEmpty()) known.cwd = server.cwd;
                if (!server.token.isEmpty()) known.token = server.token;
                if (!server.fingerprint.isEmpty()) known.fingerprint = server.fingerprint;
                save(context, servers);
                return;
            }
        }
        servers.add(server);
        save(context, servers);
    }

    /** What was agreed with this machine, if it was ever paired. */
    public static Server find(Context context, String host, int port) {
        for (Server known : saved(context)) {
            if (known.host.equals(host) && known.port == port) return known;
        }
        return null;
    }

    public static void forget(Context context, Server server) {
        List<Server> servers = saved(context);
        servers.removeIf(known -> known.host.equals(server.host) && known.port == server.port);
        save(context, servers);
    }
}
