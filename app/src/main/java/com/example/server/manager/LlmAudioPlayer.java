package com.example.server.manager;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

public class LlmAudioPlayer {
    private static final String TAG = "LlmAudioPlayer";
    private final Context context;
    private MediaPlayer mediaPlayer;
    private final ConcurrentLinkedQueue<File> audioQueue = new ConcurrentLinkedQueue<>();
    private boolean isPlaying = false;
    private final Runnable onAllFinished;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable finishRunnable;

    public LlmAudioPlayer(Context context, Runnable onAllFinished) {
        this.context = context;
        this.onAllFinished = onAllFinished;
    }

    public void addToQueue(byte[] audioData) {
        if (audioData == null || audioData.length == 0) return;

        try {
            if (finishRunnable != null) {
                mainHandler.removeCallbacks(finishRunnable);
                finishRunnable = null;
            }

            File tempFile = File.createTempFile("ai_voice_", ".wav", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            audioQueue.offer(tempFile);
            Log.i(TAG, "📥 收到 Server 語音封包，大小: " + audioData.length + " bytes，已放入播放佇列 (剩餘: " + audioQueue.size() + ")");

            if (!isPlaying) {
                playNext();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 儲存音訊暫存檔失敗", e);
        }
    }

    private void playNext() {
        File nextFile = audioQueue.poll();

        if (nextFile == null) {
            Log.d(TAG, "⏳ 播放佇列已空，啟動 1.5 秒防斷線緩衝計時器...");
            finishRunnable = () -> {
                Log.i(TAG, "✅ 1.5 秒內無新語音，判定當前播報完全結束。");
                isPlaying = false;
                if (onAllFinished != null) {
                    onAllFinished.run();
                }
            };
            mainHandler.postDelayed(finishRunnable, 1500);
            return;
        }

        isPlaying = true;
        try {
            Log.i(TAG, "▶️ 開始播放 AI 語音檔案...");
            if (mediaPlayer == null) mediaPlayer = new MediaPlayer();
            mediaPlayer.reset();
            mediaPlayer.setDataSource(nextFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "⏩ 單段語音播放完畢，準備播放下一段...");
                if (nextFile.exists()) nextFile.delete();
                playNext();
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ 播放檔案發生例外錯誤", e);
            if (nextFile.exists()) nextFile.delete();
            playNext();
        }
    }

    public void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            Log.i(TAG, "⏸️ 暫停語音播放");
            mediaPlayer.pause();
        }
    }

    public void resumeAudio() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            Log.i(TAG, "▶️ 恢復語音播放");
            mediaPlayer.start();
        } else if (!isPlaying && !audioQueue.isEmpty()) {
            playNext();
        }
    }

    public void stopAll() {
        Log.i(TAG, "🛑 強制停止所有語音播放並清空佇列");
        isPlaying = false;
        if (finishRunnable != null) {
            mainHandler.removeCallbacks(finishRunnable);
            finishRunnable = null;
        }
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "❌ 釋放 MediaPlayer 失敗", e);
            }
            mediaPlayer = null;
        }
        while (!audioQueue.isEmpty()) {
            File f = audioQueue.poll();
            if (f != null && f.exists()) f.delete();
        }
    }

    public boolean isPlaying() {
        return (mediaPlayer != null && mediaPlayer.isPlaying());
    }
}