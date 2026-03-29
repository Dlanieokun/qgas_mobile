package com.example.qgas;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MapFragment extends Fragment {

    private MapView map = null;
    private FusedLocationProviderClient fusedLocationClient;
    private final OkHttpClient client = new OkHttpClient();

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

    // Helper to resize markers so they don't cover the whole map
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
        String apiUrl = settings.getString("api_base_url", "http://113.19.12.104:8180/qgas/public/api");
        String url = apiUrl + "/station-fuel/nearby-station?latitude=" + lat + "&longitude=" + lon;

        Request request = new Request.Builder().url(url).addHeader("Accept", "application/json").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) { e.printStackTrace(); }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string(); // Read body ONCE
                        JSONObject json = new JSONObject(responseData);
                        JSONArray stations = json.getJSONArray("data");
                        if (isAdded()) requireActivity().runOnUiThread(() -> displayMarkers(stations));
                    } catch (Exception e) { e.printStackTrace(); }
                }
            }
        });
    }

    private void displayMarkers(JSONArray stations) {
        if (getContext() == null) return;

        // Set marker icon size to 100x100 pixels
        Drawable gasIcon = getResizedIcon(R.drawable.ic_gas_station, 100);

        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String baseImgUrl = settings.getString("api_base_url", "").replace("/api", "");

        try {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject station = stations.getJSONObject(i);

                // Get the specific photo path for THIS station
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
                baseImgUrl = baseImgUrl.replace("public", "storage");
                // Build full URL: base + / + photoPath
                final String finalPhotoUrl = baseImgUrl + (photoPath.startsWith("/") ? "" : "/") + photoPath;

                m.setInfoWindow(new MarkerInfoWindow(R.layout.custom_info_window, map) {
                    @Override
                    public void onOpen(Object item) {
                        View view = getView();
                        TextView title = view.findViewById(R.id.bubble_title);
                        TextView desc = view.findViewById(R.id.bubble_description);
                        ImageView img = view.findViewById(R.id.bubble_image);

                        title.setText(m.getTitle());
                        desc.setText(m.getSnippet());

                        // Load the specific station.photo
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

    @Override
    public void onResume() { super.onResume(); if(map != null) map.onResume(); }
    @Override
    public void onPause() { super.onPause(); if(map != null) map.onPause(); }
}