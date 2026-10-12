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
 *   <li><b>No frame-processing loop</b> — recording is controlled by key events;
 *       the only timed UI work is the Activity's 500 ms REC indicator.</li>
 *   <li><b>Non-blocking I/O</b> — AvindexStore notification runs on a
 *       dedicated {@link Thread} so it never delays the UI or preview threads.</li>
 *   <li><b>UI cap ≤ 2 Hz</b> — caller pumps {@link #isRecording()} from a
 *       500 ms Handler tick; this class itself performs no timed work.</li>
 *   <li><b>Defensive reflection checks</b> — unavailable methods and explicit
 *       boolean failure results are reported instead of presented as success.</li>
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

    /** Toggle recording from the Sony movie key (scan 515); media indexing is dispatched separately. */
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
            invokeControl(cameraEx, M_START);
            recording = true;
            notifyListener(true, null);
        } catch (Exception e) {
            recording = false;
            String error = "startMovieRec: " + e.getMessage();
            android.util.Log.e("dry-Film-lab", error, e);
            notifyListener(false, error);
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private void stopRecording() {
        if (cameraEx == null) { recording = false; notifyListener(false, null); return; }
        String error = null;
        boolean stopped = false;
        try {
            invokeControl(cameraEx, M_STOP);
            stopped = true;
        } catch (Exception e) {
            error = "stopMovieRec: " + e.getMessage();
            android.util.Log.e("dry-Film-lab", error, e);
        } finally {
            if (stopped) {
                recording = false;
                notifyListener(false, error);
                queueAvindexNotify();
            } else {
                notifyListener(true, error);
            }
        }
    }

    private void queueAvindexNotify() {
        // API 10 builds cannot be assumed to have a usable ExecutorService.
        try {
            Thread t = new Thread(notifyRunnable, "avindex-notify");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (RuntimeException e) {
            android.util.Log.e("dry-Film-lab", "Unable to start AvindexStore worker", e);
        }
    }

    private static void invokeControl(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        Object result = method.invoke(target);
        if (Boolean.FALSE.equals(result)) throw new IllegalStateException(methodName + " returned false");
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
        } catch (Exception e) {
            android.util.Log.w("dry-Film-lab", "CameraEx could not provide the last movie path", e);
        }

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
            } catch (RuntimeException e) {
                android.util.Log.w("dry-Film-lab", "Unable to scan the camera media directory", e);
            }
        }

        // 3. Notify AvindexStore — this makes the file visible in the gallery
        notifyMediaFile(path);
    }

    /** Index a captured still after its file has been closed, without blocking the camera callback. */
    static void indexMediaFile(final String path) {
        if (path == null || path.length() == 0) return;
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { notifyMediaFile(path); }
            }, "avindex-notify");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (RuntimeException e) {
            android.util.Log.e("dry-Film-lab", "Unable to start still-image index worker for " + path, e);
        }
    }

    private static void notifyMediaFile(String path) {
        if (path == null || path.length() == 0) return;
        try {
            Class<?> av = Class.forName(AV_CLASS);
            Method notify = av.getMethod(M_NOTIFY, String.class);
            Object result = notify.invoke(null, path);
            if (Boolean.FALSE.equals(result)) throw new IllegalStateException(M_NOTIFY + " returned false");
        } catch (Exception e) {
            android.util.Log.w("dry-Film-lab", "AvindexStore could not index " + path, e);
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
