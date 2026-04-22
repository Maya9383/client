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

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final AudioCallback callback;
    private boolean isRecording = false;
    private AudioRecord audioRecord;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface AudioCallback {
        void onVolumeChanged(double volumePercent);
        void onRecordingFinished(byte[] wavData);
    }

    public AudioRecorderHelper(Context context, AudioCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void startRecording(int durationMs) {
        Log.i(TAG, "🎙️ 收到錄音請求，預計錄製 " + durationMs + " 毫秒...");

        if (isRecording) {
            Log.d(TAG, "⚠️ 目前已經在錄音中，忽略重複的錄音請求。");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ 錄音失敗: 麥克風權限未開啟！");
            return;
        }

        isRecording = true;

        new Thread(() -> {
            try {
                Log.d(TAG, "⚙️ 嘗試初始化 AudioRecord 硬體...");
                int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize);

                // 🌟 這裡是最容易因為衝突而報錯的地方
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "❌ AudioRecord 初始化失敗！(麥克風極可能正被其他程式或系統喚醒服務佔用中)");
                    isRecording = false;
                    return;
                }

                Log.i(TAG, "🔴 錄音硬體啟動成功，開始擷取聲音資料...");
                audioRecord.startRecording();

                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[minBufferSize];
                long startTime = System.currentTimeMillis();

                // 錄音迴圈 (為了不洗版，這裡不印 Log，靠 UI 更新即可)
                while (isRecording && (System.currentTimeMillis() - startTime < durationMs)) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        pcmStream.write(buffer, 0, read);
                        calculateVolume(buffer, read);
                    }
                }

                Log.i(TAG, "⏹️ 錄音時間到達或被主動中斷，準備停止硬體擷取...");
                stopAndRelease();

                byte[] pcmData = pcmStream.toByteArray();
                byte[] wavData = addWavHeader(pcmData);

                Log.i(TAG, "📦 錄音結束，成功打包 WAV 音訊資料，大小: " + wavData.length + " bytes，準備回傳給 Server");
                mainHandler.post(() -> callback.onRecordingFinished(wavData));

            } catch (Exception e) {
                Log.e(TAG, "❌ 錄音過程中發生例外錯誤", e);
                isRecording = false;
            }
        }).start();
    }

    public void stopRecording() {
        if (isRecording) {
            Log.i(TAG, "🛑 收到強制停止錄音指令");
            isRecording = false; // 這會讓 Thread 裡的 while 迴圈自動結束
        }
    }

    private void stopAndRelease() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                Log.d(TAG, "🧹 釋放 AudioRecord 麥克風硬體資源...");
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "❌ 釋放麥克風資源時發生錯誤", e);
            } finally {
                audioRecord = null;
            }
        }
    }

    private void calculateVolume(byte[] buffer, int readSize) {
        long sum = 0;
        for (int i = 0; i < readSize / 2; i++) {
            short pcmShort = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
            sum += pcmShort * pcmShort;
        }
        double rms = Math.sqrt(sum / (readSize / 2.0));
        double volumeDb = 20 * Math.log10(rms > 1 ? rms : 1);
        double volumePercent = Math.min(Math.max(volumeDb - 30, 0), 50) / 50.0;

        mainHandler.post(() -> callback.onVolumeChanged(volumePercent));
    }

    private byte[] addWavHeader(byte[] pcmData) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        int totalDataLen = pcmData.length + 36;
        int byteRate = SAMPLE_RATE * 2;

        dos.writeBytes("RIFF");
        dos.writeInt(Integer.reverseBytes(totalDataLen));
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt ");
        dos.writeInt(Integer.reverseBytes(16));
        dos.writeShort(Short.reverseBytes((short) 1));
        dos.writeShort(Short.reverseBytes((short) 1));
        dos.writeInt(Integer.reverseBytes(SAMPLE_RATE));
        dos.writeInt(Integer.reverseBytes(byteRate));
        dos.writeShort(Short.reverseBytes((short) 2));
        dos.writeShort(Short.reverseBytes((short) 16));
        dos.writeBytes("data");
        dos.writeInt(Integer.reverseBytes(pcmData.length));
        dos.write(pcmData);

        return baos.toByteArray();
    }
}