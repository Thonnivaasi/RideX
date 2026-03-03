package com.voicecallpro.app;
import android.content.Context;
import java.io.*;
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context ctx;
    private final Thread.UncaughtExceptionHandler def;
    public CrashHandler(Context ctx) {
        this.ctx = ctx;
        this.def = Thread.getDefaultUncaughtExceptionHandler();
    }
    public static void init(Context ctx) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));
    }
    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            File f = new File(ctx.getExternalFilesDir(null), "crash.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(f));
            e.printStackTrace(pw);
            pw.flush(); pw.close();
        } catch (Exception ex) {}
        if (def != null) def.uncaughtException(t, e);
    }
}
