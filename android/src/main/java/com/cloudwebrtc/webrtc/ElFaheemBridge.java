package com.cloudwebrtc.webrtc;

import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.os.Build;

import com.cloudwebrtc.webrtc.audio.ElFaheemAudioMixer;

import java.nio.ByteBuffer;

/**
 * Plain (no WebRTC-typed supertype) facade in front of the ElFaheem pieces,
 * callable from the app module (ScreenCaptureService.kt, MainActivity.kt)
 * without org.webrtc on the app module's classpath.
 *
 * Why this exists: ElFaheemFrameCapturer implements org.webrtc.VideoCapturer,
 * which lives in the WebRTC AAR, an `implementation` dependency INSIDE the
 * flutter_webrtc plugin module that is not exposed to the app module. Kotlin
 * needs a class's whole supertype chain on the classpath for ANY reference
 * to it ("Cannot access 'VideoCapturer' which is a supertype of ..."). This
 * class's signatures only use java / android types.
 *
 * Three things cross here:
 * - video: {@link #pushFrame} (the app's renderer → the active capturer);
 * - device audio during a share (API 29+): {@link #startScreenAudio} captures
 *   other apps' playback from the share's MediaProjection and mixes it into
 *   the outgoing microphone buffer; {@link #setMicSoftMute} zeroes the mic
 *   samples while the track keeps publishing (see ElFaheemAudioMixer);
 * - audio focus: AudioSwitchManager's focus listener forwards to
 *   {@link #audioFocusListener} (it was a no-op).
 */
public class ElFaheemBridge {

    public static void pushFrame(ByteBuffer rgba, int width, int height, long timestampNs) {
        ElFaheemFrameCapturer capturer = ElFaheemFrameCapturer.current;
        if (capturer != null) {
            capturer.pushFrame(rgba, width, height, timestampNs);
        }
    }

    /** True while a share track's capturer is running (frames pushed now are used). */
    public static boolean hasCapturer() {
        return ElFaheemFrameCapturer.current != null;
    }

    /** Whether this device can capture other apps' audio at all (Android 10 / API 29+). */
    public static boolean screenAudioSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /**
     * Starts capturing device audio from the share's projection and mixing it
     * into the microphone track. Returns the state: "started", "unsupported"
     * (below API 29), "no_permission" (RECORD_AUDIO) or "failed: ...".
     */
    public static String startScreenAudio(MediaProjection projection) {
        return ElFaheemAudioMixer.INSTANCE.startScreenAudio(projection);
    }

    public static void stopScreenAudio() {
        ElFaheemAudioMixer.INSTANCE.stopScreenAudio();
    }

    /** "off", "starting", "capturing" (data seen) or "failed: ...". */
    public static String screenAudioState() {
        return ElFaheemAudioMixer.INSTANCE.screenAudioState();
    }

    /** Zero the microphone's samples (the track keeps publishing, the shared audio still goes out). */
    public static void setMicSoftMute(boolean muted) {
        ElFaheemAudioMixer.INSTANCE.setMicSoftMuted(muted);
    }

    public static boolean isMicSoftMuted() {
        return ElFaheemAudioMixer.INSTANCE.isMicSoftMuted();
    }

    /** Set by the app; AudioSwitchManager's focus listener forwards every change here. */
    public static volatile AudioManager.OnAudioFocusChangeListener audioFocusListener;

    public static void dispatchAudioFocusChange(int focusChange) {
        AudioManager.OnAudioFocusChangeListener l = audioFocusListener;
        if (l != null) l.onAudioFocusChange(focusChange);
    }
}
