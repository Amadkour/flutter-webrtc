package com.cloudwebrtc.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.YuvHelper;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The live screen-share capturer of the single-MediaProjection design.
 *
 * The app's ScreenCaptureService owns the one MediaProjection; its
 * ScreenPlaceholderRenderer reads each screen frame back (already scaled
 * down on the GPU and flipped to top-left origin) and hands it in through
 * {@link ElFaheemBridge#pushFrame}. This class never touches
 * MediaProjectionManager.
 *
 * Rules:
 * - Nothing is sent until real frames arrive, and nothing after stopCapture()
 *   (no synthetic test frames: viewers used to see a green screen).
 * - RGBA to I420 with libyuv (YuvHelper.ABGRToI420: libyuv's "ABGR" is the
 *   byte order R,G,B,A that glReadPixels(GL_RGBA) writes) into pooled
 *   buffers that go back to the pool when the encoder releases the frame.
 * - Frames above the requested frame rate are dropped here as well.
 * - start / stop / push share one lock: once stopCapture() returns, no frame
 *   reaches the observer any more.
 */
public class ElFaheemFrameCapturer implements VideoCapturer {

    private static final String TAG = "ElFaheemFrameCapturer";
    private static final int MAX_POOLED = 4;

    /**
     * "Whichever capturer is active": the app's renderer has no reference to
     * the object GetUserMediaImpl created, so frames are routed through this.
     */
    public static volatile ElFaheemFrameCapturer current;

    private final Object lock = new Object();
    private CapturerObserver observer;
    private boolean running = false;
    private int fps = 15;
    private long lastFrameNs = 0;
    private long pushed = 0;
    private long dropped = 0;

    /** One I420 frame's planes, reused through the pool. */
    private static final class Planes {
        final int width;
        final int height;
        final int strideY;
        final int strideUV;
        final ByteBuffer y;
        final ByteBuffer u;
        final ByteBuffer v;

        Planes(int width, int height) {
            this.width = width;
            this.height = height;
            this.strideY = width;
            this.strideUV = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            this.y = ByteBuffer.allocateDirect(strideY * height);
            this.u = ByteBuffer.allocateDirect(strideUV * chromaHeight);
            this.v = ByteBuffer.allocateDirect(strideUV * chromaHeight);
        }
    }

    private final ConcurrentLinkedQueue<Planes> pool = new ConcurrentLinkedQueue<>();

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                           Context context,
                           CapturerObserver capturerObserver) {
        synchronized (lock) {
            this.observer = capturerObserver;
        }
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        synchronized (lock) {
            fps = Math.max(1, framerate);
            running = true;
            lastFrameNs = 0;
            pushed = 0;
            dropped = 0;
            if (observer != null) observer.onCapturerStarted(true);
        }
        current = this;
        Log.d(TAG, "startCapture " + width + "x" + height + "@" + fps + " (frames come from the app renderer)");
    }

    /**
     * Called by the app's renderer, on its GL thread, for every frame it reads back.
     *
     * @param rgba        tightly packed RGBA8888, top-left origin, w*h*4 bytes, direct
     * @param w           width in pixels
     * @param h           height in pixels
     * @param timestampNs System.nanoTime() of the frame
     */
    public void pushFrame(ByteBuffer rgba, int w, int h, long timestampNs) {
        if (w <= 0 || h <= 0 || rgba == null || !rgba.isDirect() || rgba.capacity() < w * h * 4) return;
        synchronized (lock) {
            if (!running || observer == null) return;
            // 10 % tolerance, so a clock that ticks exactly at fps is not halved.
            long minInterval = 900_000_000L / fps;
            if (lastFrameNs != 0 && timestampNs - lastFrameNs < minInterval) {
                dropped++;
                return;
            }
            lastFrameNs = timestampNs;

            final Planes p = takePlanes(w, h);
            rgba.position(0);
            YuvHelper.ABGRToI420(rgba, w * 4, p.y, p.strideY, p.u, p.strideUV, p.v, p.strideUV, w, h);
            JavaI420Buffer buffer = JavaI420Buffer.wrap(w, h, p.y, p.strideY, p.u, p.strideUV, p.v, p.strideUV,
                    () -> recycle(p));
            VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
            observer.onFrameCaptured(frame);
            frame.release();
            if (++pushed % 300 == 0) {
                Log.d(TAG, "pushed " + pushed + " frames " + w + "x" + h + " (dropped by the fps limit: " + dropped + ")");
            }
        }
    }

    private Planes takePlanes(int w, int h) {
        Planes p;
        while ((p = pool.poll()) != null) {
            if (p.width == w && p.height == h) return p;
            // the size changed (rotation): the old planes are dropped
        }
        return new Planes(w, h);
    }

    private void recycle(Planes p) {
        if (pool.size() < MAX_POOLED) pool.offer(p);
    }

    @Override
    public void stopCapture() {
        synchronized (lock) {
            if (running && observer != null) observer.onCapturerStopped();
            running = false;
        }
        if (current == this) current = null;
        pool.clear();
        Log.d(TAG, "stopCapture after " + pushed + " frames");
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        synchronized (lock) {
            fps = Math.max(1, framerate);
        }
    }

    @Override
    public void dispose() {
        stopCapture();
    }

    @Override
    public boolean isScreencast() {
        return true;
    }
}
