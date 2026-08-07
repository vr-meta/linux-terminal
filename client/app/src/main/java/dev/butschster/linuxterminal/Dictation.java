package dev.butschster.linuxterminal;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Records speech. Nothing more.
 *
 * <p>Recognition happens on the server, over the connection that already exists. That
 * is not a detail of where the code sits: an API key shipped to a headset is a key on a
 * device you carry into other people's houses, and a second endpoint would mean a second
 * port and a second firewall rule. Here the audio rides the session and the key stays on
 * the machine that owns it.
 */
public class Dictation {

    private static final String TAG = "linux-terminal";

    /** What Whisper resamples to anyway, so recording here is lossless for it. */
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;

    private static final long MAX_MS = 5 * 60 * 1000;

    public interface Listener {
        void onLevel(float level);

        void onElapsed(long millis);

        /** Recording finished: here are the samples, send them somewhere. */
        void onAudio(byte[] pcm);

        /** Nothing usable was recorded, and why. */
        void onFailed(String problem);
    }

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean recording;
    private long startedAt;

    public Dictation(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    public void start() {
        if (recording) return;
        recording = true;
        startedAt = System.currentTimeMillis();
        new Thread(this::record, "dictation").start();
        tick();
    }

    public void stop() {
        recording = false;
    }

    private void tick() {
        if (!recording) return;
        main.post(() -> listener.onElapsed(System.currentTimeMillis() - startedAt));
        main.postDelayed(this::tick, 500);
    }

    private void record() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            fail("microphone unavailable");
            return;
        }

        AudioRecord recorder;
        try {
            // VOICE_RECOGNITION rather than MIC: noise handling meant for speech.
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
        } catch (SecurityException e) {
            fail("no permission to record");
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            fail("recorder did not initialise");
            return;
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[minBuffer];
        recorder.startRecording();
        while (recording) {
            int n = recorder.read(buffer, 0, buffer.length);
            if (n > 0) {
                captured.write(buffer, 0, n);
                float level = rms(buffer, n);
                main.post(() -> listener.onLevel(level));
            }
            if (System.currentTimeMillis() - startedAt > MAX_MS) {
                Log.i(TAG, "dictation hit the five minute limit");
                recording = false;
            }
        }
        recorder.stop();
        recorder.release();

        byte[] pcm = captured.toByteArray();
        Log.i(TAG, "captured " + (pcm.length / (SAMPLE_RATE * 2.0)) + "s");
        if (pcm.length == 0) {
            fail("nothing recorded");
            return;
        }
        main.post(() -> listener.onAudio(pcm));
    }

    private void fail(String problem) {
        recording = false;
        main.post(() -> listener.onFailed(problem));
    }

    /** Root mean square of a 16-bit little-endian block, normalised to 0..1. */
    private static float rms(byte[] pcm, int length) {
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
            samples++;
        }
        if (samples == 0) return 0f;
        return (float) (Math.sqrt(sum / (double) samples) / 32768.0);
    }
}
