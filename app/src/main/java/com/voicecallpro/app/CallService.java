package com.voicecallpro.app;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import androidx.core.app.NotificationCompat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
public class CallService extends Service {
    public static final String ACTION_END_CALL = "com.voicecallpro.app.END_CALL";
    public static final String ACTION_RECONNECT_STATUS = "com.voicecallpro.app.RECONNECT_STATUS";
    public static final String EXTRA_RECONNECT_MSG = "reconnect_msg";
    private static final String CHANNEL_ID = "voicecall_channel";
    private static final int NOTIF_ID = 1;
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 1024;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO;
    public static final int PORT_AUDIO = 50005;
    public static final int PORT_PING = 50006;
    private final IBinder binder = new LocalBinder();
    public enum CallMode { WIFI_ONLY, WIFI_BT_AUDIO, BT_ONLY, BT_BT_MIC }
    private boolean muted = false;
    private boolean phoneCallActive = false;
    private InetAddress peerAddress;
    private BluetoothCallHelper btHelper;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private DatagramSocket audioSocket;
    private DatagramSocket pingSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>(50);
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    public class LocalBinder extends android.os.Binder {
        public CallService getService() { return CallService.this; }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        setupPhoneListener();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END_CALL.equals(intent.getAction())) {
            stopCall(); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Connecting..."));
        return START_STICKY;
    }
    private void setupPhoneListener() {
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        phoneStateListener = new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String number) {
                if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    phoneCallActive = true; muted = true;
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    phoneCallActive = false; muted = false;
                }
            }
        };
        if (telephonyManager != null)
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }
    public void setMuted(boolean m) { this.muted = m; }
    public void setSpeakerOn(boolean on) { audioManager.setSpeakerphoneOn(on); }
    public void setVolume(int level) { audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, level, 0); }
    public int getMaxVolume() { return audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL); }
    public int getCurrentVolume() { return audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL); }
    public void startWifiCall(InetAddress peer, boolean useBtAudio) {
        this.peerAddress = peer;
        running.set(true);
        setupAudio(useBtAudio ? CallMode.WIFI_BT_AUDIO : CallMode.WIFI_ONLY);
        startWifiSend(); startWifiReceive(); startPlaybackThread(); startPingResponder();
        updateNotification("In call (WiFi" + (useBtAudio ? " + BT Audio)" : ")"));
    }
    public void startBtCall(boolean useBtMic) {
        running.set(true);
        setupAudio(useBtMic ? CallMode.BT_BT_MIC : CallMode.BT_ONLY);
        startPlaybackThread();
        updateNotification("In call (BT" + (useBtMic ? " + BT Mic)" : ")"));
    }
    public void setBtHelper(BluetoothCallHelper helper) {
        this.btHelper = helper;
        helper.setAudioCallback(this::onAudioReceived, this::readMicBuffer);
    }
    public void triggerAutoReconnect(Runnable onSuccess, Runnable onGiveUp) {
        if (reconnecting.getAndSet(true)) return;
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000L;
            int countdown = 120;
            while (System.currentTimeMillis() < deadline) {
                broadcastReconnect("Reconnecting... " + countdown + "s");
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                countdown--;
                if (peerAddress != null) {
                    try {
                        DatagramSocket s = new DatagramSocket();
                        s.setSoTimeout(1000);
                        byte[] ping = "PING".getBytes();
                        s.send(new DatagramPacket(ping, ping.length, peerAddress, PORT_PING));
                        byte[] buf = new byte[8];
                        s.receive(new DatagramPacket(buf, buf.length));
                        s.close();
                        reconnecting.set(false);
                        broadcastReconnect("");
                        if (onSuccess != null) onSuccess.run();
                        return;
                    } catch (Exception e) {}
                }
            }
            reconnecting.set(false);
            broadcastReconnect("Reconnect failed");
            if (onGiveUp != null) onGiveUp.run();
        }).start();
    }
    private void broadcastReconnect(String msg) {
        Intent i = new Intent(ACTION_RECONNECT_STATUS);
        i.putExtra(EXTRA_RECONNECT_MSG, msg);
        sendBroadcast(i);
    }
    public void stopCall() {
        running.set(false); playbackQueue.clear(); stopAudio();
        try { if (audioSocket != null) { audioSocket.close(); audioSocket = null; } } catch (Exception e) {}
        try { if (pingSocket != null) { pingSocket.close(); pingSocket = null; } } catch (Exception e) {}
        if (btHelper != null) { btHelper.close(); btHelper = null; }
        stopForeground(true);
    }
    private void setupAudio(CallMode mode) {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (mode == CallMode.WIFI_BT_AUDIO || mode == CallMode.BT_BT_MIC) {
            audioManager.startBluetoothSco(); audioManager.setBluetoothScoOn(true);
        }
        int recBuf = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT), BUFFER_SIZE);
        int micSrc = (mode == CallMode.BT_BT_MIC || mode == CallMode.WIFI_BT_AUDIO)
            ? MediaRecorder.AudioSource.DEFAULT : MediaRecorder.AudioSource.MIC;
        audioRecord = new AudioRecord(micSrc, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, recBuf);
        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT);
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
            CHANNEL_OUT, AUDIO_FORMAT, minTrack, AudioTrack.MODE_STREAM);
        audioRecord.startRecording(); audioTrack.play();
    }
    private void stopAudio() {
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception e) {}
        try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); audioTrack = null; } } catch (Exception e) {}
        audioManager.setBluetoothScoOn(false); audioManager.stopBluetoothSco();
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }
    private byte[] readMicBuffer() {
        byte[] buf = new byte[BUFFER_SIZE];
        if (audioRecord != null && !muted && !phoneCallActive) audioRecord.read(buf, 0, buf.length);
        return buf;
    }
    private void onAudioReceived(byte[] data) {
        if (phoneCallActive) return;
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        if (!playbackQueue.offer(copy)) { playbackQueue.poll(); playbackQueue.offer(copy); }
    }
    private void startPlaybackThread() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] data = playbackQueue.take();
                    if (audioTrack != null && !phoneCallActive) audioTrack.write(data, 0, data.length);
                } catch (InterruptedException e) { break; }
            }
        }).start();
    }
    private void startWifiSend() {
        new Thread(() -> {
            try {
                audioSocket = new DatagramSocket();
                byte[] buf = new byte[BUFFER_SIZE];
                while (running.get()) {
                    if (audioRecord == null || muted || phoneCallActive || peerAddress == null) { Thread.sleep(10); continue; }
                    int read = audioRecord.read(buf, 0, buf.length);
                    if (read > 0) audioSocket.send(new DatagramPacket(buf, read, peerAddress, PORT_AUDIO));
                }
            } catch (Exception e) {}
        }).start();
    }
    private void startWifiReceive() {
        new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket(PORT_AUDIO)) {
                s.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE * 2];
                while (running.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    s.receive(pkt);
                    if (peerAddress == null) peerAddress = pkt.getAddress();
                    byte[] data = new byte[pkt.getLength()];
                    System.arraycopy(pkt.getData(), 0, data, 0, pkt.getLength());
                    onAudioReceived(data);
                }
            } catch (Exception e) {}
        }).start();
    }
    private void startPingResponder() {
        new Thread(() -> {
            try {
                pingSocket = new DatagramSocket(PORT_PING);
                byte[] buf = new byte[16];
                while (running.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    pingSocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength());
                    if (msg.startsWith("PING")) {
                        byte[] pong = "PONG".getBytes();
                        pingSocket.send(new DatagramPacket(pong, pong.length, pkt.getAddress(), pkt.getPort()));
                    }
                }
            } catch (Exception e) {}
        }).start();
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ridex", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent endIntent = new Intent(this, EndCallReceiver.class);
        endIntent.setAction(ACTION_END_CALL);
        PendingIntent endPi = PendingIntent.getBroadcast(this, 1, endIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ridex")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .setSilent(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "End Call", endPi)
            .build();
    }
    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }
    @Override public void onDestroy() {
        if (telephonyManager != null && phoneStateListener != null)
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        stopCall(); super.onDestroy();
    }
}
