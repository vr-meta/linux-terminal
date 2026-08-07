package dev.butschster.linuxterminal;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Trading six digits for the secret the machines actually use.
 *
 * <p>A person types a short code once. This sends it over the pinned, encrypted
 * connection and gets back a long token, which is what every session afterwards
 * presents. The short code is never stored and never used again — it exists only
 * for the seconds it takes to do this, which is what makes six digits enough.
 *
 * <p>Failing here is not a network error and must not read like one. A code that
 * was mistyped, expired, or already used all come back the same way: the server
 * closes without answering, and it does so deliberately, so that a reply cannot
 * be used to tell those apart.
 */
public final class Pairing {

    private Pairing() {
    }

    /** How long to wait for the answer. The server replies immediately or not at all. */
    private static final int TIMEOUT_MS = 6000;

    public static class Refused extends IOException {
        Refused() {
            super("the code was not accepted — it may be wrong, used, or expired");
        }
    }

    /**
     * @return the long token, to be stored against this server
     */
    public static String exchange(String host, int port, String fingerprint, String code)
            throws IOException {
        Socket socket = Pinned.connect(host, port, fingerprint, TIMEOUT_MS);
        try {
            socket.setSoTimeout(TIMEOUT_MS);

            byte[] payload = new JSONObject()
                    .put("pair", code).toString().getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(HostSession.MSG_AUTH);
            out.write(new byte[]{
                    (byte) (payload.length >> 24), (byte) (payload.length >> 16),
                    (byte) (payload.length >> 8), (byte) payload.length});
            out.write(payload);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int kind = in.readUnsignedByte();
            int length = in.readInt();
            if (kind != HostSession.MSG_PAIRED || length <= 0 || length > 4096) {
                throw new Refused();
            }
            byte[] body = new byte[length];
            in.readFully(body);

            String token = new JSONObject(new String(body, StandardCharsets.UTF_8))
                    .optString("token", "");
            if (token.isEmpty()) throw new Refused();
            return token;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new Refused();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
