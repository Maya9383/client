package com.example.server.manager;

import android.util.Log;
import androidx.annotation.NonNull;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

// 負責與 LLM 伺服器的長連接 //
public class ChatWebSocketManager {
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final String wsUrl;
    private final LlmAudioPlayer player;

    public ChatWebSocketManager(String ip, String port, String robotId, LlmAudioPlayer player) {
        this.client = new OkHttpClient();
        this.wsUrl = "ws://" + ip + ":" + port + "/ws/chat?device_id=" + robotId;
        this.player = player;
    }

    public void connect() {
        if (webSocket != null) return;
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                player.addToQueue(bytes.toByteArray()); // 收到音訊丟進播放器
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                ChatWebSocketManager.this.webSocket = null;
            }
        });
    }

    public void sendAudio(byte[] wavData) {
        if (webSocket != null) {
            webSocket.send(ByteString.of(wavData));
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "User Exit");
            webSocket = null;
        }
    }
}