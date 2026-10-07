package com.winlator.renderer;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.SparseArray;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import java.nio.ByteBuffer;
import timber.log.Timber;

/**
 * Samples the top-left pixels of presented frames for the WowForeverInput addon's markers:
 * magenta at x 0..1 = text focus, cyan at x 2..4 = in-game UI loaded.
 */
public final class TextFocusProbe {
    private static final long INTERVAL_MS = 100;
    private static final long STALE_MS = 2000;
    private static final long GAP_MS = 1000;
    private static final int CYAN_X = 3;

    /** Per-window sample state, guarded by the class lock. */
    private static final class State {
        long firstMs, lastMs, magentaMs;
        boolean cyanSeen;
    }

    private static volatile boolean enabled;
    private static long lastSampleMs;
    private static final SparseArray<State> states = new SparseArray<>();

    private TextFocusProbe() {}

    public static synchronized void setEnabled(boolean on) {
        enabled = on;
        states.clear();
    }

    /** True while some window presented the magenta marker within the last STALE_MS. */
    public static synchronized boolean isMarkerVisible() {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < states.size(); i++) {
            State s = states.valueAt(i);
            if (s.magentaMs != 0 && now - s.magentaMs < STALE_MS) return true;
        }
        return false;
    }

    /** How long application window [windowId] has been presenting without a gap, or 0 if it isn't presenting. */
    public static synchronized long presentingMs(int windowId) {
        State s = states.get(windowId);
        if (s == null) return 0;
        long now = SystemClock.uptimeMillis();
        return now - s.lastMs > GAP_MS ? 0 : now - s.firstMs;
    }

    /** True once [windowId] has shown the cyan in-game marker. */
    public static synchronized boolean cyanSeen(int windowId) {
        State s = states.get(windowId);
        return s != null && s.cyanSeen;
    }

    /** Called with drawable.renderLock held, right before the frame is handed to the compositor. */
    static void onPresent(Window window, Drawable drawable) {
        if (!enabled || window == null || drawable == null) return;
        if (drawable.width < CYAN_X + 1 || drawable.height < 2) return;
        // Only WoW's window: re-locking other apps' buffers (Battle.net/CEF) stops their repaints.
        Window top = window;
        while (top != null && !top.isApplicationWindow()) top = top.getParent();
        String cls = top != null ? top.getClassName() : null;
        if (cls == null || !cls.toLowerCase(java.util.Locale.ROOT).contains("wowb-arm64")) return;
        long now = SystemClock.uptimeMillis();
        synchronized (TextFocusProbe.class) {
            if (now - lastSampleMs < INTERVAL_MS) return;
            lastSampleMs = now;
        }
        int hit;
        try {
            hit = sample(drawable);
        } catch (Throwable t) {
            return;
        }
        if (hit < 0) return;
        // Key by the application (top-level) window; Wine may present into a child client window.
        Window app = window;
        while (app != null && !app.isApplicationWindow()) app = app.getParent();
        int key = app != null ? app.id : window.id;
        synchronized (TextFocusProbe.class) {
            State s = states.get(key);
            if (s == null) {
                s = new State();
                states.put(key, s);
            }
            if (s.lastMs == 0 || now - s.lastMs > GAP_MS) s.firstMs = now;
            s.lastMs = now;
            boolean wasMagenta = s.magentaMs != 0, magenta = (hit & 1) != 0;
            s.magentaMs = magenta ? now : 0;
            if (magenta != wasMagenta) Timber.tag("WowInput").i("Probe window #%d: magenta %s", key, magenta ? "on" : "off");
            if ((hit & 2) != 0 && !s.cyanSeen) {
                s.cyanSeen = true;
                Timber.tag("WowInput").i("Probe window #%d: cyan seen (in-game UI loaded)", key);
            }
        }
    }

    /** Bit 1 = magenta at (0,0),(0,1); bit 2 = cyan at (CYAN_X,0); -1 = unreadable. */
    private static int sample(Drawable drawable) {
        Texture texture = drawable.getTexture();
        if (texture instanceof GPUImage) {
            GPUImage g = (GPUImage) texture;
            if (g.getHardwareBufferPtr() != 0) {
                // Re-lock so gralloc hands us a fresh (cache-invalidated) mapping of the GPU-written frame.
                closeFence(g.unlock());
                g.lock();
            }
            ByteBuffer vd = g.getVirtualData();
            if (vd == null) return -1;
            int stride = g.getStride() > 0 ? g.getStride() : drawable.width;
            return check(vd, stride);
        }
        ByteBuffer buf = drawable.getBuffer();
        if (buf == null) return -1;
        return check(buf, buf.capacity() / (drawable.height * 4));
    }

    private static int check(ByteBuffer b, int stridePx) {
        int row1 = stridePx * 4;
        if (b.capacity() < row1 + 4) return -1;
        int r = 0;
        if (isMagenta(b, 0) && isMagenta(b, row1)) r |= 1;
        if (isCyan(b, CYAN_X * 4)) r |= 2;
        return r;
    }

    // Byte order is RGBA or BGRA; magenta is the same in both.
    private static boolean isMagenta(ByteBuffer b, int off) {
        int c0 = b.get(off) & 0xFF, c1 = b.get(off + 1) & 0xFF, c2 = b.get(off + 2) & 0xFF;
        return c0 > 200 && c1 < 60 && c2 > 200;
    }

    // Cyan is (0,255,255) in RGBA and (255,255,0) in BGRA.
    private static boolean isCyan(ByteBuffer b, int off) {
        int c0 = b.get(off) & 0xFF, c1 = b.get(off + 1) & 0xFF, c2 = b.get(off + 2) & 0xFF;
        return c1 > 200 && ((c0 < 60 && c2 > 200) || (c0 > 200 && c2 < 60));
    }

    private static void closeFence(int fd) {
        if (fd < 0) return;
        try {
            ParcelFileDescriptor.adoptFd(fd).close();
        } catch (Exception ignored) {
        }
    }
}
