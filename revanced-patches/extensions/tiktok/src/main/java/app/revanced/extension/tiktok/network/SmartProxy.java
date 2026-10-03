package app.revanced.extension.tiktok.network;

import android.util.Base64;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.tiktok.settings.Settings;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selective TTNet proxy router.
 *
 * TikTok's native TTNet/Cronet stack supports a global HTTP proxy, but that would also proxy
 * content traffic. To keep video/photo playback direct, TTNet is pointed at a loopback HTTP proxy
 * owned by the extension. This router only chains API/control hosts to the configured upstream
 * proxy; every other host is connected directly.
 *
 * No TLS interception is performed. HTTPS is forwarded with CONNECT tunnels only.
 */
@SuppressWarnings({"unused", "SameParameterValue"})
public final class SmartProxy {
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    private static final Object LOCK = new Object();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();

    private static final ThreadFactory DAEMON_THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "rv-smart-proxy-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    private static final ExecutorService WORKERS =
            Executors.newCachedThreadPool(DAEMON_THREAD_FACTORY);

    private static volatile ServerSocket serverSocket;
    private static volatile String activeConfigKey = "";
    private static volatile String lastAppliedRule = "";
    private static volatile int lastCronetEngineIdentity;

    private SmartProxy() {
    }

    /**
     * Called from a TTNet hook after/while Cronet is available. Safe to call repeatedly.
     */
    public static void apply(Object cronetClient) {
        try {
            if (cronetClient == null) {
                return;
            }

            if (!Settings.SMART_PROXY_ENABLED.get()) {
                synchronized (LOCK) {
                    int engineIdentity = System.identityHashCode(cronetClient);
                    if (serverSocket == null && lastAppliedRule.isEmpty() &&
                            lastCronetEngineIdentity == engineIdentity) {
                        return;
                    }
                    stopLocalProxyLocked();
                    applyTTNetRule(null);
                    lastAppliedRule = "";
                    lastCronetEngineIdentity = engineIdentity;
                }
                return;
            }

            Config config = Config.fromSettings();
            if (!config.isValid()) {
                Logger.printDebug(() -> "Smart proxy enabled but upstream host/port is invalid");
                return;
            }

            synchronized (LOCK) {
                String configKey = config.key();
                if (serverSocket == null || serverSocket.isClosed() || !configKey.equals(activeConfigKey)) {
                    stopLocalProxyLocked();
                    startLocalProxyLocked(config);
                    activeConfigKey = configKey;
                    lastAppliedRule = "";
                }

                int engineIdentity = System.identityHashCode(cronetClient);
                String rule = "http=127.0.0.1:" + serverSocket.getLocalPort();
                if (!rule.equals(lastAppliedRule) || engineIdentity != lastCronetEngineIdentity) {
                    applyTTNetRule(rule);
                    lastAppliedRule = rule;
                    lastCronetEngineIdentity = engineIdentity;
                    Logger.printDebug(() -> "Smart proxy attached to TTNet via " + rule);
                }
            }
        } catch (Throwable throwable) {
            Logger.printException(() -> "Smart proxy apply failed", throwable);
        }
    }

    private static void applyTTNetRule(String rule) throws Exception {
        Class<?> ttNetInit = Class.forName("com.bytedance.ttnet.TTNetInit");
        Method setProxy = ttNetInit.getDeclaredMethod("setProxy", String.class);
        setProxy.setAccessible(true);
        setProxy.invoke(null, rule);
    }

    private static void startLocalProxyLocked(Config config) throws IOException {
        ServerSocket socket = new ServerSocket(
                0,
                64,
                InetAddress.getByName("127.0.0.1")
        );
        serverSocket = socket;

        Thread acceptThread = DAEMON_THREAD_FACTORY.newThread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(SOCKET_TIMEOUT_MS);
                    WORKERS.execute(() -> handleClient(client, config));
                } catch (IOException exception) {
                    if (!socket.isClosed()) {
                        Logger.printException(() -> "Smart proxy accept failed", exception);
                    }
                    break;
                }
            }
        });
        acceptThread.setName("rv-smart-proxy-accept");
        acceptThread.start();

        Logger.printDebug(() -> "Smart proxy loopback listening on 127.0.0.1:" + socket.getLocalPort());
    }

    private static void stopLocalProxyLocked() {
        ServerSocket socket = serverSocket;
        serverSocket = null;
        activeConfigKey = "";
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleClient(Socket client, Config config) {
        Socket remote = null;
        try {
            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            byte[] headerBytes = readHeader(clientIn);
            if (headerBytes.length == 0) {
                return;
            }

            HeaderBlock header = HeaderBlock.parse(headerBytes);
            if (header == null) {
                sendError(clientOut, 400, "Bad Request");
                return;
            }

            if ("CONNECT".equalsIgnoreCase(header.method)) {
                HostPort destination = HostPort.parse(header.target, 443);
                if (destination == null) {
                    sendError(clientOut, 400, "Bad CONNECT target");
                    return;
                }

                boolean useUpstream = shouldProxy(destination.host, config);
                remote = openDestination(destination, config, useUpstream);
                clientOut.write(("HTTP/1.1 200 Connection Established\r\n" +
                        "Proxy-Agent: ReVanced-SmartProxy\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                clientOut.flush();

                Logger.printDebug(() -> "Smart proxy " +
                        (useUpstream ? "PROXY " : "DIRECT ") + destination.host);

                tunnel(client, remote);
                remote = null;
                return;
            }

            HostPort destination = header.destinationForPlainHttp();
            if (destination == null) {
                sendError(clientOut, 400, "Missing Host");
                return;
            }

            boolean useUpstream = shouldProxy(destination.host, config);
            if (useUpstream && config.type == ProxyType.HTTP) {
                try {
                    remote = connectSocket(config.host, config.port);
                    byte[] forwarded = addProxyAuthorization(headerBytes, config);
                    remote.getOutputStream().write(forwarded);
                    remote.getOutputStream().flush();
                } catch (IOException upstreamFailure) {
                    closeQuietly(remote);
                    remote = null;
                    if (!config.fallbackDirect) {
                        throw upstreamFailure;
                    }
                    remote = connectSocket(destination.host, destination.port);
                    remote.getOutputStream().write(header.asOriginFormBytes());
                    remote.getOutputStream().flush();
                }
            } else {
                try {
                    remote = openDestination(destination, config, useUpstream);
                } catch (IOException upstreamFailure) {
                    if (!useUpstream || !config.fallbackDirect) throw upstreamFailure;
                    remote = connectSocket(destination.host, destination.port);
                }
                remote.getOutputStream().write(header.asOriginFormBytes());
                remote.getOutputStream().flush();
            }

            Logger.printDebug(() -> "Smart proxy " +
                    (useUpstream ? "PROXY " : "DIRECT ") + destination.host);
            tunnel(client, remote);
            remote = null;
        } catch (Throwable throwable) {
            Logger.printException(() -> "Smart proxy connection failed", throwable);
            try {
                sendError(client.getOutputStream(), 502, "Bad Gateway");
            } catch (Throwable ignored) {
            }
        } finally {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    private static Socket openDestination(HostPort destination, Config config, boolean useUpstream)
            throws IOException {
        if (!useUpstream) {
            return connectSocket(destination.host, destination.port);
        }

        try {
            if (config.type == ProxyType.SOCKS5) {
                return connectViaSocks5(destination, config);
            }
            return connectViaHttpProxy(destination, config);
        } catch (IOException upstreamFailure) {
            if (!config.fallbackDirect) {
                throw upstreamFailure;
            }
            Logger.printDebug(() -> "Smart proxy upstream failed; direct fallback for " +
                    destination.host);
            return connectSocket(destination.host, destination.port);
        }
    }

    private static Socket connectSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        return socket;
    }

    private static Socket connectViaHttpProxy(HostPort destination, Config config)
            throws IOException {
        Socket socket = connectSocket(config.host, config.port);
        OutputStream out = socket.getOutputStream();
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ")
                .append(destination.hostForAuthority())
                .append(':')
                .append(destination.port)
                .append(" HTTP/1.1\r\nHost: ")
                .append(destination.hostForAuthority())
                .append(':')
                .append(destination.port)
                .append("\r\nProxy-Connection: Keep-Alive\r\n");

        if (!config.username.isEmpty()) {
            request.append("Proxy-Authorization: Basic ")
                    .append(basicAuth(config.username, config.password))
                    .append("\r\n");
        }
        request.append("\r\n");

        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        byte[] response = readHeader(socket.getInputStream());
        String firstLine = firstLine(response);
        if (firstLine == null || !firstLine.matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d.*")) {
            closeQuietly(socket);
            throw new IOException("Upstream HTTP proxy rejected CONNECT: " + firstLine);
        }
        return socket;
    }

    private static Socket connectViaSocks5(HostPort destination, Config config)
            throws IOException {
        Socket socket = connectSocket(config.host, config.port);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        boolean withAuth = !config.username.isEmpty();
        if (withAuth) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();

        int version = readByte(in);
        int method = readByte(in);
        if (version != 0x05 || method == 0xFF) {
            throw new IOException("SOCKS5 authentication method rejected");
        }

        if (method == 0x02) {
            byte[] user = config.username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = config.password.getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) {
                throw new IOException("SOCKS5 credentials are too long");
            }
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(0x01);
            auth.write(user.length);
            auth.write(user);
            auth.write(pass.length);
            auth.write(pass);
            out.write(auth.toByteArray());
            out.flush();

            if (readByte(in) != 0x01 || readByte(in) != 0x00) {
                throw new IOException("SOCKS5 username/password authentication failed");
            }
        } else if (method != 0x00) {
            throw new IOException("Unsupported SOCKS5 authentication method: " + method);
        }

        byte[] host = destination.host.getBytes(StandardCharsets.UTF_8);
        if (host.length > 255) {
            throw new IOException("SOCKS5 destination hostname too long");
        }

        ByteArrayOutputStream connect = new ByteArrayOutputStream();
        connect.write(0x05);
        connect.write(0x01);
        connect.write(0x00);
        connect.write(0x03);
        connect.write(host.length);
        connect.write(host);
        connect.write((destination.port >>> 8) & 0xFF);
        connect.write(destination.port & 0xFF);
        out.write(connect.toByteArray());
        out.flush();

        if (readByte(in) != 0x05) {
            throw new IOException("Invalid SOCKS5 reply");
        }
        int reply = readByte(in);
        readByte(in); // reserved
        int atyp = readByte(in);
        if (reply != 0x00) {
            throw new IOException("SOCKS5 CONNECT failed with code " + reply);
        }

        if (atyp == 0x01) {
            readFully(in, 4);
        } else if (atyp == 0x03) {
            readFully(in, readByte(in));
        } else if (atyp == 0x04) {
            readFully(in, 16);
        } else {
            throw new IOException("Invalid SOCKS5 address type");
        }
        readFully(in, 2);
        return socket;
    }

    private static boolean shouldProxy(String host, Config config) {
        String normalized = host.toLowerCase(Locale.US);
        if (matchesCustomHosts(normalized, config.extraApiHosts)) {
            return true;
        }

        // Feed/search/account/control endpoints. Media hosts intentionally do NOT match this list.
        if (normalized.endsWith(".tiktokv.com")) {
            return startsWithAny(normalized,
                    "api", "hotapi", "search", "verification", "imapi", "passport");
        }
        if (normalized.endsWith(".musical.ly")) {
            return startsWithAny(normalized, "api", "search", "verification", "imapi");
        }
        if (normalized.endsWith(".tiktokapi.com")) {
            return true;
        }
        if (normalized.endsWith(".snssdk.com") || normalized.endsWith(".isnssdk.com")) {
            return true;
        }
        if (normalized.endsWith(".zijieapi.com")) {
            return true;
        }
        if (normalized.endsWith(".byteoversea.com")) {
            return startsWithAny(normalized,
                    "api", "mssdk", "mon", "log", "rtlog", "settings", "gecko", "dm", "sgali");
        }

        return false;
    }

    private static boolean startsWithAny(String host, String... prefixes) {
        String firstLabel = host;
        int dot = host.indexOf('.');
        if (dot > 0) {
            firstLabel = host.substring(0, dot);
        }
        for (String prefix : prefixes) {
            if (firstLabel.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean matchesCustomHosts(String host, String rules) {
        if (rules == null || rules.trim().isEmpty()) {
            return false;
        }
        String[] entries = rules.split("[,;\\s]+");
        for (String entry : entries) {
            String rule = entry.trim().toLowerCase(Locale.US);
            if (rule.isEmpty()) continue;
            if (rule.startsWith("*.")) rule = rule.substring(2);
            if (host.equals(rule) || host.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }

    private static void tunnel(Socket left, Socket right) throws IOException {
        InputStream leftIn = left.getInputStream();
        OutputStream leftOut = left.getOutputStream();
        InputStream rightIn = right.getInputStream();
        OutputStream rightOut = right.getOutputStream();

        WORKERS.execute(() -> {
            try {
                copy(leftIn, rightOut);
            } catch (IOException ignored) {
            } finally {
                try {
                    right.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        });

        try {
            copy(rightIn, leftOut);
        } finally {
            try {
                left.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < MAX_HEADER_BYTES) {
            int value = in.read();
            if (value == -1) break;
            buffer.write(value);

            if ((matched == 0 && value == '\r') ||
                    (matched == 1 && value == '\n') ||
                    (matched == 2 && value == '\r') ||
                    (matched == 3 && value == '\n')) {
                matched++;
                if (matched == 4) break;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }

        if (buffer.size() >= MAX_HEADER_BYTES) {
            throw new IOException("Proxy request headers too large");
        }
        return buffer.toByteArray();
    }

    private static String firstLine(byte[] header) {
        String text = new String(header, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n");
        if (end < 0) return null;
        return text.substring(0, end);
    }

    private static byte[] addProxyAuthorization(byte[] headerBytes, Config config) {
        if (config.username.isEmpty()) return headerBytes;
        String text = new String(headerBytes, StandardCharsets.ISO_8859_1);
        int insert = text.indexOf("\r\n");
        if (insert < 0) return headerBytes;

        String auth = "\r\nProxy-Authorization: Basic " +
                basicAuth(config.username, config.password);
        String result = text.substring(0, insert) + auth + text.substring(insert);
        return result.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return Base64.encodeToString(
                raw.getBytes(StandardCharsets.ISO_8859_1),
                Base64.NO_WRAP
        );
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return data;
    }

    private static void sendError(OutputStream out, int code, String message) throws IOException {
        String body = code + " " + message;
        String response = "HTTP/1.1 " + body + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + body.length() + "\r\n\r\n" +
                body;
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private enum ProxyType {
        HTTP,
        SOCKS5;

        static ProxyType parse(String value) {
            return value != null && value.toLowerCase(Locale.US).startsWith("socks")
                    ? SOCKS5
                    : HTTP;
        }
    }

    private static final class Config {
        final ProxyType type;
        final String host;
        final int port;
        final String username;
        final String password;
        final boolean fallbackDirect;
        final String extraApiHosts;

        Config(ProxyType type, String host, int port, String username, String password,
               boolean fallbackDirect, String extraApiHosts) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.fallbackDirect = fallbackDirect;
            this.extraApiHosts = extraApiHosts;
        }

        static Config fromSettings() {
            String host = safe(Settings.SMART_PROXY_HOST.get()).trim();
            int port;
            try {
                port = Integer.parseInt(safe(Settings.SMART_PROXY_PORT.get()).trim());
            } catch (NumberFormatException ignored) {
                port = -1;
            }

            return new Config(
                    ProxyType.parse(Settings.SMART_PROXY_TYPE.get()),
                    host,
                    port,
                    safe(Settings.SMART_PROXY_USERNAME.get()),
                    safe(Settings.SMART_PROXY_PASSWORD.get()),
                    Settings.SMART_PROXY_FALLBACK_DIRECT.get(),
                    safe(Settings.SMART_PROXY_EXTRA_API_HOSTS.get())
            );
        }

        boolean isValid() {
            return !host.isEmpty() && port > 0 && port <= 65535;
        }

        String key() {
            // Password is intentionally not included in logs; hashCode is enough to detect change.
            return type + "|" + host + "|" + port + "|" + username + "|" +
                    password.hashCode() + "|" + fallbackDirect + "|" + extraApiHosts;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort parse(String authority, int defaultPort) {
            if (authority == null || authority.isEmpty()) return null;
            try {
                if (authority.charAt(0) == '[') {
                    int end = authority.indexOf(']');
                    if (end < 0) return null;
                    String host = authority.substring(1, end);
                    int port = defaultPort;
                    if (end + 1 < authority.length() && authority.charAt(end + 1) == ':') {
                        port = Integer.parseInt(authority.substring(end + 2));
                    }
                    return new HostPort(host, port);
                }

                int colon = authority.lastIndexOf(':');
                if (colon > 0 && authority.indexOf(':') == colon) {
                    return new HostPort(
                            authority.substring(0, colon),
                            Integer.parseInt(authority.substring(colon + 1))
                    );
                }
                return new HostPort(authority, defaultPort);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        String hostForAuthority() {
            return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        }
    }

    private static final class HeaderBlock {
        final byte[] original;
        final String method;
        final String target;
        final String version;
        final String hostHeader;

        HeaderBlock(byte[] original, String method, String target, String version, String hostHeader) {
            this.original = original;
            this.method = method;
            this.target = target;
            this.version = version;
            this.hostHeader = hostHeader;
        }

        static HeaderBlock parse(byte[] data) {
            String text = new String(data, StandardCharsets.ISO_8859_1);
            String[] lines = text.split("\r\n");
            if (lines.length == 0) return null;
            String[] request = lines[0].split(" ", 3);
            if (request.length != 3) return null;

            String host = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) continue;
                if ("host".equalsIgnoreCase(lines[i].substring(0, colon).trim())) {
                    host = lines[i].substring(colon + 1).trim();
                    break;
                }
            }
            return new HeaderBlock(data, request[0], request[1], request[2], host);
        }

        HostPort destinationForPlainHttp() {
            try {
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    URI uri = URI.create(target);
                    int port = uri.getPort();
                    if (port < 0) {
                        port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
                    }
                    return new HostPort(uri.getHost(), port);
                }
            } catch (RuntimeException ignored) {
            }
            return HostPort.parse(hostHeader, 80);
        }

        byte[] asOriginFormBytes() {
            try {
                if (!target.startsWith("http://") && !target.startsWith("https://")) {
                    return original;
                }
                URI uri = URI.create(target);
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

                String text = new String(original, StandardCharsets.ISO_8859_1);
                int lineEnd = text.indexOf("\r\n");
                if (lineEnd < 0) return original;
                String first = method + " " + path + " " + version;
                return (first + text.substring(lineEnd))
                        .getBytes(StandardCharsets.ISO_8859_1);
            } catch (RuntimeException ignored) {
                return original;
            }
        }
    }
}
