package com.example.server.manager;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.server.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ChatWebSocketManager {
    private static final String TAG = "WebSocketManager";
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final String wsUrl;
    private final LlmAudioPlayer player;
    private final MainActivity mainActivity;

    public ChatWebSocketManager(String ip, String port, String robotId, LlmAudioPlayer player, MainActivity mainActivity) {
        this.client = new OkHttpClient();
        this.wsUrl = "ws://" + ip + ":" + port + "/ws/chat?device_id=" + robotId;
        this.player = player;
        this.mainActivity = mainActivity;
    }

    public void connect() {
        if (webSocket != null) {
            Log.d(TAG, "⚠️ WebSocket 已經存在，跳過重複連線");
            return;
        }

        Log.i(TAG, "🔌 準備發起 WebSocket 連線至: " + wsUrl);
        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.i(TAG, "✅ WebSocket 連線成功！專屬推播通道已建立。");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");

                    if ("broadcast_start".equals(type)) {
                        String broadcastText = json.getString("text");
                        Log.i(TAG, "📥 收到廣播指令 [START]: " + broadcastText);
                        mainActivity.showBroadcastUI(broadcastText);
                    }
                    else if ("broadcast_end".equals(type)) {
                        Log.i(TAG, "📥 收到廣播指令 [END]");
                    }
                    else if ("ai_text_chunk".equals(type)) {
                        // 保留給一般聊天用
                    } else {
                        Log.d(TAG, "📥 收到其他 JSON 訊息: " + text);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "❌ JSON 解析錯誤: " + text, e);
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
                Log.i(TAG, "🎵 收到 Server 二進位音檔，大小: " + bytes.size() + " bytes，丟入播放器！");
                player.addToQueue(bytes.toByteArray());
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.i(TAG, "🚪 WebSocket 已正常關閉 (Code: " + code + ", Reason: " + reason + ")");
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Log.e(TAG, "❌ WebSocket 連線失敗或異常中斷: " + t.getMessage());
                ChatWebSocketManager.this.webSocket = null;
            }
        });
    }

    public void sendAudio(byte[] wavData) {
        if (webSocket != null) {
            Log.i(TAG, "📤 傳送病患語音至 Server，大小: " + wavData.length + " bytes");
            webSocket.send(ByteString.of(wavData));
        } else {
            Log.e(TAG, "❌ 無法傳送語音，WebSocket 未連線！");
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            Log.i(TAG, "✂️ 主動斷開 WebSocket 連線");
            webSocket.close(1000, "User Exit");
            webSocket = null;
        }
    }
}