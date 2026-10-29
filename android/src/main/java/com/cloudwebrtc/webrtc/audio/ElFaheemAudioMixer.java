package com.cloudwebrtc.webrtc.audio;

import android.media.AudioFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import org.webrtc.audio.JavaAudioDeviceModule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * The microphone buffer hook (JavaAudioDeviceModule.AudioBufferCallback,
 * set in MethodCallHandlerImpl when the audio device module is built).
 * WebRtcAudioRecord calls it on its record thread after every
 * AudioRecord.read() and BEFORE the buffer goes to native, so whatever is
 * written here is what WebRTC's audio processing and encoder get. This is
 * how LiveKit's native Android SDK mixes screen audio as well.
 *
 * Two jobs, both no-ops (one volatile read each) when idle:
 * - mix the share's device audio (ElFaheemScreenAudio) into the mic samples;
 * - the mic "soft mute": zero the mic samples while the track keeps
 *   publishing, so the shared audio still reaches viewers when the presenter
 *   mutes the mic during a share. A LiveKit mute cannot be used for that: it
 *   disables (and by default stops) the mic track, which silences the
 *   shared audio riding on it too.
 *
 * Echo: the mic part arrives here already processed by the platform's
 * hardware AEC / NS when the module uses them (the default on API 29+); the
 * shared audio is added afterwards and is not in WebRTC's far-end reference,
 * so software AEC (devices without hardware AEC) leaves it alone. The app's
 * own playback (the remote voices) is excluded from the capture by uid.
 */
public final class ElFaheemAudioMixer implements JavaAudioDeviceModule.AudioBufferCallback {

    private static final String TAG = "ElFaheemAudioMixer";
    public static final ElFaheemAudioMixer INSTANCE = new ElFaheemAudioMixer();

    private volatile boolean micSoftMuted = false;
    private volatile Object screen = null; // ElFaheemScreenAudio (typed Object: the class needs API 29)
    private volatile String lastStartError = null;

    // Cached 16-bit view of WebRtcAudioRecord's byte buffer (the same object every call).
    private ByteBuffer lastBuffer;
    private ShortBuffer shorts;
    private boolean warnedFormat = false;
    private long mixedBuffers = 0;

    private ElFaheemAudioMixer() {}

    public void setMicSoftMuted(boolean muted) {
        if (micSoftMuted != muted) Log.i(TAG, "mic soft mute " + (muted ? "ON" : "OFF"));
        micSoftMuted = muted;
    }

    public boolean isMicSoftMuted() {
        return micSoftMuted;
    }

    public synchronized String startScreenAudio(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported";
        if (projection == null) return "failed: no projection";
        if (screen != null) return "started";
        try {
            screen = ElFaheemScreenAudio.start(projection);
            lastStartError = null;
            mixedBuffers = 0;
            return "started";
        } catch (SecurityException e) {
            lastStartError = "no_permission";
            Log.e(TAG, "device audio: RECORD_AUDIO missing: " + e.getMessage());
            return "no_permission";
        } catch (Exception e) {
            lastStartError = "failed: " + e.getMessage();
            Log.e(TAG, "device audio could not start: " + e.getMessage(), e);
            return lastStartError;
        }
    }

    public synchronized void stopScreenAudio() {
        Object s = screen;
        screen = null;
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ((ElFaheemScreenAudio) s).stop();
            Log.i(TAG, "device audio stopped after " + mixedBuffers + " mixed mic buffers");
        }
    }

    public String screenAudioState() {
        Object s = screen;
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return ((ElFaheemScreenAudio) s).state();
        return lastStartError != null ? lastStartError : "off";
    }

    @Override
    public long onBuffer(ByteBuffer buffer, int audioFormat, int channelCount, int sampleRate,
                         int bytesRead, long captureTimeNs) {
        final Object s = screen;
        final boolean mute = micSoftMuted;
        if ((s == null && !mute) || buffer == null || channelCount <= 0) return captureTimeNs;

        if (audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            if (!warnedFormat) {
                warnedFormat = true;
                Log.w(TAG, "mic format " + audioFormat + " is not 16-bit PCM: device audio is not mixed");
            }
            if (mute) zero(buffer, bytesRead);
            return captureTimeNs;
        }
        if (buffer != lastBuffer) {
            lastBuffer = buffer;
            ByteBuffer view = buffer.duplicate();
            view.clear();
            shorts = view.order(ByteOrder.nativeOrder()).asShortBuffer();
        }
        int frames = Math.min(bytesRead, buffer.capacity()) / (2 * channelCount);
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            for (int i = 0; i < frames * channelCount; i++) shorts.put(i, (short) 0);
            return captureTimeNs;
        }
        ((ElFaheemScreenAudio) s).mixInto(shorts, frames, channelCount, sampleRate, mute);
        if (++mixedBuffers == 1) {
            Log.i(TAG, "mixing device audio into the mic: " + sampleRate + " Hz, " + channelCount + " ch, "
                    + frames + " frames per buffer");
        }
        return captureTimeNs;
    }

    private static void zero(ByteBuffer buffer, int bytes) {
        int n = Math.min(bytes, buffer.capacity());
        for (int i = 0; i < n; i++) buffer.put(i, (byte) 0);
    }
}
