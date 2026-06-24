package com.example.qgas;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class HomeFragment extends Fragment {

    private RecyclerView recyclerView;
    private QueueAdapter adapter;
    private List<JSONObject> queueList = new ArrayList<>();
    private final OkHttpClient client = getUnsafeOkHttpClient();

    private static final String PREFS_NAME = "AppConfig";
    private static final String KEY_API_URL = "api_base_url";
    private static final String KEY_WHITELIST_STATUS = "whitelist_status";
    private static final String KEY_DEVICE_ID = "device_id";

    private RecyclerView rvUpdates;
    private QueueAdapter updatesAdapter;
    private List<JSONObject> updatesList = new ArrayList<>();
    private boolean isUpdatingItems = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // New Station Queue
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        recyclerView = view.findViewById(R.id.rv_home_queue);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new QueueAdapter(queueList);
        recyclerView.setAdapter(adapter);

        // New Update Queue
        rvUpdates = view.findViewById(R.id.rv_updates_queue);
        rvUpdates.setLayoutManager(new LinearLayoutManager(getContext()));
        updatesAdapter = new QueueAdapter(updatesList);
        rvUpdates.setAdapter(updatesAdapter);

        return view;
    }

    private void loadUpdatesQueue() {
        SharedPreferences prefs = requireContext().getSharedPreferences("UpdatePriceQueue", Context.MODE_PRIVATE);
        String data = prefs.getString("pending_updates", "[]");
        try {
            JSONArray jsonArray = new JSONArray(data);
            updatesList.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                updatesList.add(jsonArray.getJSONObject(i));
            }
            updatesAdapter.notifyDataSetChanged();

            // Start the sequential sync for updates if not already running
            if (!updatesList.isEmpty() && !isUpdatingItems) {
                syncNextUpdate(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void syncNextUpdate(int index) {
        if (index >= updatesList.size()) {
            isUpdatingItems = false;
            return;
        }

        isUpdatingItems = true;
        JSONObject task = updatesList.get(index);

        try {
            String pid = task.getString("pid");
            String Mun = task.getString("municipality");
            double lon = task.getDouble("longitude");
            double lat = task.getDouble("latitude");
            Mun = displayMunicipalityToast(lat, lon);

            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("fuels", task.getString("fuels"))
                    .addFormDataPart("date_captured", task.getString("date_captured"))
                    .addFormDataPart("device_id", task.getString("device_id"))
                    .addFormDataPart("status", task.getString("status"))
                    .addFormDataPart("municipality", Mun);

            // Handle image attachment if present
            if (task.has("captured_uri")) {
                addFileToBuilder(builder, "photo", Uri.parse(task.getString("captured_uri")));
            }
            if (task.has("captured_update_uri")) {
                String path = task.getString("captured_update_uri");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        builder.addFormDataPart("photo", file.getName(),
                                RequestBody.create(file, MediaType.parse("image/jpeg")));
                    }
                }
            }

            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String baseUrl = sp.getString("api_base_url", "https://qgas.site");
            String url = baseUrl + "/public/api/station-fuel/" + pid;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .post(builder.build())
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    saveErrorToFile("Update Sync Failure: " + e.getMessage());
                    showToast("Update Sync Failure: " + e.getMessage());
                    // Move to next even on failure to avoid getting stuck
                    syncNextUpdate(index + 1);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try (Response resp = response) {
                        if (resp.isSuccessful()) {
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    Toast.makeText(getContext(), "Price updated successfully!", Toast.LENGTH_SHORT).show();
                                    removeUpdateFromQueue(task);
                                    // Item removed, so the next item is now at the current index
                                    syncNextUpdate(index);
                                });
                            }
                        } else {
                            saveErrorToFile("Update Server Error: " + resp.toString());
                            showToast("Update Failed: Server Error " + resp.toString());
                            syncNextUpdate(index + 1);
                        }
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            syncNextUpdate(index + 1);
        }
    }

    private void removeUpdateFromQueue(JSONObject item) {
        updatesList.remove(item);
        updatesAdapter.notifyDataSetChanged();
        SharedPreferences prefs = requireContext().getSharedPreferences("UpdatePriceQueue", Context.MODE_PRIVATE);
        prefs.edit().putString("pending_updates", new JSONArray(updatesList).toString()).apply();
    }

    /**
     * Helper to convert Uri to File for Multipart upload, matching UpdatePriceFragment logic.
     */
    private void addFileToBuilder(MultipartBody.Builder builder, String key, Uri uri) throws IOException {
        if (uri == null || uri.getScheme() == null || uri.getScheme().startsWith("http")) return;

        File file = new File(requireContext().getExternalCacheDir(), key + "_sync_temp.jpg");
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(file)) {
            if (is == null) return;
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        }
        builder.addFormDataPart(key, file.getName(), RequestBody.create(file, MediaType.parse("image/jpeg")));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Trigger whitelist check whenever the fragment view is created

        loadQueue();
        loadUpdatesQueue();
        checkAPI();
        checkWhitelist();
    }

    private void checkAPI() {
        SharedPreferences settings = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String currentUrl = settings.getString(KEY_API_URL, "");
        String legacyUrl = "https://services.leyteprovince.gov.ph:8282";
        String targetUrl = "https://qgas.site";

        if (currentUrl == null || currentUrl.isEmpty() || currentUrl.equals(legacyUrl)) {
            settings.edit().putString(KEY_API_URL, targetUrl).apply();
            Toast.makeText(getContext(), "Using default URL", Toast.LENGTH_LONG).show();
        }
    }


    private void checkWhitelist() {
        String androidId = Settings.Secure.getString(requireContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        final String finalAndroidId = (androidId != null) ? androidId.toUpperCase() : "UNKNOWN";

        SharedPreferences settings = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String originalUrl = settings.getString(KEY_API_URL, "https://qgas.site");

        if (originalUrl == null || originalUrl.isEmpty()) return;

        String baseUrl = originalUrl.endsWith("/") ? originalUrl.substring(0, originalUrl.length() - 1) : originalUrl;
        String url = baseUrl + "/public/api/user/is-device-whitelisted/" + finalAndroidId;

        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("HomeFragment", "Whitelist check failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response resp = response) {
                    String responseData = resp.body().string();
                    JSONObject json = new JSONObject(responseData);
                    boolean isSuccess = json.optBoolean("success", false);

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            SharedPreferences.Editor editor = settings.edit();
                            if (isSuccess) {
                                editor.putString(KEY_WHITELIST_STATUS, "WHITELISTED");
                                editor.putString(KEY_DEVICE_ID, finalAndroidId);
                            } else {
                                editor.putString(KEY_WHITELIST_STATUS, "NOT WHITELISTED");
                                showToast("Your not in the Whitelist anymore!");
                            }
                            editor.apply();

                            // Update navigation visibility in MainActivity if needed
                            if (getActivity() instanceof MainActivity) {
                                ((MainActivity) getActivity()).updateNavVisibility();
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void loadQueue() {
        SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", Context.MODE_PRIVATE);
        String data = prefs.getString("queue_data", "[]");
        try {
            JSONArray jsonArray = new JSONArray(data);
            queueList.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                queueList.add(jsonArray.getJSONObject(i));
            }
            adapter.notifyDataSetChanged();

            // Start the sequential sync if the list is not empty
            if (!queueList.isEmpty()) {
                syncNextItem(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Uploads items one by one recursively.
     * @param index The current index in the queueList to upload.
     */
    private void syncNextItem(int index) {
        // Base case: if we have reached the end of the list, stop.
        if (index >= queueList.size()) {
            return;
        }
        Log.e("TEST", ": " + queueList.get(index).toString());
        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String apiUrl = settings.getString("api_base_url", "https://qgas.site");
        String device_id = settings.getString("device_id", "");

        JSONObject scan = queueList.get(index);

        try {
            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("station_name", scan.getString("station"))
                    .addFormDataPart("latitude", String.valueOf(scan.getDouble("latitude")))
                    .addFormDataPart("longitude", String.valueOf(scan.getDouble("longitude")))
                    .addFormDataPart("date_captured", scan.getString("timestamp"))
                    .addFormDataPart("fuels", scan.getJSONArray("prices").toString())
                    .addFormDataPart("device_id", device_id)
                    .addFormDataPart("municipality", scan.getString("municipality"))
                    .addFormDataPart("station_category", scan.getString("station_category"));

            if (scan.has("gasImagePath")) {
                String path = scan.getString("gasImagePath");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        builder.addFormDataPart("photo", file.getName(),
                                RequestBody.create(file, MediaType.parse("image/jpeg")));
                    }
                }
            }

            if (scan.has("stationImagePath")) {
                String path = scan.getString("stationImagePath");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists()) {
                        builder.addFormDataPart("station_photo", file.getName(),
                                RequestBody.create(file, MediaType.parse("image/jpeg")));
                    }
                }
            }

            Request request = new Request.Builder()
                    .url(apiUrl + "/public/api/station-fuel")
                    .addHeader("Accept", "application/json")
                    .post(builder.build())
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    saveErrorToFile("Network failure: " + e.getMessage());
                    showToast("Upload failed: Network Error");

                    // Proceed to next item even if current fails (optional logic)
                    syncNextItem(index + 1);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";

                        if (response.isSuccessful()) {
                            showToast("Uploaded successfully!");

                            // Remove from queue and update UI on Main Thread
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    removeItemFromQueue(scan);
                                    // Since item is removed, the "next" item is now at the same 'index'
                                    syncNextItem(index);
                                });
                            }
                        } else {
                            String logEntry = "URL: " + request.url() +
                                    "\nStatus Code: " + response.code() +
                                    "\nResponse: " + responseBody;
                            saveErrorToFile(logEntry);
                            showToast("Server Error " + response.code());

                            // Move to next item on server-side error
                            syncNextItem(index + 1);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        syncNextItem(index + 1);
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            saveErrorToFile("JSON/Request Exception: " + e.getMessage());
            syncNextItem(index + 1);
        }
    }

    private void saveErrorToFile(String content) {
        new Thread(() -> {
            try {
                // Get the public Documents directory (/storage/emulated/0/Documents)
                File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);

                if (!docsDir.exists()) {
                    docsDir.mkdirs();
                }

                // Define the log file
                File logFile = new File(docsDir, "qgas_errors.txt");

                // Generate timestamp for the entry
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                String logEntry = "\n--- ERROR [" + timestamp + "] ---\n" + content + "\n---------------------\n";

                // Write to file in append mode
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write(logEntry.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }

                Log.d("FileLogger", "Saved to public Documents: " + logFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e("FileLogger", "Public storage write failed. Check permissions.", e);
            }
        }).start();
    }

    private void showToast(String message) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void removeItemFromQueue(JSONObject item) {
        queueList.remove(item);
        adapter.notifyDataSetChanged();
        SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", Context.MODE_PRIVATE);
        prefs.edit().putString("queue_data", new JSONArray(queueList).toString()).apply();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.ViewHolder> {
        private List<JSONObject> items;
        QueueAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            try {
                JSONObject obj = items.get(position);

                String stationName = obj.optString("station", "");
                if (stationName.isEmpty()) {
                    holder.tvStation.setVisibility(View.GONE);
                } else {
                    holder.tvStation.setVisibility(View.VISIBLE);
                    holder.tvStation.setText(stationName);
                }

                holder.tvTime.setText(obj.optString("timestamp", obj.optString("date_captured", "")));

                if (obj.has("latitude")) {
                    holder.tvLat.setText("Lat: " + obj.getDouble("latitude"));
                    holder.tvLong.setText("Long: " + obj.getDouble("longitude"));
                } else {
                    holder.tvLat.setText("Price Update");
                    holder.tvLong.setText("");
                }

                // Handle fuel display
                if (obj.has("prices")) {
                    // New Scan logic
                    JSONArray pricesArray = obj.getJSONArray("prices");
                    holder.tvDetails.setText("Fuels: " + pricesArray.length());
                    // If you want to list them here too, use the same loop logic as below
                } else if (obj.has("fuels")) {
                    // Update Task logic
                    JSONArray fArray = new JSONArray(obj.getString("fuels"));

                    // IMPORTANT: setText clears the old data from recycled views
                    holder.tvDetails.setText("Updating: " + fArray.length() + " fuels");

                    for (int i = 0; i < fArray.length(); i++) {
                        JSONObject fuelObj = fArray.getJSONObject(i);

                        // Use "name" as per your latest snippet
                        String name = fuelObj.optString("name", "Unknown Fuel");
                        double price = fuelObj.optDouble("price", 0.00);

                        // Fixed the format string typo here
                        holder.tvDetails.append("\n" + name + ": ₱" + String.format("%.2f", price));
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvStation, tvTime, tvDetails, tvLat, tvLong;
            ViewHolder(View itemView) {
                super(itemView);
                tvStation = itemView.findViewById(R.id.tv_q_station);
                tvTime = itemView.findViewById(R.id.tv_q_datetime);
                tvDetails = itemView.findViewById(R.id.tv_q_details);
                tvLat = itemView.findViewById(R.id.tv_q_lat);
                tvLong = itemView.findViewById(R.id.tv_q_long);
            }
        }
    }

    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String displayMunicipalityToast(double lat, double lon) {
        // Check if fragment is still attached to prevent crashes
        if (!isAdded() || getContext() == null) return "";

        android.location.Geocoder geocoder = new android.location.Geocoder(requireContext(), java.util.Locale.getDefault());
        try {
            List<android.location.Address> addresses = geocoder.getFromLocation(lat, lon, 1);

            if (addresses != null && !addresses.isEmpty()) {
                android.location.Address address = addresses.get(0);
                String municipality = address.getLocality();

                if (municipality == null) {
                    municipality = address.getSubAdminArea();
                }

                if (municipality != null) {
                    // Ensure Toast runs on UI thread if this is called from a background thread
                    String finalMunicipality = municipality;
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "Location: " + finalMunicipality.toUpperCase(), Toast.LENGTH_SHORT).show()
                    );
                    return municipality.toUpperCase();
                }
            }
        } catch (IOException e) {
            Log.e("Geocoder", "Network or service unavailable", e);
        }

        return "";
    }
}