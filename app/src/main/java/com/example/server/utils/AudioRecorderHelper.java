package com.example.server.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class AudioRecorderHelper {
    private static final String TAG = "AudioRecorderHelper";

    // 錄音設定 (與原本 MainActivity 一致)
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final AudioCallback callback;
    private boolean isRecording = false;
    private AudioRecord audioRecord;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 定義接口，讓 Activity 可以接收結果
    public interface AudioCallback {
        void onVolumeChanged(double volumePercent); // 傳回 0.0 ~ 1.0 的音量百分比
        void onRecordingFinished(byte[] wavData);  // 傳回處理好的 WAV 位元組
    }

    public AudioRecorderHelper(Context context, AudioCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 開始錄音
     * @param durationMs 錄音持續時間 (毫秒)
     */
    public void startRecording(int durationMs) {
        if (isRecording) return;

        // 檢查權限
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "無錄音權限");
            return;
        }

        isRecording = true;
        new Thread(() -> {
            try {
                int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize);

                audioRecord.startRecording();
                Log.d(TAG, "開始錄製 PCM...");

                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[minBufferSize];
                long startTime = System.currentTimeMillis();

                // 錄音迴圈
                while (isRecording && (System.currentTimeMillis() - startTime < durationMs)) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmStream.write(buffer, 0, read);
                        calculateVolume(buffer, read); // 計算音量
                    }
                }

                // 結束錄製
                stopAndRelease();

                // 轉換為 WAV 並回傳
                byte[] pcmData = pcmStream.toByteArray();
                byte[] wavData = addWavHeader(pcmData);

                mainHandler.post(() -> callback.onRecordingFinished(wavData));

            } catch (Exception e) {
                Log.e(TAG, "錄音執行緒錯誤: " + e.getMessage());
                isRecording = false;
            }
        }).start();
    }

    public void stopRecording() {
        isRecording = false;
    }

    private void stopAndRelease() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
    }

    /**
     * 計算音量分貝並映射為百分比
     */
    private void calculateVolume(byte[] buffer, int readSize) {
        long sum = 0;
        for (int i = 0; i < readSize / 2; i++) {
            short pcmShort = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
            sum += pcmShort * pcmShort;
        }
        double rms = Math.sqrt(sum / (readSize / 2.0));
        // 映射原本邏輯: 30dB ~ 80dB -> 0.0 ~ 1.0
        double volumeDb = 20 * Math.log10(rms > 1 ? rms : 1);
        double volumePercent = Math.min(Math.max(volumeDb - 30, 0), 50) / 50.0;

        mainHandler.post(() -> callback.onVolumeChanged(volumePercent));
    }

    /**
     * 為 PCM 資料添加 WAV 標頭 (原本 MainActivity 的 addWavHeader 邏輯)
     */
    private byte[] addWavHeader(byte[] pcmData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        int totalDataLen = pcmData.length + 36;
        int byteRate = SAMPLE_RATE * 2; // 16-bit mono = sampleRate * (bitsPerSample/8) * channels

        dos.writeBytes("RIFF");
        dos.writeInt(Integer.reverseBytes(totalDataLen));
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt ");
        dos.writeInt(Integer.reverseBytes(16));
        dos.writeShort(Short.reverseBytes((short) 1)); // PCM
        dos.writeShort(Short.reverseBytes((short) 1)); // Mono
        dos.writeInt(Integer.reverseBytes(SAMPLE_RATE));
        dos.writeInt(Integer.reverseBytes(byteRate));
        dos.writeShort(Short.reverseBytes((short) 2)); // Block align
        dos.writeShort(Short.reverseBytes((short) 16)); // Bits per sample
        dos.writeBytes("data");
        dos.writeInt(Integer.reverseBytes(pcmData.length));
        dos.write(pcmData);

        return baos.toByteArray();
    }
}