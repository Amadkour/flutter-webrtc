package com.cloudwebrtc.webrtc.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Process;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.nio.ShortBuffer;

/**
 * Device audio for a screen share (API 29+): an AudioRecord on
 * AudioPlaybackCaptureConfiguration from the share's own MediaProjection,
 * usages MEDIA, GAME and UNKNOWN, with THIS app's uid excluded, so the
 * remote voices the app plays (and anything else it plays) are never
 * captured again: no echo loop. Call audio (VOICE_COMMUNICATION) is never
 * capturable on Android anyway, and apps that opt out of playback capture
 * (allowAudioPlaybackCapture=false) are silent here.
 *
 * Captured as 48 kHz stereo 16-bit into a 500 ms ring buffer. The mic
 * thread pulls from it in {@link #mixInto}: resampled (linear, with its
 * phase carried across buffers) to the mic's rate, down / up-mixed to the
 * mic's channel count, added to the mic samples and soft-limited.
 * Latency stays bounded: it waits for 20 ms before the first pull and
 * drops back to 60 ms when more than 150 ms piled up (clock drift).
 */
@RequiresApi(29)
final class ElFaheemScreenAudio {

    private static final String TAG = "ElFaheemScreenAudio";
    static final int RATE = 48000;
    static final int CHANNELS = 2;
    private static final int RING_FRAMES = RATE / 2;          // 500 ms
    private static final int PRIME_FRAMES = RATE / 50;        // 20 ms
    private static final int HIGH_WATER_FRAMES = RATE * 15 / 100; // 150 ms
    private static final int TRIM_TO_FRAMES = RATE * 6 / 100; // 60 ms
    /** The shared audio is full-scale media; a little under 1 leaves the voice room. */
    private static final float SCREEN_GAIN = 0.8f;
    private static final float KNEE = 24576f;
    private static final float HEADROOM = 32767f - KNEE;

    private final AudioRecord record;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean sawData = false;
    private volatile String error = null;

    // Ring buffer of interleaved stereo frames; guarded by `this`.
    private final short[] ring = new short[RING_FRAMES * CHANNELS];
    private int readFrame = 0;
    private int filledFrames = 0;
    private boolean primed = false;

    // Resampler state; only touched on the mic thread (inside mixInto, under `this`).
    private double phase = 1.0;
    private float prevL, prevR, curL, curR;

    // Stats for the log.
    private long capturedFrames = 0;
    private long underruns = 0;
    private long trimmed = 0;
    private int peak = 0;
    private long lastStatsMs = 0;

    private ElFaheemScreenAudio(AudioRecord record) {
        this.record = record;
        this.thread = new Thread(this::captureLoop, "ElFaheemScreenAudio");
        this.thread.setPriority(Thread.MAX_PRIORITY);
    }

    /** Throws (SecurityException without RECORD_AUDIO, IllegalStateException ...) when it cannot start. */
    static ElFaheemScreenAudio start(MediaProjection projection) {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min, RATE * CHANNELS * 2 / 10); // at least 100 ms
        AudioRecord record = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setAudioPlaybackCaptureConfig(config)
                .build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("AudioRecord not initialized");
        }
        ElFaheemScreenAudio audio = new ElFaheemScreenAudio(record);
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            throw new IllegalStateException("AudioRecord did not start");
        }
        audio.thread.start();
        Log.i(TAG, "device audio capture started: " + RATE + " Hz stereo, buffer " + bytes
                + " B, usages MEDIA|GAME|UNKNOWN, own uid " + Process.myUid() + " excluded");
        return audio;
    }

    void stop() {
        running = false;
        try {
            record.stop();
        } catch (Exception ignored) {
        }
        try {
            thread.join(500);
        } catch (InterruptedException ignored) {
        }
        record.release();
        Log.i(TAG, "device audio capture stopped: " + capturedFrames + " frames captured, "
                + underruns + " underruns, " + trimmed + " frames trimmed");
    }

    String state() {
        if (error != null) return "failed: " + error;
        if (!running) return "off";
        return sawData ? "capturing" : "starting";
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final short[] chunk = new short[RATE / 100 * CHANNELS]; // 10 ms
        while (running) {
            int n;
            try {
                n = record.read(chunk, 0, chunk.length);
            } catch (Exception e) {
                error = e.getMessage();
                Log.e(TAG, "read failed: " + e.getMessage());
                break;
            }
            if (n < 0) {
                if (running) {
                    error = "read error " + n;
                    Log.e(TAG, "read error " + n);
                }
                break;
            }
            if (n == 0) continue;
            write(chunk, n / CHANNELS);
        }
    }

    private synchronized void write(short[] src, int frames) {
        if (!sawData) {
            sawData = true;
            Log.i(TAG, "first device audio data");
        }
        capturedFrames += frames;
        for (int i = 0; i < frames * CHANNELS; i++) {
            int a = Math.abs(src[i]);
            if (a > peak) peak = a;
        }
        if (filledFrames + frames > RING_FRAMES) {
            // Nobody pulled (the mic thread is not running): keep the newest.
            int overflow = filledFrames + frames - RING_FRAMES;
            readFrame = (readFrame + overflow) % RING_FRAMES;
            filledFrames -= overflow;
        }
        int writeFrame = (readFrame + filledFrames) % RING_FRAMES;
        for (int f = 0; f < frames; f++) {
            int dst = ((writeFrame + f) % RING_FRAMES) * CHANNELS;
            ring[dst] = src[f * CHANNELS];
            ring[dst + 1] = src[f * CHANNELS + 1];
        }
        filledFrames += frames;
        long now = System.currentTimeMillis();
        if (now - lastStatsMs > 10_000) {
            lastStatsMs = now;
            Log.d(TAG, "device audio: " + capturedFrames + " frames, buffered " + (filledFrames * 1000 / RATE)
                    + " ms, peak " + peak + ", underruns " + underruns + ", trimmed " + trimmed);
            peak = 0;
        }
    }

    /**
     * Adds the shared audio to the mic buffer in place (16-bit interleaved,
     * `channels` channels at `rate` Hz). With `zeroMic` the mic samples are
     * replaced instead of added to.
     */
    synchronized void mixInto(ShortBuffer dst, int frames, int channels, int rate, boolean zeroMic) {
        if (frames <= 0 || channels <= 0 || rate <= 0) return;
        final double step = (double) RATE / rate;

        if (filledFrames > HIGH_WATER_FRAMES) {
            int drop = filledFrames - TRIM_TO_FRAMES;
            readFrame = (readFrame + drop) % RING_FRAMES;
            filledFrames -= drop;
            trimmed += drop;
        }
        if (!primed && filledFrames >= PRIME_FRAMES) primed = true;
        // Source frames this buffer will consume.
        int need = (int) Math.floor(phase + (frames - 1) * step);
        boolean haveScreen = primed && filledFrames >= need;
        if (primed && !haveScreen) {
            underruns++;
            primed = false;
        }
        if (!haveScreen && !zeroMic) return; // the mic buffer stays as it is

        for (int f = 0; f < frames; f++) {
            float l = 0f;
            float r = 0f;
            if (haveScreen) {
                while (phase >= 1.0) {
                    prevL = curL;
                    prevR = curR;
                    int src = readFrame * CHANNELS;
                    curL = ring[src];
                    curR = ring[src + 1];
                    readFrame = (readFrame + 1) % RING_FRAMES;
                    filledFrames--;
                    phase -= 1.0;
                }
                float t = (float) phase;
                l = (prevL + (curL - prevL) * t) * SCREEN_GAIN;
                r = (prevR + (curR - prevR) * t) * SCREEN_GAIN;
                phase += step;
            }
            int base = f * channels;
            if (channels == 1) {
                float mic = zeroMic ? 0f : dst.get(base);
                dst.put(base, limit(mic + (l + r) * 0.5f));
            } else {
                float micL = zeroMic ? 0f : dst.get(base);
                float micR = zeroMic ? 0f : dst.get(base + 1);
                dst.put(base, limit(micL + l));
                dst.put(base + 1, limit(micR + r));
                for (int c = 2; c < channels; c++) {
                    if (zeroMic) dst.put(base + c, (short) 0);
                }
            }
        }
    }

    /** Soft limiter: linear below the knee, then a tanh shoulder that never passes full scale. */
    static short limit(float x) {
        float a = Math.abs(x);
        if (a <= KNEE) return (short) Math.round(x);
        float over = (float) Math.tanh((a - KNEE) / HEADROOM) * HEADROOM;
        float y = KNEE + over;
        return (short) Math.round(x < 0 ? -y : y);
    }
}
