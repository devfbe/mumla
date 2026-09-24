/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net;

import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Creates the TLS sockets of one connection. The server is trusted if the system trusts its chain
 * and the leaf names {@code peerHost}, or if its leaf matches a certificate the user pinned for
 * {@code peerHost} in the trust store; see {@link ServerTrustManager}.
 */
public class HumlaSSLSocketFactory {
    private static final String TAG = HumlaSSLSocketFactory.class.getName();

    private final SSLContext mContext;
    private final ServerTrustManager mTrustManager;
    private final String mPeerHost;

    /**
     * @param peerHost The host name the user entered: certificates are verified against it, it is
     *                 sent as SNI and it selects the pins, even when an SRV record points the
     *                 connection at another host.
     */
    public HumlaSSLSocketFactory(KeyStore keystore, String keystorePassword, String trustStorePath, String trustStorePassword, String trustStoreFormat, String peerHost) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException, NoSuchProviderException, IOException, CertificateException {
        this(keystore, keystorePassword,
                trustStorePath != null ? loadTrustStore(trustStorePath, trustStorePassword, trustStoreFormat) : null,
                systemTrustManager(), peerHost);
    }

    /** Package-private for tests, which supply their own system trust. */
    HumlaSSLSocketFactory(KeyStore keystore, String keystorePassword, KeyStore trustStore, X509TrustManager systemTrust, String peerHost) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
        mContext = SSLContext.getInstance("TLS");
        mPeerHost = peerHost;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keystore, keystorePassword != null ? keystorePassword.toCharArray() : new char[0]);

        Set<String> pins = trustStore != null ? CertificatePins.forHost(trustStore, peerHost) : Collections.emptySet();
        Log.i(TAG, pins.isEmpty() ? "No pinned certificate for this host" : "Using pinned certificate(s) for this host");
        mTrustManager = new ServerTrustManager(systemTrust, peerHost, pins);

        mContext.init(kmf.getKeyManagers(), new TrustManager[] { mTrustManager }, null);
    }

    private static X509TrustManager systemTrustManager() throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }

    /**
     * Loads the trust store file at {@code path}, closing it on every path: this runs once per
     * connection attempt, reconnects included. Package-private for the test.
     */
    static KeyStore loadTrustStore(String path, String password, String format) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore trustStore = KeyStore.getInstance(format);
        try (FileInputStream fis = new FileInputStream(path)) {
            trustStore.load(fis, password.toCharArray());
        }
        return trustStore;
    }

    /**
     * Creates a new SSLSocket that runs through a SOCKS5 proxy to reach its destination. The proxy
     * resolves {@code host}.
     */
    public SSLSocket createTorSocket(String host, int port, String proxyHost, int proxyPort) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
        Socket socket = new Socket(proxy);
        socket.connect(InetSocketAddress.createUnresolved(host, port));
        return configure((SSLSocket) mContext.getSocketFactory().createSocket(socket, host, port, true));
    }

    public SSLSocket createSocket(String host, int port) throws IOException {
        return configure((SSLSocket) mContext.getSocketFactory().createSocket(InetAddress.getByName(host), port));
    }

    private SSLSocket configure(SSLSocket socket) {
        if (mPeerHost != null && !mPeerHost.isEmpty() && !HostnameMatcher.isIpLiteral(mPeerHost)) {
            try {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(Collections.singletonList(new SNIHostName(mPeerHost)));
                socket.setSSLParameters(parameters);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Not sending SNI for a host name SNI cannot carry", e);
            }
        }
        return socket;
    }

    /**
     * Gets the certificate chain of the remote host.
     * @return The remote server's certificate chain, or null if a connection has not reached handshake yet.
     */
    public X509Certificate[] getServerChain() {
        return mTrustManager.getServerChain();
    }

    /** Why the server certificate was rejected, or {@link TrustFailure#NONE}. */
    public TrustFailure getTrustFailure() {
        return mTrustManager.getFailure();
    }
}
