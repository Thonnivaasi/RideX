package com.voicecallpro.app;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;
public class MusicStreamer {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private AudioTrack audioTrack;
    public void start() {
        if (running.get()) return;
        running.set(true);
        int minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBuf, 4096), AudioTrack.MODE_STREAM);
        audioTrack.play();
        thread = new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(MusicPlayer.MUSIC_PORT)) {
                sock.setSoTimeout(5000);
                byte[] buf = new byte[2048];
                while (running.get()) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        sock.receive(pkt);
                        if (audioTrack != null) audioTrack.write(pkt.getData(), 0, pkt.getLength());
                    } catch (java.net.SocketTimeoutException e) {}
                }
            } catch (Exception e) {}
        });
        thread.setDaemon(true); thread.start();
    }
    public void stop() {
        running.set(false);
        if (thread != null) thread.interrupt();
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch (Exception e) {}
    }
}
