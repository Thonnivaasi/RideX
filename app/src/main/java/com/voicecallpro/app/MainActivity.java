package com.voicecallpro.app;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
public class MainActivity extends AppCompatActivity {
    private static final int PERM_REQUEST = 100;
    private SwitchMaterial switchWifiBt, switchBtMic;
    private TextView tvStatus, tvTimer, tvRoomCodeDisplay, tvLastCall;
    private TextView tvHistory1, tvHistory2, tvSongName, tvBtSignal;
    private View bar1, bar2, bar3;
    private Button btnHost, btnCall, btnMute, btnSpeaker, btnEndCall;
    private Button btnPrev, btnPlayPause, btnNext, btnAddPlaylist;
    private TextInputEditText etRoomCode;
    private View layoutWifiCode, layoutBtDevices, musicPanelExpanded;
    private Spinner spinnerDevices, spinnerPlaylists;
    private ListView listSongs;
    private SeekBar seekVolume;
    private boolean inCall = false, muted = false, speakerOn = false;
    private boolean musicPanelOpen = false;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int callSeconds = 0;
    private Runnable timerRunnable;
    private CallService callService;
    private boolean serviceBound = false;
    private final ServiceConnection serviceConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            callService = ((CallService.LocalBinder) b).getService();
            serviceBound = true; syncVolumeSlider();
        }
        public void onServiceDisconnected(ComponentName n) { serviceBound = false; }
    };
    private BluetoothAdapter btAdapter;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final List<String> pairedNames = new ArrayList<>();
    private WifiDiscoveryHelper wifiDiscovery;
    private String currentRoomCode;
    private String currentCallMode = "WiFi";
    private CallHistoryManager historyManager;
    private SignalMonitor signalMonitor;
    private MusicPlayer musicPlayer;
    private MusicStreamer musicStreamer;
    private final List<String> playlistNames = new ArrayList<>();
    private final List<List<String>> playlists = new ArrayList<>();
    private int currentPlaylistIndex = 0;
    private ActivityResultLauncher<Uri> folderPicker;
    private final BroadcastReceiver reconnectReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String msg = intent.getStringExtra(CallService.EXTRA_RECONNECT_MSG);
            if (msg != null && !msg.isEmpty()) setStatus(msg);
            else if (inCall) setStatus("Reconnected");
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        historyManager = new CallHistoryManager(this);
        musicPlayer = new MusicPlayer(this);
        musicStreamer = new MusicStreamer();
        bindViews(); setupBluetooth(); requestPerms();
        setupListeners(); setupMusicPlayer(); setupFolderPicker(); refreshHistory();
        bindService(new Intent(this, CallService.class), serviceConn, Context.BIND_AUTO_CREATE);
        registerReceiver(reconnectReceiver, new IntentFilter(CallService.ACTION_RECONNECT_STATUS));
    }
    private void bindViews() {
        switchWifiBt       = findViewById(R.id.switchWifiBt);
        switchBtMic        = findViewById(R.id.switchBtMic);
        tvStatus           = findViewById(R.id.tvStatus);
        tvTimer            = findViewById(R.id.tvTimer);
        tvRoomCodeDisplay  = findViewById(R.id.tvRoomCodeDisplay);
        tvLastCall         = findViewById(R.id.tvLastCall);
        tvHistory1         = findViewById(R.id.tvHistory1);
        tvHistory2         = findViewById(R.id.tvHistory2);
        tvSongName         = findViewById(R.id.tvSongName);
        tvBtSignal         = findViewById(R.id.tvBtSignal);
        bar1               = findViewById(R.id.bar1);
        bar2               = findViewById(R.id.bar2);
        bar3               = findViewById(R.id.bar3);
        btnHost            = findViewById(R.id.btnHost);
        btnCall            = findViewById(R.id.btnCall);
        btnMute            = findViewById(R.id.btnMute);
        btnSpeaker         = findViewById(R.id.btnSpeaker);
        btnEndCall         = findViewById(R.id.btnEndCall);
        btnPrev            = findViewById(R.id.btnPrev);
        btnPlayPause       = findViewById(R.id.btnPlayPause);
        btnNext            = findViewById(R.id.btnNext);
        btnAddPlaylist     = findViewById(R.id.btnAddPlaylist);
        etRoomCode         = findViewById(R.id.etRoomCode);
        layoutWifiCode     = findViewById(R.id.layoutWifiCode);
        layoutBtDevices    = findViewById(R.id.layoutBtDevices);
        musicPanelExpanded = findViewById(R.id.musicPanelExpanded);
        spinnerDevices     = findViewById(R.id.spinnerDevices);
        spinnerPlaylists   = findViewById(R.id.spinnerPlaylists);
        listSongs          = findViewById(R.id.listSongs);
        seekVolume         = findViewById(R.id.seekVolume);
    }
    private void setupBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
    }
    private void setupListeners() {
        switchWifiBt.setOnCheckedChangeListener((b, checked) -> {
            ((TextView) findViewById(R.id.tvWifiBtLabel)).setTextColor(getColor(checked ? R.color.accent : R.color.text_secondary));
            ((TextView) findViewById(R.id.tvWifiLabel)).setTextColor(getColor(checked ? R.color.text_secondary : R.color.text_white));
        });
        switchBtMic.setOnCheckedChangeListener((b, checked) -> {
            layoutWifiCode.setVisibility(checked ? View.GONE : View.VISIBLE);
            layoutBtDevices.setVisibility(checked ? View.VISIBLE : View.GONE);
            bar1.setVisibility(checked ? View.GONE : View.VISIBLE);
            bar2.setVisibility(checked ? View.GONE : View.VISIBLE);
            bar3.setVisibility(checked ? View.GONE : View.VISIBLE);
            tvBtSignal.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) loadPairedDevices();
        });
        btnHost.setOnClickListener(v -> onHostClicked());
        btnCall.setOnClickListener(v -> onCallClicked());
        btnMute.setOnClickListener(v -> toggleMute());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        btnEndCall.setOnClickListener(v -> endCall());
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && serviceBound) callService.setVolume(progress);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        findViewById(R.id.musicBarCollapsed).setOnClickListener(v -> toggleMusicPanel());
        btnPrev.setOnClickListener(v -> musicPlayer.prev());
        btnNext.setOnClickListener(v -> musicPlayer.next());
        btnPlayPause.setOnClickListener(v -> {
            musicPlayer.togglePause();
            btnPlayPause.setText(musicPlayer.isPaused() ? ">" : "||");
        });
        btnAddPlaylist.setOnClickListener(v -> folderPicker.launch(null));
        spinnerPlaylists.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                currentPlaylistIndex = pos;
                if (pos < playlists.size()) { musicPlayer.setPlaylist(playlists.get(pos)); refreshSongList(); }
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        listSongs.setOnItemClickListener((parent, view, pos, id) -> musicPlayer.play(pos));
    }
    private void syncVolumeSlider() {
        if (!serviceBound) return;
        seekVolume.setMax(callService.getMaxVolume());
        seekVolume.setProgress(callService.getCurrentVolume());
    }
    private void setupMusicPlayer() {
        musicPlayer.setListener((name, idx) -> runOnUiThread(() -> {
            tvSongName.setText(name);
            btnPlayPause.setText("||");
        }));
    }
    private void setupFolderPicker() {
        folderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) return;
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String seg = uri.getLastPathSegment();
            String folderName = (seg != null && seg.contains(":") ? seg.substring(seg.lastIndexOf(':') + 1) : seg);
            List<String> songs = scanAudioFiles(uri);
            if (songs.isEmpty()) { toast("No audio files found"); return; }
            playlistNames.add(folderName); playlists.add(songs);
            refreshPlaylistSpinner();
            toast("Playlist added: " + folderName + " (" + songs.size() + " songs)");
        });
    }
    private List<String> scanAudioFiles(Uri treeUri) {
        List<String> result = new ArrayList<>();
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
            Cursor cursor = getContentResolver().query(childUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String mime  = cursor.getString(1);
                    if (mime != null && mime.startsWith("audio/")) {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        result.add(fileUri.toString());
                    }
                }
                cursor.close();
            }
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }
    private void refreshPlaylistSpinner() {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, playlistNames);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPlaylists.setAdapter(a);
    }
    private void refreshSongList() {
        if (currentPlaylistIndex >= playlists.size()) return;
        List<String> songs = playlists.get(currentPlaylistIndex);
        List<String> names = new ArrayList<>();
        for (String s : songs) names.add(s.substring(s.lastIndexOf('/') + 1));
        listSongs.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
    }
    private void toggleMusicPanel() {
        musicPanelOpen = !musicPanelOpen;
        musicPanelExpanded.setVisibility(musicPanelOpen ? View.VISIBLE : View.GONE);
    }
    private void loadPairedDevices() {
        pairedDevices.clear(); pairedNames.clear();
        if (btAdapter == null) { toast("BT not available"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("BT Connect permission needed"); return;
        }
        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            pairedDevices.add(d);
            pairedNames.add(d.getName() != null ? d.getName() : d.getAddress());
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, pairedNames);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(a);
        if (pairedNames.isEmpty()) toast("No paired BT devices");
    }
    private void onHostClicked() {
        if (switchBtMic.isChecked()) {
            if (btAdapter == null) { toast("No BT"); return; }
            currentCallMode = "BT";
            setStatus("Waiting for BT connection...");
            startSvc();
            BluetoothCallHelper helper = new BluetoothCallHelper();
            if (serviceBound) callService.setBtHelper(helper);
            helper.startAsHost(btAdapter, new BluetoothCallHelper.ConnectionListener() {
                public void onConnected() {
                    runOnUiThread(() -> {
                        if (helper.isConnected()) {
                            setStatus("Connected (BT)"); updateBtSignal(true);
                            if (serviceBound) callService.startBtCall(true);
                            onCallStarted();
                        } else setStatus("Waiting for guest...");
                    });
                }
                public void onError(String msg) { runOnUiThread(() -> setStatus("BT Error: " + msg)); }
            });
        } else {
            currentCallMode = switchWifiBt.isChecked() ? "WiFi+BT" : "WiFi";
            currentRoomCode = String.format("%06d", new Random().nextInt(1000000));
            tvRoomCodeDisplay.setText("Room: " + currentRoomCode);
            tvRoomCodeDisplay.setVisibility(View.VISIBLE);
            setStatus("Hosting - code: " + currentRoomCode);
            if (wifiDiscovery != null) wifiDiscovery.stop();
            wifiDiscovery = new WifiDiscoveryHelper();
            wifiDiscovery.startHostBeacon(currentRoomCode);
            startSvc();
            if (serviceBound) callService.startWifiCall(null, switchWifiBt.isChecked());
            onCallStarted();
        }
    }
    private void onCallClicked() {
        if (switchBtMic.isChecked()) {
            if (pairedDevices.isEmpty()) { toast("No paired device"); return; }
            BluetoothDevice device = pairedDevices.get(spinnerDevices.getSelectedItemPosition());
            currentCallMode = "BT";
            setStatus("Connecting to " + pairedNames.get(spinnerDevices.getSelectedItemPosition()));
            startSvc();
            BluetoothCallHelper helper = new BluetoothCallHelper();
            if (serviceBound) callService.setBtHelper(helper);
            helper.connectToDevice(device, new BluetoothCallHelper.ConnectionListener() {
                public void onConnected() {
                    runOnUiThread(() -> {
                        setStatus("Connected (BT)"); updateBtSignal(true);
                        if (serviceBound) callService.startBtCall(true);
                        onCallStarted();
                    });
                }
                public void onError(String msg) { runOnUiThread(() -> setStatus("Error: " + msg)); }
            });
        } else {
            String code = etRoomCode.getText() != null ? etRoomCode.getText().toString().trim() : "";
            if (code.length() != 6) { toast("Enter 6-digit room code"); return; }
            currentCallMode = switchWifiBt.isChecked() ? "WiFi+BT" : "WiFi";
            setStatus("Searching...");
            if (wifiDiscovery != null) wifiDiscovery.stop();
            wifiDiscovery = new WifiDiscoveryHelper();
            wifiDiscovery.searchForHost(code, new WifiDiscoveryHelper.DiscoveryListener() {
                public void onHostFound(InetAddress addr) {
                    runOnUiThread(() -> {
                        setStatus("Connected (WiFi)");
                        startSvc();
                        if (serviceBound) callService.startWifiCall(addr, switchWifiBt.isChecked());
                        startSignalMonitor(addr);
                        if (!playlists.isEmpty()) musicPlayer.setStreamTarget(addr);
                        musicStreamer.start();
                        onCallStarted();
                    });
                }
                public void onTimeout() { runOnUiThread(() -> setStatus("Host not found")); }
                public void onError(String msg) { runOnUiThread(() -> setStatus("Error: " + msg)); }
            });
        }
    }
    private void startSignalMonitor(InetAddress addr) {
        if (signalMonitor != null) signalMonitor.stop();
        signalMonitor = new SignalMonitor();
        signalMonitor.start(addr, CallService.PORT_PING,
            (quality, pingMs) -> runOnUiThread(() -> updateWifiSignal(quality)));
    }
    private void updateWifiSignal(SignalMonitor.Quality q) {
        int good = getColor(R.color.green_signal);
        int fair = getColor(R.color.orange_signal);
        int poor = getColor(R.color.red_signal);
        int grey = getColor(R.color.text_grey);
        if (q == SignalMonitor.Quality.GOOD) {
            bar1.setBackgroundColor(good); bar2.setBackgroundColor(good); bar3.setBackgroundColor(good);
        } else if (q == SignalMonitor.Quality.FAIR) {
            bar1.setBackgroundColor(fair); bar2.setBackgroundColor(fair); bar3.setBackgroundColor(grey);
        } else if (q == SignalMonitor.Quality.POOR) {
            bar1.setBackgroundColor(poor); bar2.setBackgroundColor(grey); bar3.setBackgroundColor(grey);
        } else {
            bar1.setBackgroundColor(grey); bar2.setBackgroundColor(grey); bar3.setBackgroundColor(grey);
        }
    }
    private void updateBtSignal(boolean connected) {
        tvBtSignal.setTextColor(getColor(connected ? R.color.green_signal : R.color.red_signal));
    }
    private void startSvc() {
        Intent i = new Intent(this, CallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
    }
    private void onCallStarted() {
        inCall = true;
        btnEndCall.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        tvLastCall.setVisibility(View.GONE);
        callSeconds = 0; startTimer(); syncVolumeSlider();
    }
    private void endCall() {
        inCall = false; stopTimer();
        historyManager.saveCall(callSeconds, currentCallMode);
        String dur = String.format("%02d:%02d", callSeconds / 60, callSeconds % 60);
        tvLastCall.setText("Last call: " + dur);
        tvLastCall.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.GONE);
        tvRoomCodeDisplay.setVisibility(View.GONE);
        setStatus("Idle");
        if (signalMonitor != null) { signalMonitor.stop(); signalMonitor = null; }
        if (wifiDiscovery != null) { wifiDiscovery.stop(); wifiDiscovery = null; }
        musicStreamer.stop(); musicPlayer.setStreamTarget(null);
        updateBtSignal(false);
        int grey = getColor(R.color.text_grey);
        bar1.setBackgroundColor(grey); bar2.setBackgroundColor(grey); bar3.setBackgroundColor(grey);
        if (serviceBound) callService.stopCall();
        stopService(new Intent(this, CallService.class));
        refreshHistory();
    }
    private void refreshHistory() {
        List<CallHistoryManager.CallRecord> records = historyManager.getHistory();
        tvHistory1.setText(records.size() > 0 ? records.get(0).toDisplay() : "");
        tvHistory2.setText(records.size() > 1 ? records.get(1).toDisplay() : "");
    }
    private void toggleMute() {
        muted = !muted;
        btnMute.setText(muted ? "Unmute" : "Mute");
        if (serviceBound) callService.setMuted(muted);
    }
    private void toggleSpeaker() {
        speakerOn = !speakerOn;
        btnSpeaker.setText(speakerOn ? "Earpiece" : "Speaker");
        if (serviceBound) callService.setSpeakerOn(speakerOn);
    }
    private void startTimer() {
        timerRunnable = new Runnable() {
            public void run() {
                callSeconds++;
                tvTimer.setText(String.format("%02d:%02d", callSeconds / 60, callSeconds % 60));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }
    private void stopTimer() { if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable); }
    private void setStatus(String s) { tvStatus.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void requestPerms() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else { perms.add(Manifest.permission.READ_EXTERNAL_STORAGE); }
        List<String> needed = new ArrayList<>();
        for (String p2 : perms)
            if (checkSelfPermission(p2) != PackageManager.PERMISSION_GRANTED) needed.add(p2);
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST);
    }
    @Override public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
    }
    @Override protected void onDestroy() {
        try { unregisterReceiver(reconnectReceiver); } catch (Exception e) {}
        if (serviceBound) unbindService(serviceConn);
        musicPlayer.release(); musicStreamer.stop();
        super.onDestroy();
    }
}
