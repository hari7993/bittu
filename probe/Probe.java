// Bittu phase-0 probe. Runs on the phone as the shell user (the same user the built-in shell bridge will use):
//   adb shell CLASSPATH=/data/local/tmp/bittu-probe.dex app_process /system/bin Probe <mode>
// Modes:
//   probe         - no call needed: checks that this phone exposes call audio to the shell user.
//   echo [secs]   - waits for an incoming call, answers it, plays a beep to the caller, then repeats the
//                   caller's own voice back to them after a short delay. Proves audio in both directions.
// Nothing is recorded to a file. Only loudness numbers are printed.
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class Probe {
  static final int RATE = 16000;

  static void log(String s) { System.out.println(s); System.out.flush(); }

  static Throwable root(Throwable t) { while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause(); return t; }

  static String sh(String cmd) {
    try {
      Process p = Runtime.getRuntime().exec(new String[] {"sh", "-c", cmd});
      BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
      StringBuilder b = new StringBuilder();
      String l;
      while ((l = r.readLine()) != null) b.append(l).append('\n');
      p.waitFor();
      return b.toString();
    } catch (Exception e) { return ""; }
  }

  // 0 idle, 1 ringing, 2 in a call. Two SIMs report two values; the highest wins.
  static int callState() {
    int max = 0;
    for (String l : sh("dumpsys telephony.registry | grep mCallState=").split("\n")) {
      int i = l.indexOf("mCallState=");
      if (i < 0) continue;
      try { max = Math.max(max, Integer.parseInt(l.substring(i + 11).trim().substring(0, 1))); } catch (Exception ignored) { }
    }
    return max;
  }

  static Context shellContext() {
    try {
      Class<?> at = Class.forName("android.app.ActivityThread");
      Object thread = at.getMethod("systemMain").invoke(null);
      Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
      // Android checks that the app name on every audio request belongs to the calling user. This process runs
      // as the shell user, so everything must be attributed to the shell app, not to "android".
      Context c = new ShellContext(sys.createPackageContext("com.android.shell", 0));
      android.app.Application app = android.app.Instrumentation.newApplication(android.app.Application.class, c);
      java.lang.reflect.Field f = at.getDeclaredField("mInitialApplication");
      f.setAccessible(true);
      f.set(thread, app);
      log("context: ok, package " + c.getPackageName() + ", requests attributed to " + c.getAttributionSource().getPackageName());
      return c;
    } catch (Throwable e) { log("context: FAILED " + root(e)); return null; }
  }

  static class ShellContext extends android.content.ContextWrapper {
    ShellContext(Context base) { super(base); }
    @Override public String getPackageName() { return "com.android.shell"; }
    @Override public String getOpPackageName() { return "com.android.shell"; }
    @Override public Context getApplicationContext() { return this; }
    @Override public android.content.AttributionSource getAttributionSource() {
      return new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
    }
  }

  static AudioManager audioManager(Context c) {
    try {
      if (c != null) return AudioManager.class.getConstructor(Context.class).newInstance(c);
    } catch (Throwable e) { log("audio manager from context: FAILED " + root(e)); }
    try { return AudioManager.class.getDeclaredConstructor().newInstance(); } catch (Throwable e) { log("audio manager: FAILED " + root(e)); return null; }
  }

  static AudioFormat fmt(boolean out) {
    return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
        .setChannelMask(out ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_IN_MONO).build();
  }

  static AudioRecord downlink(AudioManager am) throws Throwable {
    Method m = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class);
    try { return (AudioRecord) m.invoke(am, fmt(false)); } catch (InvocationTargetException e) { throw root(e); }
  }

  static AudioTrack uplink(AudioManager am) throws Throwable {
    Method m = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
    try { return (AudioTrack) m.invoke(am, fmt(true)); } catch (InvocationTargetException e) { throw root(e); }
  }

  static void probe(AudioManager am) {
    try {
      Object ok = AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(am);
      log("RESULT call audio can be intercepted on this phone: " + ok);
    } catch (Throwable e) { log("RESULT isPstnCallAudioInterceptable: FAILED " + root(e)); }
    try {
      StringBuilder in = new StringBuilder(), out = new StringBuilder();
      for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) (d.isSource() ? in : out).append(d.getType()).append(' ');
      log("input device types: " + in + "(telephony = 18)");
      log("output device types: " + out + "(telephony = 18)");
    } catch (Throwable e) { log("device list: FAILED " + root(e)); }
    log("call state now: " + callState() + " (0 idle, 1 ringing, 2 in a call)");
    try { AudioRecord r = downlink(am); log("downlink recorder outside a call: created, state " + r.getState()); r.release(); }
    catch (Throwable e) { log("downlink recorder outside a call: " + e); }
    try { AudioTrack t = uplink(am); log("uplink player outside a call: created, state " + t.getState()); t.release(); }
    catch (Throwable e) { log("uplink player outside a call: " + e); }
  }

  static Context ctx;
  static final String[] STATE = {"?", "stopped", "?", "RECORDING"};

  // A recorder on a named Android audio source, attributed to the shell package.
  static AudioRecord plain(int source) {
    AudioRecord.Builder b = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(fmt(false));
    if (ctx != null) b.setContext(ctx);
    return b.build();
  }

  // Starts a recorder and reads half a second. Returns samples read, or -1 if it did not start.
  static int trial(String name, AudioRecord r) {
    try {
      r.startRecording();
      int st = r.getRecordingState();
      short[] buf = new short[RATE / 50];
      int got = 0; double peak = 0;
      for (int i = 0; i < 25 && st == AudioRecord.RECORDSTATE_RECORDING; i++) { int n = r.read(buf, 0, buf.length); if (n > 0) { got += n; peak = Math.max(peak, rms(buf, n)); } }
      log(String.format("  %-28s state %s, read %d samples, peak loudness %.0f", name, STATE[Math.max(0, Math.min(3, st))], got, peak));
      return st == AudioRecord.RECORDSTATE_RECORDING ? got : -1;
    } catch (Throwable e) { log("  " + name + " FAILED: " + root(e)); return -1; }
  }

  // Tries every way of hearing the caller and returns the first recorder that delivers sound data.
  static AudioRecord openDownlink(AudioManager am) {
    String[] names = {"system call-audio route", "voice downlink source", "voice call source"};
    for (int k = 0; k < 3; k++) {
      AudioRecord r = null;
      try { r = k == 0 ? downlink(am) : plain(k == 1 ? 3 : 4); } catch (Throwable e) { log("  " + names[k] + " could not be created: " + e); continue; }
      if (trial(names[k], r) > 0) { log("USING " + names[k]); return r; }
      try { r.stop(); } catch (Throwable ignored) { }
      r.release();
    }
    return null;
  }

  // No call needed: shows which microphone-type sources the shell user is allowed to start at all.
  static void rectest() {
    int[] src = {1, 6, 7, 3, 4};
    String[] nm = {"microphone", "voice recognition", "voice communication", "voice downlink (needs a call)", "voice call (needs a call)"};
    for (int i = 0; i < src.length; i++) {
      try { AudioRecord r = plain(src[i]); trial(nm[i], r); try { r.stop(); } catch (Throwable ignored) { } r.release(); }
      catch (Throwable e) { log("  " + nm[i] + " could not be created: " + root(e)); }
    }
  }

  static double rms(short[] b, int n) { double s = 0; for (int i = 0; i < n; i++) s += (double) b[i] * b[i]; return n == 0 ? 0 : Math.sqrt(s / n); }

  static void echo(AudioManager am, int secs) throws Exception {
    log("WAITING for an incoming call (up to 3 minutes). Call this phone now from another phone.");
    long until = System.currentTimeMillis() + 180000;
    while (callState() != 1) { if (System.currentTimeMillis() > until) { log("RESULT no call came. Nothing was answered."); return; } Thread.sleep(400); }
    log("ringing. Answering in 2 seconds.");
    Thread.sleep(2000);
    sh("input keyevent 5");
    for (int i = 0; i < 25 && callState() != 2; i++) Thread.sleep(200);
    if (callState() != 2) { log("RESULT could not answer the call (state " + callState() + ")"); return; }
    log("answered. Opening call audio.");
    Thread.sleep(1200);

    AudioRecord rec = null;
    AudioTrack trk = null;
    log("audio mode during the call: " + am.getMode() + " (2 = in call)");
    rec = openDownlink(am);
    if (rec == null) log("RESULT no way of hearing the caller started on this phone");
    try { trk = uplink(am); log("uplink player: created, state " + trk.getState()); } catch (Throwable e) { log("RESULT uplink player FAILED: " + e); }

    try {
      if (trk != null) trk.play();
      int frame = RATE / 50; // 20 ms
      short[] in = new short[frame];
      short[] out = new short[frame];
      int delayFrames = 45; // 0.9 s before the caller hears themselves
      short[][] ring = new short[delayFrames][frame];
      int pos = 0, total = secs * 50, heard = 0;
      double peak = 0, sum = 0;
      long written = 0;
      for (int f = 0; f < total && callState() == 2; f++) {
        int n = rec != null ? rec.read(in, 0, frame) : 0;
        if (rec == null) Thread.sleep(20);
        double level = n > 0 ? rms(in, n) : 0;
        if (level > 300) heard++;
        peak = Math.max(peak, level);
        sum += level;
        // First 3 seconds: three beeps so the caller knows Bittu is on the line. After that: their own voice, delayed.
        boolean beep = f < 150 && (f / 25) % 2 == 0;
        for (int i = 0; i < frame; i++) out[i] = beep ? (short) (Math.sin(2 * Math.PI * 660 * (f * frame + i) / RATE) * 9000) : ring[pos][i];
        if (n > 0) System.arraycopy(in, 0, ring[pos], 0, frame); else java.util.Arrays.fill(ring[pos], (short) 0);
        pos = (pos + 1) % delayFrames;
        if (trk != null) { int w = trk.write(out, 0, frame); if (w > 0) written += w; }
        if (f % 50 == 49) log(String.format("  second %2d: caller loudness %5.0f, read %d, sent %d", f / 50 + 1, level, n, written));
      }
      log(String.format("RESULT downlink (hearing the caller): %s. Loud frames %d of %d, peak %.0f, average %.0f", rec == null ? "NOT AVAILABLE" : heard > 10 ? "WORKS" : "SILENT", heard, total, peak, sum / Math.max(1, total)));
      log("RESULT uplink (speaking to the caller): " + (trk == null ? "NOT AVAILABLE" : written > 0 ? "audio was accepted (" + written + " samples). The caller must confirm they heard beeps and their own voice." : "NOTHING WAS ACCEPTED"));
    } catch (Throwable e) {
      log("RESULT audio loop FAILED: " + root(e));
    } finally {
      try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) { }
      try { if (trk != null) { trk.stop(); trk.release(); } } catch (Throwable ignored) { }
      if (callState() == 2) { sh("input keyevent 6"); log("call ended by the test."); }
    }
  }

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "probe";
    log("Bittu probe, mode " + mode + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), uid " + android.os.Process.myUid());
    Looper.prepareMainLooper();
    ctx = shellContext();
    AudioManager am = audioManager(ctx);
    if (am == null) { log("RESULT cannot reach the audio system"); System.exit(1); }
    if (mode.equals("echo")) echo(am, args.length > 1 ? Integer.parseInt(args[1]) : 20);
    else if (mode.equals("rectest")) rectest();
    else probe(am);
    log("done.");
    System.exit(0);
  }
}
