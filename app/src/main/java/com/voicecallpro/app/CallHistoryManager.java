package com.voicecallpro.app;
import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class CallHistoryManager {
    private static final String PREFS = "call_history";
    private final SharedPreferences prefs;
    public static class CallRecord {
        public final String dateTime, duration, mode;
        public CallRecord(String dateTime, String duration, String mode) {
            this.dateTime = dateTime; this.duration = duration; this.mode = mode;
        }
        public String toDisplay() { return dateTime + "  " + duration + "  (" + mode + ")"; }
    }
    public CallHistoryManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public void saveCall(int durationSeconds, String mode) {
        String dt = new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(new Date());
        String dur = String.format(Locale.getDefault(), "%02d:%02d", durationSeconds / 60, durationSeconds % 60);
        String entry = dt + "|" + dur + "|" + mode;
        String old0 = prefs.getString("entry_0", "");
        prefs.edit().putString("entry_1", old0).putString("entry_0", entry).apply();
    }
    public List<CallRecord> getHistory() {
        List<CallRecord> list = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String raw = prefs.getString("entry_" + i, "");
            if (!raw.isEmpty()) {
                String[] parts = raw.split("\\|");
                if (parts.length == 3) list.add(new CallRecord(parts[0], parts[1], parts[2]));
            }
        }
        return list;
    }
}
