package com.cloudwebrtc.webrtc;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Stage 1 test capturer.
 *
 * Pushes a solid-color frame on a timer instead of reading from
 * MediaProjection. Used to prove that a hand-built VideoCapturer can reach
 * LiveKit through flutter_webrtc's normal getDisplayMedia() publish path,
 * with ZERO dependency on screen capture or MediaProjection permissions.
 *
 * Stage 2 (ElFaheem's ScreenCaptureService.kt work) replaces the internal
 * timer with real frames handed in from the OpenGL fan-out step via a new
 * pushFrame(VideoFrame) method — the VideoCapturer contract itself does not
 * change between Stage 1 and Stage 2, only where the frames come from.
 *
 * Deliberately implements org.webrtc.VideoCapturer directly rather than
 * extending ScreenCapturerAndroid, so nothing in this class can accidentally
 * touch MediaProjectionManager.
 */
public class ElFaheemFrameCapturer implements VideoCapturer {

    private static final String THREAD_NAME = "ElFaheemFrameCapturer";

    /**
     * ScreenCaptureService (native recording, a separate component with no
     * direct object reference to whatever GetUserMediaImpl.createDisplayMediaTrack
     * created) reaches real frames into this capturer through this registry:
     * "whichever ElFaheemFrameCapturer is currently active" rather than a
     * passed-in reference. Set in startCapture(), cleared in stopCapture() —
     * mirrors the @Volatile companion-field pattern already used throughout
     * ScreenCaptureService.kt for this kind of cross-component wiring.
     */
    public static volatile ElFaheemFrameCapturer current;

    private CapturerObserver observer;
    private HandlerThread thread;
    private Handler handler;

    private int width = 1280;
    private int height = 720;
    private int fps = 15;
    private volatile boolean running = false;

    // Stage 2: once ScreenPlaceholderRenderer starts calling pushFrame() with
    // real screen content, the Stage 1 solid-color timer stops generating
    // synthetic frames — the two sources must never race onFrameCaptured().
    private volatile boolean usingExternalFrames = false;

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                            Context context,
                            CapturerObserver capturerObserver) {
        // surfaceTextureHelper/context are unused — Stage 1 synthesizes I420
        // buffers directly, and Stage 2's pushFrame() receives already-
        // converted I420 buffers from ScreenPlaceholderRenderer rather than
        // going through a SurfaceTexture of its own.
        this.observer = capturerObserver;
    }

    @Override
    public void startCapture(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        running = true;
        current = this; // ScreenCaptureService's renderer callback reaches real frames in via this
        if (!usingExternalFrames) {
            thread = new HandlerThread(THREAD_NAME);
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(this::tick);
        }
    }

    /**
     * Stage 2 entry point: ScreenCaptureService's ScreenPlaceholderRenderer
     * calls this on every real screen frame once Share is granted, instead
     * of the Stage 1 timer generating a solid color. Safe to call from any
     * thread — CapturerObserver.onFrameCaptured() is documented as
     * thread-safe in the WebRTC Android SDK.
     *
     * @param rgba   Tightly-packed RGBA8888 bytes, top-left origin, exactly
     *               width*height*4 bytes (as produced by glReadPixels).
     * @param w      Frame width in pixels.
     * @param h      Frame height in pixels.
     * @param timestampNs Frame timestamp (System.nanoTime() at capture).
     */
    public void pushFrame(java.nio.ByteBuffer rgba, int w, int h, long timestampNs) {
        if (!running || observer == null) return;
        usingExternalFrames = true; // stop the Stage 1 timer, if it's still running
        observer.onFrameCaptured(rgbaToI420Frame(rgba, w, h, timestampNs));
    }

    private void tick() {
        if (!running || observer == null || usingExternalFrames) return;
        observer.onFrameCaptured(buildSolidFrame());
        handler.postDelayed(this::tick, 1000L / fps);
    }

    /**
     * Converts a glReadPixels-style RGBA buffer to I420 (BT.601 studio-swing
     * coefficients — matches what MediaCodec/most Android encoders expect).
     * CPU-cost conversion; fine for validating Stage 2 end-to-end, but the
     * first thing to optimize away if frame rate/CPU becomes a problem (see
     * the note in ScreenPlaceholderRenderer.onFrameAvailable()).
     */
    private VideoFrame rgbaToI420Frame(java.nio.ByteBuffer rgba, int w, int h, long timestampNs) {
        JavaI420Buffer buf = JavaI420Buffer.allocate(w, h);
        java.nio.ByteBuffer dstY = buf.getDataY();
        java.nio.ByteBuffer dstU = buf.getDataU();
        java.nio.ByteBuffer dstV = buf.getDataV();
        int strideY = buf.getStrideY();
        int strideU = buf.getStrideU();
        int strideV = buf.getStrideV();

        rgba.rewind();
        byte[] row = new byte[w * 4];
        // GL's glReadPixels origin is bottom-left; encoders expect top-left,
        // so rows are read in reverse to flip vertically during conversion.
        for (int y = h - 1; y >= 0; y--) {
            rgba.position(y * w * 4);
            rgba.get(row, 0, w * 4);
            int dstYRow = (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int r = row[x * 4] & 0xFF;
                int g = row[x * 4 + 1] & 0xFF;
                int b = row[x * 4 + 2] & 0xFF;

                int yVal = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                dstY.put(dstYRow * strideY + x, clamp(yVal));

                if ((dstYRow % 2 == 0) && (x % 2 == 0)) {
                    int uVal = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int vVal = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    dstU.put((dstYRow / 2) * strideU + (x / 2), clamp(uVal));
                    dstV.put((dstYRow / 2) * strideV + (x / 2), clamp(vVal));
                }
            }
        }
        return new VideoFrame(buf, 0, timestampNs);
    }

    private static byte clamp(int v) {
        return (byte) Math.max(0, Math.min(255, v));
    }

    /** Flat green I420 buffer: Y=120 (mid-green luma), U/V=128 (neutral chroma). */
    private VideoFrame buildSolidFrame() {
        JavaI420Buffer buf = JavaI420Buffer.allocate(width, height);
        fill(buf.getDataY(), buf.getStrideY(), height, (byte) 120);
        fill(buf.getDataU(), buf.getStrideU(), (height + 1) / 2, (byte) 128);
        fill(buf.getDataV(), buf.getStrideV(), (height + 1) / 2, (byte) 128);
        return new VideoFrame(buf, /* rotation= */ 0, System.nanoTime());
    }

    private void fill(ByteBuffer b, int stride, int rows, byte value) {
        byte[] row = new byte[stride];
        Arrays.fill(row, value);
        b.rewind();
        for (int i = 0; i < rows; i++) {
            b.put(row);
        }
        b.rewind();
    }

    @Override
    public void stopCapture() {
        running = false;
        if (current == this) {
            current = null; // only clear if we're still the active one (defensive against races)
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, framerate);
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
