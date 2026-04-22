package com.example.server.manager;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.widget.VideoView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RobotManager {
    private static final String TAG = "RobotManager";
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String robotId;
    private final Activity activity;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private long currentVideoRequestId = 0;

    // 🌟 新增：用來記錄上一次的狀態，避免輪詢時瘋狂洗版
    private String lastKnownState = "UNKNOWN";

    public RobotManager(Activity activity, String ip, String port, String robotId) {
        this.activity = activity;
        this.httpClient = new OkHttpClient();
        this.baseUrl = "http://" + ip + ":" + port;
        this.robotId = robotId;
        Log.i(TAG, "⚙️ 初始化 RobotManager (機器人控制大腦)...");
    }

    // --- 影音播放 API ---
    public void playNextPromoVideo(VideoView videoView, VideoCheckCallback check) {
        if (!check.shouldPlay()) return;
        final long requestId = ++currentVideoRequestId;
        String url = baseUrl + "/api/client/play_next?robot_id=" + robotId;

        Log.d(TAG, "🎬 請求下一部宣傳影片...");
        Request request = new Request.Builder().url(url).get().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "❌ 取得影片失敗: " + e.getMessage());
                activity.runOnUiThread(() -> videoView.postDelayed(() -> playNextPromoVideo(videoView, check), 5000));
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject json = new JSONObject(response.body().string());
                        if ("success".equals(json.getString("status"))) {
                            String downloadUrl = json.getJSONObject("video_info").getString("download_url");
                            String fullUrl = baseUrl + downloadUrl;
                            Log.d(TAG, "✅ 成功取得影片網址: " + downloadUrl);
                            activity.runOnUiThread(() -> {
                                if (requestId != currentVideoRequestId) return;
                                videoView.stopPlayback();
                                videoView.setVideoURI(Uri.parse(fullUrl));
                                videoView.start();
                            });
                        }
                    } catch (Exception e) { Log.e(TAG, "❌ 解析影片 JSON 錯誤", e); }
                }
            }
        });
    }

    // --- 機器人控制 API ---
    public void sendMission(String destId, MissionCallback callback) {
        Log.i(TAG, "🚀 [派車指令] 發送前往點位: " + destId);
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            json.put("destination_id", destId);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/mission/create").post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "❌ [派車指令] 網路連線失敗", e);
                    activity.runOnUiThread(() -> Toast.makeText(activity, "派車失敗", Toast.LENGTH_SHORT).show());
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            JSONObject res = new JSONObject(response.body().string());
                            String missionId = res.optString("mission_id", "");
                            Log.i(TAG, "✅ [派車成功] 伺服器回傳任務 ID: " + missionId);
                            activity.runOnUiThread(() -> callback.onSuccess(missionId));
                        } catch (Exception e) { Log.e(TAG, "❌ 解析派車回應錯誤", e); }
                    } else {
                        Log.e(TAG, "❌ [派車失敗] Server 回傳錯誤碼: " + response.code());
                    }
                }
            });
        } catch (Exception e) { Log.e(TAG, "❌ JSON 組裝錯誤", e); }
    }

    public void cancelMission(String missionId, Runnable onSuccess) {
        Log.i(TAG, "🛑 [取消指令] 準備取消任務 ID: " + missionId);
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            json.put("mission_id", missionId);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/mission/cancel").post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "❌ [取消指令] 網路連線失敗", e);
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (response.isSuccessful() && onSuccess != null) {
                        Log.i(TAG, "✅ [取消成功] 任務已成功取消");
                        activity.runOnUiThread(onSuccess);
                    } else {
                        Log.e(TAG, "❌ [取消失敗] Server 回傳錯誤碼: " + response.code());
                    }
                }
            });
        } catch (Exception e) { Log.e(TAG, "❌ JSON 組裝錯誤", e); }
    }

    public void sendChargeRequest(Runnable onSuccess) {
        Log.i(TAG, "🔋 [充電指令] 發送返回充電座請求...");
        try {
            JSONObject json = new JSONObject();
            json.put("robot_id", robotId);
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder().url(baseUrl + "/api/robot/charge").post(body).build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "❌ [充電指令] 網路連線失敗", e);
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (response.isSuccessful() && onSuccess != null) {
                        Log.i(TAG, "✅ [充電成功] 機器人準備返航");
                        activity.runOnUiThread(onSuccess);
                    } else {
                        Log.e(TAG, "❌ [充電失敗] Server 回傳錯誤碼: " + response.code());
                    }
                }
            });
        } catch (Exception e) { Log.e(TAG, "❌ JSON 組裝錯誤", e); }
    }

    public void checkRobotStatus(StatusCallback callback) {
        Log.d(TAG, "📡 [狀態輪詢] 發送狀態查詢請求給 Server...");
        String url = baseUrl + "/api/robot/status";
        JSONObject json = new JSONObject();
        try { json.put("robot_id", robotId); } catch (Exception ignored) {}
        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder().url(url).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "❌ [API 錯誤] 查詢狀態失敗: Server 沒有回應 (" + e.getMessage() + ")");
                activity.runOnUiThread(() -> callback.onResult(null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject res = new JSONObject(response.body().string());

                        if (res.optBoolean("success", false)) {
                            JSONObject dataObj = res.optJSONObject("data");

                            if (dataObj != null && dataObj.has("state") && !dataObj.isNull("state")) {
                                String state = dataObj.getString("state");

                                // 🌟 關鍵邏輯：只有狀態改變時才印出 Log.i，避免洗版
                                if (!state.equals(lastKnownState)) {
                                    Log.i(TAG, "🚙 [狀態更新] 機器人目前狀態改變為: " + state);
                                    lastKnownState = state;
                                }

                                activity.runOnUiThread(() -> callback.onResult(state));
                                return;
                            }
                        }
                        Log.d(TAG, "⚠️ 收到不完整的狀態資料");
                        activity.runOnUiThread(() -> callback.onResult("UNKNOWN"));

                    } catch (Exception e) {
                        Log.e(TAG, "❌ [解析錯誤] 解析狀態回應失敗", e);
                        activity.runOnUiThread(() -> callback.onResult(null));
                    }
                } else {
                    Log.e(TAG, "❌ [狀態查詢失敗] HTTP 錯誤碼: " + response.code());
                    activity.runOnUiThread(() -> callback.onResult(null));
                }
            }
        });
    }
    // ==========================================
    // 🌟 新增：向 Server 抓取動態點位清單
    // ==========================================
    public void fetchDestinations(DestinationsCallback callback) {
        Log.i(TAG, "📍 [點位抓取] 向 Server 請求最新地圖點位...");
        String url = baseUrl + "/api/client/destinations";
        Request request = new Request.Builder().url(url).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "❌ [點位抓取] 網路連線失敗: " + e.getMessage());
                activity.runOnUiThread(() -> callback.onResult(null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                // 📝 取得原始回傳內容
                String responseBody = (response.body() != null) ? response.body().string() : "";

                if (response.isSuccessful()) {
                    try {
                        JSONObject json = new JSONObject(responseBody);

                        // 🌟 嚴格判定條件：status 必須是 "success" 且含有 destinations 欄位
                        String status = json.optString("status", "");
                        if ("success".equals(status) && json.has("destinations")) {
                            org.json.JSONArray dests = json.getJSONArray("destinations");

                            Log.i(TAG, "✅ [點位抓取] 成功取得 " + dests.length() + " 個真實點位");
                            activity.runOnUiThread(() -> callback.onResult(dests));
                            return;
                        } else {
                            // 如果 status 是 error (代表 Server 抓不到雲端，走到了快取邏輯)
                            Log.w(TAG, "⚠️ Server 回傳非 success 狀態: " + status + "，拒絕採用資料。");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ [點位抓取] JSON 格式解析失敗: " + responseBody, e);
                    }
                } else {
                    Log.e(TAG, "❌ [點位抓取] HTTP 錯誤，代碼: " + response.code());
                }

                // 若走到這代表不符合嚴格成功條件
                activity.runOnUiThread(() -> callback.onResult(null));
            }
        });
    }
    // 在檔案最底部的 interface 區域新增這行：
    public interface DestinationsCallback { void onResult(org.json.JSONArray destinations); }
    public interface VideoCheckCallback { boolean shouldPlay(); }
    public interface StatusCallback { void onResult(String status); }
    public interface MissionCallback { void onSuccess(String missionId); }
}