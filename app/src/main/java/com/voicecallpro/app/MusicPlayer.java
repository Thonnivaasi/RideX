package com.voicecallpro.app;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
public class MusicPlayer {
    public static final int MUSIC_PORT = 50007;
    private final Context ctx;
    private MediaPlayer mediaPlayer;
    private List<String> playlist = new ArrayList<>();
    private int currentIndex = 0;
    private InetAddress streamTarget;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private DatagramSocket streamSocket;
    private boolean paused = false;
    public interface Listener { void onSongChanged(String name, int index); }
    private Listener listener;
    public MusicPlayer(Context ctx) { this.ctx = ctx; }
    public void setListener(Listener l) { this.listener = l; }
    public void setStreamTarget(InetAddress addr) { this.streamTarget = addr; }
    public void setPlaylist(List<String> paths) { playlist = new ArrayList<>(paths); currentIndex = 0; }
    public void play(int index) {
        if (playlist.isEmpty() || index < 0 || index >= playlist.size()) return;
        currentIndex = index;
        stopMedia();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(ctx, Uri.parse(playlist.get(index)));
            mediaPlayer.prepare();
            mediaPlayer.start();
            paused = false;
            String raw = playlist.get(index);
            String name = raw.substring(raw.lastIndexOf('/') + 1);
            if (listener != null) listener.onSongChanged(name, index);
            mediaPlayer.setOnCompletionListener(mp -> next());
            if (streamTarget != null) startStreaming(playlist.get(index));
        } catch (Exception e) { e.printStackTrace(); }
    }
    public void togglePause() {
        if (mediaPlayer == null) return;
        if (paused) { mediaPlayer.start(); paused = false; }
        else { mediaPlayer.pause(); paused = true; }
    }
    public boolean isPaused() { return paused; }
    public void next() { if (!playlist.isEmpty()) play((currentIndex + 1) % playlist.size()); }
    public void prev() {
        if (playlist.isEmpty()) return;
        int idx = currentIndex - 1; if (idx < 0) idx = playlist.size() - 1; play(idx);
    }
    public void pauseForPhoneCall() { if (mediaPlayer != null && mediaPlayer.isPlaying()) { mediaPlayer.pause(); paused = true; } }
    public void resumeAfterPhoneCall() { if (mediaPlayer != null && paused) { mediaPlayer.start(); paused = false; } }
    private void startStreaming(String uriStr) {
        stopStreaming();
        streaming.set(true);
        new Thread(() -> {
            try {
                streamSocket = new DatagramSocket();
                java.io.InputStream is = ctx.getContentResolver().openInputStream(Uri.parse(uriStr));
                if (is == null) return;
                byte[] buf = new byte[1024]; int read;
                while (streaming.get() && (read = is.read(buf)) > 0) {
                    if (!paused && streamTarget != null)
                        streamSocket.send(new DatagramPacket(buf, read, streamTarget, MUSIC_PORT));
                    Thread.sleep(10);
                }
                is.close();
            } catch (Exception e) {}
        }).start();
    }
    public void stopStreaming() {
        streaming.set(false);
        try { if (streamSocket != null) { streamSocket.close(); streamSocket = null; } } catch (Exception e) {}
    }
    private void stopMedia() {
        stopStreaming();
        try { if (mediaPlayer != null) { mediaPlayer.stop(); mediaPlayer.release(); mediaPlayer = null; } } catch (Exception e) {}
    }
    public void release() { stopMedia(); }
}
