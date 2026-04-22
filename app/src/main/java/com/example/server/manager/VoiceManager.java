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

        Log.i(TAG, "⚙️ 初始化 VoiceManager (語音喚醒模組)...");
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                // 🌟 就是這裡發出「嘟」的聲音！
                Log.d(TAG, "🟢 系統提示音 (嘟)：麥克風已就緒，開始背景收音...");
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    Log.i(TAG, "📝 語音辨識結果: " + matches.toString());

                    for (String text : matches) {
                        if (text.contains("小美") || text.contains("小梅") || text.contains("小米") ||
                                text.contains("小門") || text.contains("校門") || text.contains("小名")) {

                            Log.i(TAG, "✨ 成功捕捉到喚醒詞『小美』！觸發對話 UI。");
                            if (onWakeWordDetected != null) onWakeWordDetected.run();
                            return; // 喚醒成功就跳出，不再重啟監聽
                        }
                    }
                }
                Log.d(TAG, "🤷‍♂️ 沒聽到關鍵字，準備重啟監聽...");
                scheduleRestart();
            }

            @Override
            public void onError(int error) {
                String errorMsg = getErrorText(error);
                // 忽略「超時沒講話(6)」和「聽不懂(7)」的錯誤，因為這在環境音中很正常
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                    // 🌟 就是這裡發出「登」的聲音！
                    Log.d(TAG, "🔕 背景收音結束 (登)：" + errorMsg);
                } else {
                    Log.e(TAG, "❌ 語音辨識發生真實錯誤: " + errorMsg);
                }
                scheduleRestart();
            }

            @Override public void onBeginningOfSpeech() {
                Log.d(TAG, "🗣️ 偵測到環境中有聲音輸入...");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                Log.d(TAG, "🔇 聲音輸入結束，等待系統解析...");
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void scheduleRestart() {
        if (!isListening) return;
        Log.d(TAG, "⏳ 準備在 1 秒後重啟麥克風...");
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> {
            if (isListening) restart();
        }, 1000);
    }

    public void startListeningWakeWord() {
        Log.i(TAG, "👂 啟動背景喚醒詞監聽...");
        isListening = true;
        setSystemMute(true);
        restart();
    }

    public void stopListening() {
        Log.i(TAG, "🛑 停止背景喚醒詞監聽，釋放麥克風。");
        isListening = false;
        mainHandler.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            recognizer.cancel();
        }
        setSystemMute(false);
    }

    private void restart() {
        if (!isListening) return;
        Log.d(TAG, "🔄 執行麥克風重啟 (即將再次發出嘟聲)...");
        try {
            recognizer.cancel();
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.e(TAG, "❌ 重啟麥克風失敗", e);
        }
    }

    private void setSystemMute(boolean mute) {
        if (audioManager == null) return;
        int direction = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, direction, 0);
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, direction, 0);
        } catch (Exception e) {
            Log.e(TAG, "靜音設定異常", e);
        }
    }

    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "音頻錄製錯誤 (可能是麥克風被佔用)";
            case SpeechRecognizer.ERROR_CLIENT: return "客戶端錯誤";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "權限不足";
            case SpeechRecognizer.ERROR_NETWORK: return "網路錯誤";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "網路超時";
            case SpeechRecognizer.ERROR_NO_MATCH: return "沒有匹配的結果";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "識別服務正忙 (嚴重衝突！)";
            case SpeechRecognizer.ERROR_SERVER: return "伺服器錯誤";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "超時沒有講話";
            default: return "未知的錯誤";
        }
    }
}