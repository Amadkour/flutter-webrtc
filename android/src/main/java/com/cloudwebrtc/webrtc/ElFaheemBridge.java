package com.cloudwebrtc.webrtc;

import java.nio.ByteBuffer;

/**
 * Plain (no WebRTC-typed supertype) facade in front of ElFaheemFrameCapturer,
 * callable from the app module (ScreenCaptureService.kt) without requiring
 * org.webrtc.VideoCapturer to be resolvable on the app module's own
 * classpath.
 *
 * Why this exists: ElFaheemFrameCapturer implements org.webrtc.VideoCapturer,
 * which lives in the WebRTC AAR — an `implementation`-scoped dependency
 * INSIDE the flutter_webrtc plugin module, not exposed transitively to the
 * app module. Kotlin requires a class's full supertype chain to be
 * classpath-resolvable for ANY reference to that class, even one that never
 * mentions the supertype directly (error: "Cannot access 'VideoCapturer'
 * which is a supertype of 'ElFaheemFrameCapturer'"). This class's own
 * supertype is just Object (implicit), so referencing IT from the app
 * module has no such requirement — only java.nio.ByteBuffer and primitives
 * cross the module boundary.
 */
public class ElFaheemBridge {
    public static void pushFrame(ByteBuffer rgba, int width, int height, long timestampNs) {
        ElFaheemFrameCapturer capturer = ElFaheemFrameCapturer.current;
        if (capturer != null) {
            capturer.pushFrame(rgba, width, height, timestampNs);
        }
    }
}
