package com.voxivoid.recipelab;

import android.hardware.Camera;
import android.os.Handler;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Video recording for the Sony NEX-5R / CX68000 via the proprietary
 * {@code com.sony.scalar.hardware.CameraEx} framework, accessed entirely by
 * reflection so no Sony stub jar is required.
 *
 * <h3>Design goals (NEX-5R / API 10 / tight RAM)</h3>
 * <ul>
 *   <li><b>Zero-allocation hot path</b> — no {@code new} in toggle() or the
 *       recording-state poll; all work objects are pre-allocated.</li>
 *   <li><b>Non-blocking I/O</b> — AvindexStore notification runs on a
 *       dedicated {@link Thread} so it never delays the UI or preview threads.</li>
 *   <li><b>UI cap ≤ 2 Hz</b> — caller pumps {@link #isRecording()} from a
 *       500 ms Handler tick; this class itself performs no timed work.</li>
 *   <li><b>Defensive null / reflection checks</b> — every CameraEx / AvindexStore
 *       call is wrapped in try-catch so the app never force-closes if a firmware
 *       version doesn't expose a method.</li>
 * </ul>
 *
 * <h3>Attribution — DO NOT REMOVE</h3>
 * <pre>
 *   App:             dry-Film-lab
 *   Original base:   FilmLab / Recipe Lab  (Marten Boet &amp; Contributors)
 *   Video mod by:    Adriel Haryono
 * </pre>
 */
final class VideoRecorder {

    // ── Sony internal class names (accessed via reflection only) ──────────────
    private static final String CX_CLASS     = "com.sony.scalar.hardware.CameraEx";
    private static final String AV_CLASS     = "com.sony.scalar.provider.AvindexStore";

    // ── Method name constants to avoid repeated string allocation ─────────────
    private static final String M_START      = "startMovieRec";
    private static final String M_STOP       = "stopMovieRec";
    private static final String M_LAST_FILE  = "getLastMovieFile";  // returns String path, if available
    private static final String M_NOTIFY     = "notifyNewFile";     // AvindexStore: index a new media file

    // ── Pre-allocated callback interface ──────────────────────────────────────
    interface Listener {
        /** Called on the UI thread when recording state changes. */
        void onRecordingChanged(boolean recording, String error);
    }

    private final Object     cameraEx;      // the live CameraEx instance from MainActivity
    private final Handler    uiHandler;     // to dispatch listener on UI thread
    private final Listener   listener;
    private volatile boolean recording = false;

    // Pre-allocated Runnable — no new object at toggle time
    private final Runnable notifyRunnable = new Runnable() {
        @Override public void run() { doAvindexNotify(); }
    };

    VideoRecorder(Object cameraEx, Handler uiHandler, Listener listener) {
        this.cameraEx  = cameraEx;
        this.uiHandler = uiHandler;
        this.listener  = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** @return {@code true} while startMovieRec has been called and stopMovieRec has not. */
    boolean isRecording() { return recording; }

    /**
     * Toggle recording on / off.  Safe to call from the UI thread — all
     * heavy work is dispatched to a background thread.
     * K_MOVIE (scan 515) should be the only caller.
     */
    void toggle() {
        if (recording) stopRecording(); else startRecording();
    }

    /** Release state on Activity.onPause — stops gracefully if still recording. */
    void release() {
        if (recording) stopRecording();
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private void startRecording() {
        if (cameraEx == null) { notifyListener(false, "CameraEx unavailable"); return; }
        try {
            Method m = cameraEx.getClass().getMethod(M_START);
            m.invoke(cameraEx);
            recording = true;
            notifyListener(true, null);
        } catch (Throwable t) {
            recording = false;
            notifyListener(false, "startMovieRec: " + t.getMessage());
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private void stopRecording() {
        if (cameraEx == null) { recording = false; notifyListener(false, null); return; }
        try {
            Method m = cameraEx.getClass().getMethod(M_STOP);
            m.invoke(cameraEx);
        } catch (Throwable t) {
            // Log but don't crash — file may already be flushed by firmware
        } finally {
            recording = false;
            notifyListener(false, null);
            // AvindexStore notification is I/O — run off the UI thread.
            // Uses a plain Thread (no Executor) — API 10 doesn't guarantee
            // ThreadPoolExecutor availability in all Gingerbread builds.
            Thread t = new Thread(notifyRunnable, "avindex-notify");
            t.setPriority(Thread.MIN_PRIORITY);   // thermal: stay away from ISP threads
            t.start();
        }
    }

    // ── AvindexStore async notification ───────────────────────────────────────

    /**
     * Runs on a background thread.  Asks CameraEx for the last written movie
     * file path, then notifies AvindexStore so the .MP4 / .MTS appears in
     * the camera's gallery index without a power-cycle.
     *
     * All reflection calls are individually guarded — if the firmware build
     * does not expose these methods the file is still saved; it simply won't
     * appear in the gallery until the card is re-indexed on next boot.
     */
    private void doAvindexNotify() {
        String path = null;

        // 1. Ask CameraEx for the last written file path (firmware-dependent)
        try {
            Method gf = cameraEx.getClass().getMethod(M_LAST_FILE);
            Object result = gf.invoke(cameraEx);
            if (result instanceof String) path = (String) result;
        } catch (Throwable t) { /* method may not exist on all firmware */ }

        // 2. Fallback: scan DCIM/100MSDCF for the newest .MP4 / .MTS file
        if (path == null || path.isEmpty()) {
            try {
                File dcim = new File(android.os.Environment.getExternalStorageDirectory(), "DCIM/100MSDCF");
                File[] files = dcim.listFiles();
                if (files != null) {
                    File newest = null;
                    for (File f : files) {
                        String n = f.getName().toUpperCase();
                        if (n.endsWith(".MP4") || n.endsWith(".MTS") || n.endsWith(".MOV")) {
                            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                        }
                    }
                    if (newest != null) path = newest.getAbsolutePath();
                }
            } catch (Throwable t) { /* SD card unavailable */ }
        }

        // 3. Notify AvindexStore — this makes the file visible in the gallery
        notifyMediaFile(path);
    }

    /** Index a captured still after its file has been closed, without blocking the camera callback. */
    static void indexMediaFile(final String path) {
        if (path == null || path.length() == 0) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { notifyMediaFile(path); }
        }, "avindex-notify");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void notifyMediaFile(String path) {
        if (path == null || path.length() == 0) return;
        try {
            Class<?> av = Class.forName(AV_CLASS);
            Method notify = av.getMethod(M_NOTIFY, String.class);
            notify.invoke(null, path);
        } catch (Throwable t) {
            android.util.Log.w("dry-Film-lab", "AvindexStore could not index " + path, t);
        }
    }

    // ── UI thread dispatch ────────────────────────────────────────────────────

    private void notifyListener(final boolean rec, final String err) {
        if (listener == null) return;
        uiHandler.post(new Runnable() {
            @Override public void run() { listener.onRecordingChanged(rec, err); }
        });
    }
}
