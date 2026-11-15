package com.example.magicv2raydeeplink;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Host-side test of Socks5Server (it has no Android dependency): run by `./build.sh --test` and by CI.
 * Plain Java with a tiny assert helper, no test framework. It never relies on 9.9.9.9 answering: the DNS
 * rewrite is checked from the server's own log, so it passes offline and on runners with internet.
 */
public class Socks5ServerTest {

    private static final int PORT = 15353;
    private static final List<String> LOG = new CopyOnWriteArrayList<>();
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    private static boolean logged(String needle) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            for (String l : LOG) if (l.contains(needle)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1");

        // a TCP echo target and a UDP echo target on loopback (not port 53: those are rewritten)
        final ServerSocket tcpEcho = new ServerSocket(0, 5, lo);
        new Thread(() -> {
            try (Socket s = tcpEcho.accept()) {
                s.getOutputStream().write(s.getInputStream().read());
            } catch (Exception ignored) {
            }
        }).start();
        final DatagramSocket udpEcho = new DatagramSocket(0, lo);
        new Thread(() -> {
            try {
                DatagramPacket p = new DatagramPacket(new byte[100], 100);
                udpEcho.receive(p);
                udpEcho.send(new DatagramPacket(new byte[]{9, 8}, 2, p.getAddress(), p.getPort()));
            } catch (Exception ignored) {
            }
        }).start();

        Socks5Server srv = new Socks5Server(PORT, LOG::add);
        srv.start();
        try {
            byte[] r = new byte[10];

            // --- TCP CONNECT, relayed untouched
            Socket s = new Socket("127.0.0.1", PORT);
            OutputStream o = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            o.write(new byte[]{5, 1, 0});
            byte[] m = new byte[2];
            in.readFully(m);
            check(m[0] == 5 && m[1] == 0, "greeting: SOCKS5, no authentication");
            int tp = tcpEcho.getLocalPort();
            o.write(new byte[]{5, 1, 0, 1, 127, 0, 0, 1, (byte) (tp >> 8), (byte) tp});
            in.readFully(r);
            check(r[1] == 0, "CONNECT to an ordinary port succeeds");
            o.write(42);
            check(in.read() == 42, "CONNECT relays bytes both ways");
            s.close();

            // --- TCP to port 53: rewritten (whatever the destination was)
            Socket d = new Socket("127.0.0.1", PORT);
            o = d.getOutputStream();
            in = new DataInputStream(d.getInputStream());
            o.write(new byte[]{5, 1, 0});
            in.readFully(m);
            o.write(new byte[]{5, 1, 0, 1, 8, 8, 8, 8, 0, 53});
            check(logged("DNS/tcp  8.8.8.8:53  ->  " + Socks5Server.DNS_TARGET + ":53"), "TCP 8.8.8.8:53 is sent to " + Socks5Server.DNS_TARGET);
            d.close();

            // --- UDP ASSOCIATE
            Socket u = new Socket("127.0.0.1", PORT);
            o = u.getOutputStream();
            in = new DataInputStream(u.getInputStream());
            o.write(new byte[]{5, 1, 0});
            in.readFully(m);
            o.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            in.readFully(r);
            check(r[1] == 0, "UDP ASSOCIATE succeeds");
            int relayPort = ((r[8] & 255) << 8) | (r[9] & 255);

            DatagramSocket cs = new DatagramSocket();
            cs.setSoTimeout(3000);
            int up = udpEcho.getLocalPort();
            byte[] pkt = {0, 0, 0, 1, 127, 0, 0, 1, (byte) (up >> 8), (byte) up, 7};
            cs.send(new DatagramPacket(pkt, pkt.length, lo, relayPort));
            DatagramPacket rp = new DatagramPacket(new byte[100], 100);
            cs.receive(rp);
            byte[] got = Arrays.copyOf(rp.getData(), rp.getLength());
            byte[] want = {0, 0, 0, 1, 127, 0, 0, 1, (byte) (up >> 8), (byte) up, 9, 8};
            check(Arrays.equals(got, want), "UDP reply carries the ORIGINAL destination as its source");

            byte[] q = {0, 0, 0, 1, 1, 1, 1, 1, 0, 53, 1, 2, 3};
            cs.send(new DatagramPacket(q, q.length, lo, relayPort));
            check(logged("DNS/udp  1.1.1.1:53  ->  " + Socks5Server.DNS_TARGET + ":53"), "UDP 1.1.1.1:53 is sent to " + Socks5Server.DNS_TARGET);

            // a domain-name destination on port 53 is rewritten too
            byte[] dom = {0, 0, 0, 3, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0, 53, 4};
            cs.send(new DatagramPacket(dom, dom.length, lo, relayPort));
            check(logged("DNS/udp  example:53  ->  " + Socks5Server.DNS_TARGET + ":53"), "UDP to a domain name on port 53 is rewritten");
            u.close();
            cs.close();

            // --- an unsupported command is refused, not hung
            Socket b = new Socket("127.0.0.1", PORT);
            b.setSoTimeout(3000);
            o = b.getOutputStream();
            in = new DataInputStream(b.getInputStream());
            o.write(new byte[]{5, 1, 0});
            in.readFully(m);
            o.write(new byte[]{5, 2, 0, 1, 127, 0, 0, 1, 0, 80});     // BIND
            in.readFully(r);
            check(r[1] == 7, "BIND is answered with 'command not supported'");
            b.close();
        } finally {
            srv.stop();
        }
        System.out.println(failures == 0 ? "all passed" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
