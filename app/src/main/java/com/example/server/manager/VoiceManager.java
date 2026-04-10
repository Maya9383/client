package com.example.server.manager;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

public class VoiceManager {
    private static final String TAG = "VoiceManager";
    private SpeechRecognizer recognizer;
    private final Intent intent;
    private final Runnable onWakeWordDetected;
    private boolean isListening = false;

    private AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VoiceManager(Context context, Runnable onWakeWordDetected) {
        this.onWakeWordDetected = onWakeWordDetected;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        // 🌟 減少等待時間，讓辨識更輕量
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "語音就緒");
                // ❌ 移除了延遲解除靜音，保持全域靜音防護罩
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    for (String text : matches) {
                        // 🌟 增加模糊匹配：納入諧音或辨識常見誤判字
                        if (text.contains("小美") ||
                                text.contains("小梅") ||
                                text.contains("小米") ||
                                text.contains("小門") ||
                                text.contains("校門") ||
                                text.contains("小名")) {

                            Log.d(TAG, "🎯 喚醒成功 (辨識到: " + text + ")");
                            if (onWakeWordDetected != null) onWakeWordDetected.run();
                            return;
                        }
                    }
                }
                scheduleRestart();
            }

            @Override
            public void onError(int error) {
                Log.e(TAG, "辨識錯誤碼: " + error);
                // ❌ 移除了報錯時的 setMute(false)，維持安靜
                scheduleRestart(); // 延遲重啟，避免死循環
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    // 🌟 延遲重啟，強制休息 1 秒再聽
    private void scheduleRestart() {
        if (!isListening) return;
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> {
            if (isListening) restart();
        }, 1000);
    }

    public void startListeningWakeWord() {
        Log.d(TAG, "開始監聽喚醒詞...");
        isListening = true;
        setSystemMute(true); // 🌟 核心：一旦開始監聽，直接全域封鎖系統提示音
        restart();
    }

    public void stopListening() {
        Log.d(TAG, "停止監聽");
        isListening = false;
        mainHandler.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            recognizer.cancel(); // 關閉監聽
        }

        setSystemMute(false); // 🌟 核心：確定停止監聽後，才解除系統靜音
    }

    private void restart() {
        if (!isListening) return;
        try {
            recognizer.cancel();
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "重啟失敗: " + e.getMessage());
        }
    }

    // 🌟 全新音量控制方法：絕對不碰 STREAM_MUSIC (媒體)，放過你的宣傳影片
    private void setSystemMute(boolean mute) {
        if (audioManager == null) return;
        int direction = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        try {
            // 只封鎖系統、通知、鬧鐘與鈴聲通道
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, direction, 0);
        } catch (Exception e) {
            Log.w(TAG, "音量控制異常");
        }
    }
}