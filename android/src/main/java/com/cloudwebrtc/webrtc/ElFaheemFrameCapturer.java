package com.cloudwebrtc.webrtc;

import androidx.annotation.Nullable;
import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Stage 1 test capturer: pushes a solid-color frame on a timer instead of
 * reading from MediaProjection. Validates that a hand-built VideoCapturer
 * can reach LiveKit through flutter_webrtc's normal publish path, with zero
 * dependency on screen capture. Stage 2 replaces the timer with real frames
 * handed in from ScreenCaptureService's OpenGL step via pushFrame().
 */
public class ElFaheemFrameCapturer implements VideoCapturer {
    private CapturerObserver observer;
    private HandlerThread thread;
    private Handler handler;
    private int width = 1280, height = 720, fps = 15;
    private volatile boolean running = false;

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper,
                           Context context, CapturerObserver capturerObserver) {
        this.observer = capturerObserver;
    }

    @Override
    public void startCapture(int width, int height, int fps) {
        this.width = width; this.height = height; this.fps = fps;
        running = true;
        thread = new HandlerThread("ElFaheemFrameCapturer");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    private void tick() {
        if (!running) return;
        observer.onFrameCaptured(buildSolidFrame());
        handler.postDelayed(this::tick, 1000L / fps);
    }

    private VideoFrame buildSolidFrame() {
        // Flat green I420 buffer — Y=color, U/V=neutral (128).
        JavaI420Buffer buf = JavaI420Buffer.allocate(width, height);
        fill(buf.getDataY(), buf.getStrideY(), height, (byte) 120);
        fill(buf.getDataU(), buf.getStrideU(), height / 2, (byte) 128);
        fill(buf.getDataV(), buf.getStrideV(), height / 2, (byte) 128);
        return new VideoFrame(buf, 0, System.nanoTime());
    }

    private void fill(java.nio.ByteBuffer b, int stride, int rows, byte v) {
        byte[] row = new byte[stride];
        java.util.Arrays.fill(row, v);
        for (int i = 0; i < rows; i++) b.put(row);
        b.rewind();
    }

    @Override public void stopCapture() {
        running = false;
        if (thread != null) thread.quitSafely();
    }
    @Override public void changeCaptureFormat(int w, int h, int f) { width = w; height = h; fps = f; }
    @Override public void dispose() { stopCapture(); }
    @Override public boolean isScreencast() { return true; }
}