import os

JAVA = os.path.expanduser("~/ridex/app/src/main/java/com/voicecallpro/app")

# Write a CrashHandler that saves crash to a file
with open(os.path.join(JAVA, "CrashHandler.java"), "w") as f:
    f.write("package com.voicecallpro.app;\n")
    f.write("import android.content.Context;\n")
    f.write("import java.io.*;\n")
    f.write("public class CrashHandler implements Thread.UncaughtExceptionHandler {\n")
    f.write("    private final Context ctx;\n")
    f.write("    private final Thread.UncaughtExceptionHandler def;\n")
    f.write("    public CrashHandler(Context ctx) {\n")
    f.write("        this.ctx = ctx;\n")
    f.write("        this.def = Thread.getDefaultUncaughtExceptionHandler();\n")
    f.write("    }\n")
    f.write("    public static void init(Context ctx) {\n")
    f.write("        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));\n")
    f.write("    }\n")
    f.write("    @Override\n")
    f.write("    public void uncaughtException(Thread t, Throwable e) {\n")
    f.write("        try {\n")
    f.write("            File f = new File(ctx.getExternalFilesDir(null), \"crash.txt\");\n")
    f.write("            PrintWriter pw = new PrintWriter(new FileWriter(f));\n")
    f.write("            e.printStackTrace(pw);\n")
    f.write("            pw.flush(); pw.close();\n")
    f.write("        } catch (Exception ex) {}\n")
    f.write("        if (def != null) def.uncaughtException(t, e);\n")
    f.write("    }\n")
    f.write("}\n")
print("OK CrashHandler.java")

# Patch MainActivity onCreate to init crash handler as first line
path = os.path.join(JAVA, "MainActivity.java")
with open(path, "r") as f:
    content = f.read()

old = "        historyManager = new CallHistoryManager(this);"
new = "        CrashHandler.init(this);\n        historyManager = new CallHistoryManager(this);"
content = content.replace(old, new)

with open(path, "w") as f:
    f.write(content)
print("OK MainActivity.java patched")
print("Done - git add, commit, push, rebuild")

