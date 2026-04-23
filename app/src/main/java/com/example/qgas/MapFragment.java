package com.example.qgas;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MapFragment extends Fragment {

    private MapView map = null;
    private FusedLocationProviderClient fusedLocationClient;
    private final OkHttpClient client = UnsafeOkHttpHelper.getUnsafeOkHttpClient();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Configuration.getInstance().load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()));
        View view = inflater.inflate(R.layout.fragment_map, container, false);

        map = view.findViewById(R.id.mapview);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        checkPermissionsAndGetLocation();

        return view;
    }

    private void checkPermissionsAndGetLocation() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 100);
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && isAdded()) initMap(location);
        });
    }

    private void initMap(Location location) {
        GeoPoint startPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        map.getController().setZoom(16.0);
        map.getController().setCenter(startPoint);

        Marker userMarker = new Marker(map);
        userMarker.setPosition(startPoint);
        userMarker.setTitle("You are here");
        userMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        map.getOverlays().add(userMarker);

        fetchNearbyStations(location.getLatitude(), location.getLongitude());
    }

    private Drawable getResizedIcon(int resourceId, int size) {
        Drawable drawable = ContextCompat.getDrawable(requireContext(), resourceId);
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true);
            return new BitmapDrawable(getResources(), resizedBitmap);
        }
        return drawable;
    }

    private void fetchNearbyStations(double lat, double lon) {
        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String apiUrl = settings.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282");
        String url = apiUrl + "/qgas/public/api/station-fuel/nearby-station?latitude=" + lat + "&longitude=" + lon + "&meters=" + 5000;

        Request request = new Request.Builder().url(url).addHeader("Accept", "application/json").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) { e.printStackTrace(); }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JSONObject json = new JSONObject(responseData);
                        JSONArray stations = json.getJSONArray("data");
                        if (isAdded()) requireActivity().runOnUiThread(() -> displayMarkers(stations));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), "Stations: " + response.message(), Toast.LENGTH_SHORT).show();
                        });
                        Log.e("MapFragment", "Stations: " + response);
                    }
                }
            }
        });
    }

    private void displayMarkers(JSONArray stations) {
        if (getContext() == null) return;

        Drawable gasIcon = getResizedIcon(R.drawable.ic_gas_station, 100);
        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String baseImgUrl = settings.getString("api_base_url", "").replace("/api", "");

        try {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject station = stations.getJSONObject(i);
                String photoPath = station.optString("station_photo", "");

                StringBuilder priceBuilder = new StringBuilder();
                if (station.has("data") && !station.isNull("data")) {
                    JSONArray fuels = new JSONArray(station.getString("data"));
                    for (int j = 0; j < fuels.length(); j++) {
                        JSONObject f = fuels.getJSONObject(j);
                        priceBuilder.append(f.getString("name")).append(": ₱").append(f.getString("price")).append("\n");
                    }
                }

                Marker m = new Marker(map);
                m.setPosition(new GeoPoint(station.getDouble("latitude"), station.getDouble("longitude")));
                m.setTitle(station.getString("station_name"));
                m.setSnippet(priceBuilder.toString().trim());
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

                if (gasIcon != null) m.setIcon(gasIcon);

                String finalBaseUrl = baseImgUrl.replace("public", "storage");
                final String finalPhotoUrl = finalBaseUrl + "/qgas/storage" + (photoPath.startsWith("/") ? "" : "/") + photoPath;

                m.setInfoWindow(new MarkerInfoWindow(R.layout.custom_info_window, map) {
                    @Override
                    public void onOpen(Object item) {
                        View view = getView();
                        TextView title = view.findViewById(R.id.bubble_title);
                        TextView desc = view.findViewById(R.id.bubble_description);
                        ImageView img = view.findViewById(R.id.bubble_image);
                        Button btnUpdate = view.findViewById(R.id.bubble_update_button);

                        title.setText(m.getTitle());
                        desc.setText(m.getSnippet());

                        // Set logic for the Update Button
                        btnUpdate.setOnClickListener(v -> {
                            handleUpdateClick(station);
                        });

                        if (isAdded() && !photoPath.isEmpty()) {
                            Glide.with(requireContext())
                                    .load(finalPhotoUrl)
                                    .placeholder(R.drawable.ic_gas_station)
                                    .error(R.drawable.ic_gas_station)
                                    .into(img);
                        }
                    }
                });

                map.getOverlays().add(m);
            }
            map.invalidate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // Helper method to handle button action
    private void handleUpdateClick(JSONObject station) {
        try {
            String name = station.getString("station_name");
            Toast.makeText(requireContext(), "Opening update for: " + name, Toast.LENGTH_SHORT).show();
            // Implement navigation to your Update Price Fragment/Activity here
            String stationData = station.toString();
            UpdatePriceFragment updateFragment = UpdatePriceFragment.newInstance(stationData);
            updateFragment.show(getChildFragmentManager(), "UpdatePriceBottomSheet");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onResume() { super.onResume(); if(map != null) map.onResume(); syncOfflineQueue(); }
    @Override
    public void onPause() { super.onPause(); if(map != null) map.onPause(); }

    private OkHttpClient getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
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

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // Create an ssl socket factory with our all-trusting manager
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void syncOfflineQueue() {
        SharedPreferences prefs = requireContext().getSharedPreferences("UpdatePriceQueue", Context.MODE_PRIVATE);
        String pendingData = prefs.getString("pending_updates", "[]");

        try {
            JSONArray queue = new JSONArray(pendingData);
            if (queue.length() > 0) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Syncing " + queue.length() + " pending updates...", Toast.LENGTH_SHORT).show());
                processNextInSyncQueue(0, queue);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processNextInSyncQueue(int index, JSONArray queue) {
        if (index >= queue.length()) {
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), "Offline data synced successfully!", Toast.LENGTH_SHORT).show());
            return;
        }

        try {
            JSONObject task = queue.getJSONObject(index);
            String pid = task.getString("pid");

            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("fuels", task.getString("fuels"))
                    .addFormDataPart("date_captured", task.getString("date_captured"))
                    .addFormDataPart("device_id", task.getString("device_id"));

            if (task.has("captured_uri")) {
                Uri uri = Uri.parse(task.getString("captured_uri"));
                addFileSync(builder, "photo", uri);
            }

            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String url = sp.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282") + "/qgas/public/api/station-fuel/" + pid;

            Request request = new Request.Builder().url(url).post(builder.build()).build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    processNextInSyncQueue(index + 1, queue);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        removeFromQueue(task);
                    }
                    processNextInSyncQueue(index + 1, queue);
                }
            });
        } catch (Exception e) {
            processNextInSyncQueue(index + 1, queue);
        }
    }

    private void addFileSync(MultipartBody.Builder builder, String key, Uri uri) throws IOException {
        File file = new File(requireContext().getExternalCacheDir(), "sync_temp.jpg");
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        }
        builder.addFormDataPart(key, file.getName(), RequestBody.create(file, MediaType.parse("image/jpeg")));
    }

    private void removeFromQueue(JSONObject taskToRemove) {
        SharedPreferences prefs = requireContext().getSharedPreferences("UpdatePriceQueue", Context.MODE_PRIVATE);
        try {
            JSONArray current = new JSONArray(prefs.getString("pending_updates", "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < current.length(); i++) {
                if (!current.getJSONObject(i).toString().equals(taskToRemove.toString())) {
                    updated.put(current.getJSONObject(i));
                }
            }
            prefs.edit().putString("pending_updates", updated.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }
}