package com.example.server; // ⚠️ 請確保這行與你的專案 package 名稱一致

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends AppCompatActivity {

    // UI 元件
    private VideoView videoScreen;
    private TextView chatDisplay, statusLabel;
    private ScrollView chatScroll;
    private LinearLayout videoContainer;
    private Button recordBtn;

    // 網路與設定
    private String serverIp = "192.168.0.157";
    private String serverPort = "8000";
    private String deviceId = "Android_Tester";
    private OkHttpClient httpClient;
    private WebSocket webSocket;

    // 音訊播放
    private MediaPlayer audioPlayer;
    private Queue<File> audioQueue = new LinkedList<>();
    private boolean isPlayingAudio = false;

    // 語音喚醒相關
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListeningWakeWord = false;
    private boolean isRecordingCommand = false;

    private static final int RECORD_AUDIO_PERMISSION_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 🌟 1. 加入這行：讓平板螢幕保持常亮，防止系統休眠殺死網路
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initUI();
        checkPermissions();

        httpClient = new OkHttpClient();
        initAudioPlayer();
        initWakeWordListener();

        fetchVideoList();
        initWebSocket();
    }

    private void initUI() {
        videoScreen = findViewById(R.id.video_screen);
        chatDisplay = findViewById(R.id.chat_display);
        statusLabel = findViewById(R.id.status_label);
        chatScroll = findViewById(R.id.chat_scroll);
        videoContainer = findViewById(R.id.video_container);
        recordBtn = findViewById(R.id.record_btn);

        recordBtn.setEnabled(false);
        recordBtn.setOnClickListener(v -> {
            if (!isRecordingCommand) startRecordingCommand();
        });

        // 🌟 影片播完後，重新恢復監聽
        videoScreen.setOnCompletionListener(mp -> {
            runOnUiThread(() -> appendChat("<br><font color='gray'>[系統] 影片播放結束。</font>"));
            startWakeWordListening();
        });
    }

    // ==========================================
    // 🌟 1. 喚醒詞監聽邏輯 (嚴格回合制)
    // ==========================================
    private void initWakeWordListener() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW");

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    // 如果沒人講話超時，默默重啟維持背景監聽
                    if (isListeningWakeWord && !isRecordingCommand) {
                        speechRecognizer.startListening(speechRecognizerIntent);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);

                        // 判斷是否講了喚醒詞
                        if (text.contains("小美") || text.contains("小每")) {

                            // 🛑 步驟一：只要聽到指令，立刻停止監聽！(關閉麥克風)
                            stopWakeWordListening();

                            if (text.contains("教育") || text.contains("訓練")) {
                                runOnUiThread(() -> appendChat("<br><font color='#27ae60'><b>[系統] 攔截指令！向伺服器請求教育影片...</b></font>"));
                                fetchTrainingVideos();
                            } else {
                                String cleanText = text.replace("小美", "").replace("小每", "").replace(" ", "");

                                if (cleanText.length() >= 2) {
                                    // 🛑 步驟二：[一氣呵成模式] 傳送文字給 Server
                                    runOnUiThread(() -> appendChat("<br>💡 [一氣呵成] 抓到指令：" + cleanText));
                                    try {
                                        JSONObject textCmd = new JSONObject();
                                        textCmd.put("type", "text_command");
                                        textCmd.put("text", cleanText);
                                        if (webSocket != null) webSocket.send(textCmd.toString());
                                    } catch (Exception e) { e.printStackTrace(); }

                                } else {
                                    // 🛑 步驟二：[兩段式模式] 開啟自訂的 VAD 錄音
                                    runOnUiThread(() -> appendChat("<br><font color='#e67e22'><b>[系統] 聽到「小美」！請開始說話...</b></font>"));
                                    startRecordingCommand();
                                }
                            }
                            // 🌟 處理完直接 return，絕對不要在這裡重啟麥克風！
                            return;
                        }
                    }
                    // 如果收到的是雜音(沒有喚醒詞)，才重啟監聽
                    if (isListeningWakeWord && !isRecordingCommand) {
                        speechRecognizer.startListening(speechRecognizerIntent);
                    }
                }

                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    private void startWakeWordListening() {
        if (speechRecognizer != null && !isRecordingCommand) {
            isListeningWakeWord = true;
            speechRecognizer.startListening(speechRecognizerIntent);
            runOnUiThread(() -> {
                statusLabel.setText("🟢 準備就緒 (等待呼叫「小美」)");
                statusLabel.setTextColor(Color.parseColor("#4CAF50"));
            });
        }
    }

    private void stopWakeWordListening() {
        isListeningWakeWord = false;
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        runOnUiThread(() -> {
            statusLabel.setText("⏳ 處理中，麥克風已關閉...");
            statusLabel.setTextColor(Color.GRAY);
        });
    }

    // ==========================================
    // 🌟 2. 錄製指令與自動斷句 (VAD)
    // ==========================================
    private void startRecordingCommand() {
        if (isRecordingCommand) return;
        isRecordingCommand = true;

        stopWakeWordListening(); // 確保原先的監聽徹底關閉
        if (audioPlayer.isPlaying()) audioPlayer.pause();
        if (videoScreen.isPlaying()) videoScreen.pause();

        runOnUiThread(() -> {
            statusLabel.setText("🔴 正在聆聽指令 (說完請停頓)...");
            statusLabel.setTextColor(Color.RED);
            recordBtn.setBackgroundColor(Color.RED);
            recordBtn.setEnabled(false);
        });

        new Thread(() -> {
            byte[] wavBytes = recordAudioWithVAD(15000);

            if (wavBytes != null && wavBytes.length > 44) {
                runOnUiThread(() -> {
                    statusLabel.setText("⏳ 收到語音，送往伺服器，麥克風關閉...");
                    statusLabel.setTextColor(Color.parseColor("#f39c12"));
                    recordBtn.setBackgroundColor(Color.parseColor("#3498db"));
                    recordBtn.setEnabled(true);
                });
                if (webSocket != null) webSocket.send(ByteString.of(wavBytes));
            } else {
                runOnUiThread(() -> {
                    statusLabel.setText("⚠️ 等待超時，沒有聽到指令");
                    statusLabel.setTextColor(Color.GRAY);
                    recordBtn.setBackgroundColor(Color.parseColor("#3498db"));
                    recordBtn.setEnabled(true);
                });
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                isRecordingCommand = false;
                startWakeWordListening(); // 失敗時重新啟動
            }
        }).start();
    }

    private byte[] recordAudioWithVAD(int maxDurationMs) {
        int sampleRate = 16000;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null;

        AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[minBufferSize];

        audioRecord.startRecording();
        long startTime = System.currentTimeMillis();
        long silenceStartTime = System.currentTimeMillis();
        boolean hasSpoken = false;
        int silenceThresholdMs = 1500;

        while (System.currentTimeMillis() - startTime < maxDurationMs) {
            int readBytes = audioRecord.read(buffer, 0, buffer.length);
            if (readBytes > 0) {
                pcmOut.write(buffer, 0, readBytes);
                long sum = 0;
                for (int i = 0; i < readBytes; i += 2) {
                    short sample = (short) ((buffer[i + 1] << 8) | buffer[i] & 0xff);
                    sum += sample * sample;
                }
                double amplitude = Math.sqrt(sum / (readBytes / 2.0));

                if (amplitude > 2500) {
                    hasSpoken = true;
                    silenceStartTime = System.currentTimeMillis();
                } else {
                    if (hasSpoken && (System.currentTimeMillis() - silenceStartTime > silenceThresholdMs)) {
                        break;
                    }
                }
            }
        }
        audioRecord.stop();
        audioRecord.release();

        if (!hasSpoken) return null;
        return addWavHeader(pcmOut.toByteArray(), sampleRate, (short) 1, (short) 16);
    }

    private byte[] addWavHeader(byte[] pcmData, int sampleRate, short channels, short bitDepth) {
        int totalDataLen = pcmData.length + 36;
        int byteRate = sampleRate * channels * (bitDepth / 8);
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff); header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff); header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff); header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff); header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff); header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff); header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (bitDepth / 8)); header[33] = 0;
        header[34] = (byte) bitDepth; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff); header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff); header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { out.write(header); out.write(pcmData); } catch (IOException ignored) {}
        return out.toByteArray();
    }

    // ==========================================
    // 🌟 3. 影片與 WebSocket 通訊
    // ==========================================
    private void fetchTrainingVideos() {
        String url = "http://" + serverIp + ":" + serverPort + "/api/training_videos";
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    appendChat("<br><font color='red'>[網路錯誤] 無法取得影片。</font>");
                    startWakeWordListening(); // 失敗了就恢復監聽
                });
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        if (json.getString("status").equals("success") && json.getInt("video_count") > 0) {
                            String firstVideo = json.getJSONArray("videos").getString(0);
                            String videoUrl = "http://" + serverIp + ":" + serverPort + "/training_videos/" + firstVideo;

                            runOnUiThread(() -> {
                                videoScreen.setVideoURI(Uri.parse(videoUrl));
                                videoScreen.start();
                                appendChat("<br><font color='gray'>[系統] 正在播放教育影片: " + firstVideo + "</font>");
                            });
                        } else {
                            runOnUiThread(() -> {
                                appendChat("<br><font color='red'>[錯誤] 伺服器端沒有教育影片！</font>");
                                startWakeWordListening(); // 沒影片就恢復監聽
                            });
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> startWakeWordListening());
                    }
                }
            }
        });
    }

    private void fetchVideoList() {
        String url = "http://" + serverIp + ":" + serverPort + "/api/videos";
        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {}
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        if (json.getString("status").equals("success")) {
                            JSONArray videos = json.getJSONArray("videos");
                            runOnUiThread(() -> {
                                for (int i = 0; i < videos.length(); i++) {
                                    try { addVideoToUI(videos.getString(i)); } catch (Exception ignored) {}
                                }
                            });
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void addVideoToUI(String videoName) {
        Button btn = new Button(this);
        btn.setText("▶ " + videoName);
        btn.setBackgroundColor(Color.parseColor("#2ecc71"));
        btn.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 10, 0);
        btn.setLayoutParams(params);
        btn.setOnClickListener(v -> {
            String videoUrl = "http://" + serverIp + ":" + serverPort + "/videos/" + videoName;
            videoScreen.setVideoURI(Uri.parse(videoUrl));
            videoScreen.start();
        });
        videoContainer.addView(btn);
    }

    private void initWebSocket() {
        String wsUrl = "ws://" + serverIp + ":" + serverPort + "/ws/chat?device_id=" + deviceId;
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                runOnUiThread(() -> {
                    recordBtn.setEnabled(true);
                    startWakeWordListening();
                });
            }
            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject data = new JSONObject(text);
                    String type = data.getString("type");
                    if (type.equals("user_text")) {
                        runOnUiThread(() -> appendChat("<br><b>👤 您說：</b> " + data.optString("text") + "<br><b>🏥 助手：</b> "));
                    } else if (type.equals("ai_text_chunk")) {
                        runOnUiThread(() -> {
                            chatDisplay.append(data.optString("text"));
                            scrollToBottom();
                        });
                    } else if (type.equals("error")) {
                        runOnUiThread(() -> appendChat("<br><font color='red'>錯誤: " + data.optString("text") + "</font>"));
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                runOnUiThread(() -> statusLabel.setText("🔊 正在播放回覆..."));
                try {
                    File tempMp3 = File.createTempFile("ai_reply", ".mp3", getCacheDir());
                    FileOutputStream fos = new FileOutputStream(tempMp3);
                    fos.write(bytes.toByteArray());
                    fos.close();
                    audioQueue.offer(tempMp3);
                    runOnUiThread(() -> playNextAudio());
                } catch (IOException ignored) {}
            }
            @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                runOnUiThread(() -> {
                    updateDisconnectedState();
                    reconnectWebSocket(); // 🌟 斷線時觸發自動重連
                });
            }
            @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                runOnUiThread(() -> {
                    updateDisconnectedState();
                    reconnectWebSocket(); // 🌟 發生錯誤(網路不穩)時觸發自動重連
                });
            }
        });
    }

    // ==========================================
    // 🌟 新增：自動重連機制
    // ==========================================
    private void reconnectWebSocket() {
        runOnUiThread(() -> {
            statusLabel.setText("🟡 嘗試重新連線中 (3秒後)...");
            statusLabel.setTextColor(Color.parseColor("#f39c12"));
        });

        // 延遲 3 秒後重新執行 initWebSocket
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 在重新連線前，稍微確認一下當前狀態，避免重複連線
            if (statusLabel.getText().toString().contains("重新連線") || statusLabel.getText().toString().contains("斷線")) {
                initWebSocket();
            }
        }, 3000);
    }

    private void updateDisconnectedState() {
        statusLabel.setText("🔴 伺服器已斷線");
        statusLabel.setTextColor(Color.RED);
        recordBtn.setEnabled(false);
        stopWakeWordListening();
    }

    private void initAudioPlayer() {
        audioPlayer = new MediaPlayer();
        audioPlayer.setOnCompletionListener(mp -> {
            isPlayingAudio = false;
            mp.reset();
            playNextAudio();
        });
    }

    private void playNextAudio() {
        if (!isPlayingAudio && !audioQueue.isEmpty()) {
            File nextFile = audioQueue.poll();
            if (nextFile != null && nextFile.exists()) {
                try {
                    isPlayingAudio = true;
                    audioPlayer.setDataSource(nextFile.getAbsolutePath());
                    audioPlayer.prepare();
                    audioPlayer.start();
                } catch (IOException e) {
                    isPlayingAudio = false;
                    playNextAudio();
                }
            }
        } else if (!isPlayingAudio && audioQueue.isEmpty()) {
            // 🛑 步驟四：AI 語音完全播報完畢，重新啟動監聽！
            isRecordingCommand = false;
            startWakeWordListening();
        }
    }

    private void appendChat(String text) {
        chatDisplay.append(Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT));
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_PERMISSION_CODE);
        }
    }
}