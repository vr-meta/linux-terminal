package dev.butschster.linuxterminal;

import android.util.Base64;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Opening a socket to a machine that has to prove it is the one we paired with.
 *
 * <p>The server signs its own certificate, which on its own proves nothing at
 * all — anybody can make one. What makes it an identity is that pairing wrote
 * the fingerprint down and every connection afterwards checks it. So the trust
 * manager here accepts <em>no</em> certificate authority and only ever asks one
 * question: is this the exact certificate we saw when the user paired?
 *
 * <p>A machine presenting a different one is not that machine. It might be a
 * reinstalled server, and it might be somebody in the middle; the client cannot
 * tell those apart and does not try, so it refuses and says which fingerprint it
 * expected. Pairing again is a deliberate act, which is the point.
 */
public final class Pinned {

    private Pinned() {
    }

    /** Thrown when the machine answered but is not the one that was paired. */
    public static class WrongMachine extends IOException {
        public final String expected;
        public final String offered;

        WrongMachine(String expected, String offered) {
            super("this is not the machine that was paired\nexpected " + expected
                    + "\ngot      " + offered);
            this.expected = expected;
            this.offered = offered;
        }
    }

    /**
     * Connects and completes the handshake, so a wrong certificate fails here
     * rather than on the first read, where it would look like a network problem.
     */
    public static Socket connect(String host, int port, String fingerprint, int timeoutMs)
            throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        plain.setTcpNoDelay(true);

        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[]{new PinTrust(fingerprint)}, null);
            SSLSocketFactory factory = context.getSocketFactory();
            SSLSocket secured = (SSLSocket) factory.createSocket(plain, host, port, true);
            secured.setUseClientMode(true);
            secured.startHandshake();
            return secured;
        } catch (IOException failure) {
            closeQuietly(plain);
            throw failure;
        } catch (Exception failure) {
            closeQuietly(plain);
            throw new IOException(failure.getMessage(), failure);
        }
    }

    /** SHA-256 of the certificate as hex pairs — the same string the server prints. */
    public static String fingerprintOf(X509Certificate certificate) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sum = digest.digest(certificate.getEncoded());
        StringBuilder out = new StringBuilder(sum.length * 3);
        for (byte b : sum) {
            if (out.length() > 0) out.append(':');
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Trusts exactly one certificate and nothing else — not a CA, not the system
     * store, not a name that happens to match.
     */
    private static final class PinTrust implements X509TrustManager {

        private final String expected;

        PinTrust(String expected) {
            this.expected = expected == null ? "" : expected.toLowerCase(Locale.ROOT);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) {
                throw new java.security.cert.CertificateException("the server sent no certificate");
            }
            try {
                String offered = fingerprintOf(chain[0]);
                if (!offered.equals(expected)) {
                    throw new java.security.cert.CertificateException(
                            new WrongMachine(expected, offered));
                }
            } catch (java.security.cert.CertificateException rethrow) {
                throw rethrow;
            } catch (Exception failure) {
                throw new java.security.cert.CertificateException(failure);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Never called: this side is the client.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Fetches the certificate a machine is presenting, for the one moment that
     * has to happen before anything is pinned: showing the user what they are
     * about to trust. Nothing else in the app connects without a pin.
     */
    public static String peekFingerprint(String host, int port, int timeoutMs) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, new TrustManager[]{new AcceptOnce()}, null);
            SSLSocket secured = (SSLSocket) context.getSocketFactory()
                    .createSocket(plain, host, port, true);
            secured.setUseClientMode(true);
            secured.startHandshake();
            Certificate[] chain = secured.getSession().getPeerCertificates();
            String fingerprint = fingerprintOf((X509Certificate) chain[0]);
            secured.close();
            return fingerprint;
        } catch (Exception failure) {
            closeQuietly(plain);
            throw new IOException("could not read the certificate: " + failure.getMessage());
        }
    }

    /** Used only while showing a fingerprint for a person to approve. */
    private static final class AcceptOnce implements X509TrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
