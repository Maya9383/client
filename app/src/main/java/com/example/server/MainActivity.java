package com.example.server;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.server.manager.RobotManager;
import com.example.server.manager.VoiceManager;
import com.example.server.manager.ChatWebSocketManager;
import com.example.server.manager.LlmAudioPlayer;
import com.example.server.utils.AudioRecorderHelper;

public class MainActivity extends AppCompatActivity {
    // --- UI 組件 ---
    private ViewFlipper viewFlipper;
    private VideoView videoView;
    private CardView cardRecord;
    private LinearLayout layoutVisualizer;
    private Animation pulse;
    private View[] vBars;
    private TextView tvRunningStatus;
    private TextView tvCountdown;

    // --- 設定值 ---
    private final String serverIp = "192.168.0.157";
    private final String serverPort = "8000";
    private final String ROBOT_ID = "robot_b";

    // --- 邏輯模組 ---
    private RobotManager robotManager;
    private VoiceManager voiceManager;
    private ChatWebSocketManager wsManager;
    private LlmAudioPlayer audioPlayer;
    private AudioRecorderHelper recorderHelper;

    // --- 控車邏輯專用 Handler 與計時器 ---
    private final Handler logicHandler = new Handler(Looper.getMainLooper());
    private Runnable waitingTimerRunnable;
    private Runnable statusPollingRunnable;
    private int countdownSeconds = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initManagers();
        initUI();
        checkPermissions();

        robotManager.playNextPromoVideo(videoView, () -> viewFlipper.getDisplayedChild() == 0);
    }

    private void initManagers() {
        robotManager = new RobotManager(this, serverIp, serverPort, ROBOT_ID);

        audioPlayer = new LlmAudioPlayer(this, () -> {
            if (viewFlipper.getDisplayedChild() == 2) {
                voiceManager.startListeningWithoutBeep();
            }
        });

        wsManager = new ChatWebSocketManager(serverIp, serverPort, ROBOT_ID, audioPlayer);

        recorderHelper = new AudioRecorderHelper(this, new AudioRecorderHelper.AudioCallback() {
            @Override
            public void onVolumeChanged(double volumePercent) {
                updateVisualizerUI(volumePercent);
            }

            @Override
            public void onRecordingFinished(byte[] wavData) {
                stopVisualizerUI();
                wsManager.sendAudio(wavData);
            }
        });

        voiceManager = new VoiceManager(this, this::startChatRecording);
    }

    private void initUI() {
        viewFlipper = findViewById(R.id.viewFlipper);
        videoView = findViewById(R.id.videoView);
        cardRecord = findViewById(R.id.cardRecord);
        layoutVisualizer = findViewById(R.id.layoutVisualizer);
        pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_animation);
        tvRunningStatus = findViewById(R.id.tvRunningStatus);
        tvCountdown = findViewById(R.id.tvCountdown);

        vBars = new View[]{
                findViewById(R.id.vBar1), findViewById(R.id.vBar2), findViewById(R.id.vBar3),
                findViewById(R.id.vBar4), findViewById(R.id.vBar5), findViewById(R.id.vBar6),
                findViewById(R.id.vBar7), findViewById(R.id.vBar8), findViewById(R.id.vBar9)
        };

        videoView.setOnCompletionListener(mp -> {
            if (viewFlipper.getDisplayedChild() == 0) robotManager.playNextPromoVideo(videoView, () -> true);
        });

        // --- 頁面 0: 首頁 ---
        findViewById(R.id.btnTrainingVideo).setOnClickListener(v -> robotManager.fetchTrainingVideo(videoView));
        findViewById(R.id.btnControl).setOnClickListener(v -> switchPage(1));
        findViewById(R.id.btnStartChat).setOnClickListener(v -> openChatPage());

        // --- 頁面 1: 控車頁 (layout_control) ---
        findViewById(R.id.btnBackFromControl).setOnClickListener(v -> {
            resetControlLogic();
            switchPage(0);
        });

        findViewById(R.id.btnDestLobby).setOnClickListener(v -> sendNewMission("Lobby"));
        findViewById(R.id.btnDestWashroom).setOnClickListener(v -> sendNewMission("Washroom"));
        findViewById(R.id.btnDestStandby).setOnClickListener(v -> sendNewMission("待機點"));
        findViewById(R.id.btnCharge).setOnClickListener(v -> performCharge());

        // --- 頁面 2: layout_chat (對話頁) ---
        findViewById(R.id.btnBackFromChat).setOnClickListener(v -> closeChatPage());
        findViewById(R.id.btnSendChat).setOnClickListener(v -> startChatRecording());

        // --- 頁面 3: 運行頁 (layout_running) ---
        findViewById(R.id.btnCancelMission).setOnClickListener(v -> handleCancelAction());
    }

    // --- 對話功能控制 ---

    private void openChatPage() {
        switchPage(2);
        wsManager.connect();
        voiceManager.startListeningWithoutBeep();
    }

    private void closeChatPage() {
        switchPage(0); // 返回首頁
        wsManager.disconnect();
        voiceManager.stopListening(); // 停止監聽關鍵字
        audioPlayer.stopAll();
        stopVisualizerUI();
    }

    private void startChatRecording() {
        voiceManager.stopListening();
        voiceManager.playCustomBeep(true);
        runOnUiThread(() -> {
            cardRecord.startAnimation(pulse);
            layoutVisualizer.setVisibility(View.VISIBLE);
        });
        recorderHelper.startRecording(5000);
    }

    // --- 控車核心邏輯 ---

    private void sendNewMission(String destId) {
        resetControlLogic();
        if (tvRunningStatus != null) tvRunningStatus.setText("機器人前往 " + destId + " 中...");

        robotManager.sendMission(destId, () -> {
            switchPage(3);
            startStatusPolling();
        });
    }

    private void performCharge() {
        resetControlLogic();
        if (tvRunningStatus != null) tvRunningStatus.setText("正在返回充電座...");

        robotManager.sendChargeRequest(() -> {
            switchPage(3);
            startStatusPolling();
        });
    }

    private void handleCancelAction() {
        robotManager.cancelMission(() -> {
            resetControlLogic();
            switchPage(1);
            startWaitTimer();
        });
    }

    private void startWaitTimer() {
        stopWaitTimer();
        countdownSeconds = 30;

        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.VISIBLE);
            tvCountdown.setText(countdownSeconds + "s 後自動返航");
        }

        waitingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (countdownSeconds > 0) {
                    countdownSeconds--;
                    if (tvCountdown != null) tvCountdown.setText(countdownSeconds + "s 後自動返航");
                    logicHandler.postDelayed(this, 1000);
                } else {
                    if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                    switchPage(4);
                    startStatusPolling();
                }
            }
        };
        logicHandler.postDelayed(waitingTimerRunnable, 1000);
    }

    private void startStatusPolling() {
        stopStatusPolling();
        statusPollingRunnable = new Runnable() {
            @Override
            public void run() {
                robotManager.checkRobotStatus(status -> {
                    if ("ARRIVED".equals(status)) {
                        resetControlLogic();
                        switchPage(1);
                    } else {
                        logicHandler.postDelayed(this, 2000);
                    }
                });
            }
        };
        logicHandler.post(statusPollingRunnable);
    }

    private void resetControlLogic() {
        stopWaitTimer();
        stopStatusPolling();
        if (tvCountdown != null) {
            tvCountdown.setVisibility(View.GONE);
        }
    }

    private void stopWaitTimer() {
        if (waitingTimerRunnable != null) logicHandler.removeCallbacks(waitingTimerRunnable);
    }

    private void stopStatusPolling() {
        if (statusPollingRunnable != null) logicHandler.removeCallbacks(statusPollingRunnable);
    }

    private void switchPage(int pageIndex) {
        if (viewFlipper != null) viewFlipper.setDisplayedChild(pageIndex);
        if (pageIndex == 0) {
            if (!videoView.isPlaying()) videoView.start();
        } else {
            if (videoView.isPlaying()) videoView.pause();
        }
    }

    private void updateVisualizerUI(double volumePercent) {
        runOnUiThread(() -> {
            float scale = getResources().getDisplayMetrics().density;
            float[] weights = {0.2f, 0.4f, 0.6f, 0.8f, 1.0f, 0.8f, 0.6f, 0.4f, 0.2f};
            for (int i = 0; i < vBars.length; i++) {
                int h = (int) ((10f + (60f * volumePercent * weights[i])) * scale);
                vBars[i].getLayoutParams().height = h;
                vBars[i].requestLayout();
            }
        });
    }

    private void stopVisualizerUI() {
        runOnUiThread(() -> {
            cardRecord.clearAnimation();
            layoutVisualizer.setVisibility(View.INVISIBLE);
            voiceManager.playCustomBeep(false);
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        resetControlLogic();
        closeChatPage();
    }
}