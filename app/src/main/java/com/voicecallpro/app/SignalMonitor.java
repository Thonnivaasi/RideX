package com.voicecallpro.app;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
public class SignalMonitor {
    public enum Quality { GOOD, FAIR, POOR, UNKNOWN }
    public interface Listener { void onQuality(Quality q, long pingMs); }
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    public void start(InetAddress peer, int port, Listener listener) {
        if (peer == null) return;
        running.set(true);
        thread = new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] ping = "PING".getBytes();
                    try (DatagramSocket s = new DatagramSocket()) {
                        s.setSoTimeout(2000);
                        long t0 = System.currentTimeMillis();
                        s.send(new DatagramPacket(ping, ping.length, peer, port));
                        byte[] buf = new byte[8];
                        s.receive(new DatagramPacket(buf, buf.length));
                        long rtt = System.currentTimeMillis() - t0;
                        Quality q = rtt < 100 ? Quality.GOOD : rtt < 300 ? Quality.FAIR : Quality.POOR;
                        listener.onQuality(q, rtt);
                    }
                } catch (Exception e) { listener.onQuality(Quality.UNKNOWN, -1); }
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }
    public void stop() { running.set(false); if (thread != null) thread.interrupt(); }
}
