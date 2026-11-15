package com.example.magicv2raydeeplink;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small SOCKS5 server (no authentication, loopback only) with two commands:
 *
 *   CONNECT        TCP, relayed straight out of this device
 *   UDP ASSOCIATE  UDP, relayed straight out of this device
 *
 * Whatever the destination, anything going to port 53 (DNS over UDP or TCP) is sent to
 * {@link #DNS_TARGET} instead. UDP replies are handed back as if they came from the address the client
 * asked for, so the client never notices the rewrite.
 *
 * This process is kept out of the VPN by Magic V2Ray (exclude=<our package>), so the sockets opened here
 * use the real network.
 */
final class Socks5Server {

    static final String DNS_TARGET = "9.9.9.9";
    private static final int MAX_UDP_FLOWS = 256;
    private static final int UDP_IDLE_MS = 60_000;

    interface Log {
        void line(String s);
    }

    private final int port;
    private final Log log;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;

    Socks5Server(int port, Log log) {
        this.port = port;
        this.log = log;
    }

    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        server = s;
        running = true;
        pool.execute(this::acceptLoop);
    }

    void stop() {
        running = false;
        closeQuietly(server);
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = server.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (running) log.line("accept failed: " + e.getMessage());
                return;
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    /** A SOCKS5 address as it arrived (kept raw so UDP replies can echo it back unchanged). */
    private static final class Addr {
        int atyp;
        byte[] raw;       // 4 / 16 address bytes, or the domain name bytes
        int port;

        String host() throws IOException {
            if (atyp == 3) return new String(raw, StandardCharsets.UTF_8);
            return InetAddress.getByAddress(raw).getHostAddress();
        }

        boolean isDns() {
            return port == 53;
        }

        byte[] encode() {
            int n = atyp == 3 ? 1 + raw.length : raw.length;
            byte[] b = new byte[1 + n + 2];
            b[0] = (byte) atyp;
            int o = 1;
            if (atyp == 3) b[o++] = (byte) raw.length;
            System.arraycopy(raw, 0, b, o, raw.length);
            o += raw.length;
            b[o] = (byte) (port >> 8);
            b[o + 1] = (byte) port;
            return b;
        }
    }

    private static Addr readAddr(DataInputStream in) throws IOException {
        Addr a = new Addr();
        a.atyp = in.readUnsignedByte();
        switch (a.atyp) {
            case 1: a.raw = new byte[4]; break;
            case 4: a.raw = new byte[16]; break;
            case 3: a.raw = new byte[in.readUnsignedByte()]; break;
            default: throw new IOException("bad address type " + a.atyp);
        }
        in.readFully(a.raw);
        a.port = in.readUnsignedShort();
        return a;
    }

    private static void reply(OutputStream out, int code, int bindPort) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 127, 0, 0, 1, (byte) (bindPort >> 8), (byte) bindPort});
        out.flush();
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(30_000);
            DataInputStream in = new DataInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();

            if (in.readUnsignedByte() != 5) return;
            in.readFully(new byte[in.readUnsignedByte()]);          // offered methods: we only do "no auth"
            out.write(new byte[]{5, 0});
            out.flush();

            if (in.readUnsignedByte() != 5) return;
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte();                                  // reserved
            Addr dst = readAddr(in);
            c.setSoTimeout(0);

            if (cmd == 1) connect(c, in, out, dst);
            else if (cmd == 3) udpAssociate(c, in, out);
            else reply(out, 7, 0);                                  // command not supported
        } catch (IOException ignored) {
        } finally {
            closeQuietly(c);
        }
    }

    // ----------------------------------------------------------------------- TCP

    private void connect(Socket client, DataInputStream in, OutputStream out, Addr dst) throws IOException {
        String host = dst.host();
        if (dst.isDns()) {
            log.line("DNS/tcp  " + host + ":53  ->  " + DNS_TARGET + ":53");
            host = DNS_TARGET;
        }
        final Socket remote = new Socket();
        try {
            remote.connect(new InetSocketAddress(host, dst.port), 10_000);
        } catch (IOException e) {
            closeQuietly(remote);
            reply(out, 5, 0);                                       // connection refused
            return;
        }
        reply(out, 0, 0);
        pool.execute(() -> {
            try {
                copy(remote.getInputStream(), client.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(remote);
            }
        });
        try {
            copy(in, remote.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
            closeQuietly(remote);
        }
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = from.read(buf)) >= 0) {
            to.write(buf, 0, n);
            to.flush();
        }
    }

    // ----------------------------------------------------------------------- UDP

    private void udpAssociate(final Socket control, DataInputStream in, OutputStream out) throws IOException {
        final DatagramSocket relay = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        // One outgoing socket per destination the client asked for, so a reply can be attributed to the
        // original destination even though every DNS server is rewritten to the same one.
        final Map<String, DatagramSocket> flows = new ConcurrentHashMap<>();
        reply(out, 0, relay.getLocalPort());

        // The association lives as long as the TCP control connection.
        pool.execute(() -> {
            try {
                while (in.read() >= 0) { /* ignore */ }
            } catch (IOException ignored) {
            } finally {
                closeQuietly(relay);
            }
        });

        InetAddress clientAddr = null;
        int clientPort = -1;
        byte[] buf = new byte[65535];
        try {
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                relay.receive(p);
                if (clientAddr == null) {
                    clientAddr = p.getAddress();
                    clientPort = p.getPort();
                } else if (!p.getAddress().equals(clientAddr) || p.getPort() != clientPort) {
                    continue;                                       // not our client
                }
                forward(relay, flows, p, clientAddr, clientPort);
            }
        } catch (IOException ignored) {                             // relay closed: the association ended
        } finally {
            closeQuietly(relay);
            for (DatagramSocket s : flows.values()) closeQuietly(s);
            closeQuietly(control);
        }
    }

    private void forward(DatagramSocket relay, Map<String, DatagramSocket> flows, DatagramPacket p,
                         InetAddress clientAddr, int clientPort) {
        try {
            byte[] d = p.getData();
            int len = p.getLength();
            if (len < 10 || d[2] != 0) return;                      // too short, or fragmented (unsupported)
            DataInputStream hdr = new DataInputStream(new java.io.ByteArrayInputStream(d, 3, len - 3));
            final Addr dst = readAddr(hdr);
            int offset = len - hdr.available();

            String host = dst.host();
            final String key = host + ":" + dst.port;
            if (dst.isDns()) {
                log.line("DNS/udp  " + host + ":53  ->  " + DNS_TARGET + ":53");
                host = DNS_TARGET;
            }
            DatagramSocket flow = flows.get(key);
            if (flow == null) {
                if (flows.size() >= MAX_UDP_FLOWS) return;
                flow = new DatagramSocket();
                flow.setSoTimeout(UDP_IDLE_MS);
                flows.put(key, flow);
                startReplyPump(relay, flows, key, flow, dst, clientAddr, clientPort);
            }
            flow.send(new DatagramPacket(d, offset, len - offset, InetAddress.getByName(host), dst.port));
        } catch (IOException ignored) {                             // bad packet or unreachable: UDP, so drop it
        }
    }

    /** Wraps what comes back in a SOCKS5 UDP header carrying the ORIGINAL destination. */
    private void startReplyPump(final DatagramSocket relay, final Map<String, DatagramSocket> flows,
                                final String key, final DatagramSocket flow, final Addr original,
                                final InetAddress clientAddr, final int clientPort) {
        pool.execute(() -> {
            byte[] head = original.encode();
            byte[] buf = new byte[65535];
            try {
                while (true) {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    flow.receive(r);
                    byte[] out = new byte[3 + head.length + r.getLength()];
                    System.arraycopy(head, 0, out, 3, head.length);
                    System.arraycopy(buf, 0, out, 3 + head.length, r.getLength());
                    relay.send(new DatagramPacket(out, out.length, clientAddr, clientPort));
                }
            } catch (SocketTimeoutException idle) {
                // nothing for a while: drop the flow
            } catch (IOException ignored) {
            } finally {
                flows.remove(key);
                closeQuietly(flow);
            }
        });
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(DatagramSocket s) {
        if (s != null) s.close();
    }
}
