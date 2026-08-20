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

    private CapturerObserver observer;
    private HandlerThread thread;
    private Handler handler;

    private int width = 1280;
    private int height = 720;
    private int fps = 15;
    private volatile boolean running = false;

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                            Context context,
                            CapturerObserver capturerObserver) {
        // surfaceTextureHelper/context are unused in Stage 1 — frames are
        // synthesized directly as I420 buffers, no GL/EGL surface needed
        // yet. Stage 2 will use surfaceTextureHelper when frames start
        // coming from ScreenCaptureService's OpenGL step instead.
        this.observer = capturerObserver;
    }

    @Override
    public void startCapture(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        running = true;

        thread = new HandlerThread(THREAD_NAME);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    private void tick() {
        if (!running || observer == null) return;
        observer.onFrameCaptured(buildSolidFrame());
        handler.postDelayed(this::tick, 1000L / fps);
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
