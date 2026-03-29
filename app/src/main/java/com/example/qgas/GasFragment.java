package com.example.qgas;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class GasFragment extends Fragment {

    private TextView tvResults;
    private EditText etStation, etLat, etLong;
    private ImageView ivPreview, ivStationPhoto;
    private Button btnCapture, btnSave, btnCaptureStation, btnAddItem;
    private File photoFile, stationPhotoFile;
    private RecyclerView rvGasList;
    private GasAdapter adapter;
    private ArrayList<Gas> gasDataList = new ArrayList<>();
    private boolean isStationPhoto = false;

    private FusedLocationProviderClient fusedLocationClient;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (isStationPhoto) {
                        if (stationPhotoFile != null && stationPhotoFile.exists()) {
                            Bitmap bitmap = BitmapFactory.decodeFile(stationPhotoFile.getAbsolutePath());
                            ivStationPhoto.setImageBitmap(bitmap);
                            ivStationPhoto.setVisibility(View.VISIBLE);

                            // Update Step 2 button text
                            btnCaptureStation.setText("Retry Station Photo");

                            btnSave.setVisibility(View.VISIBLE);
                            tvResults.setText("Station photo captured! You can now save the data.");
                        }
                    } else {
                        if (photoFile != null && photoFile.exists()) {
                            Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                            ivPreview.setImageBitmap(bitmap);

                            // Update Step 1 button text
                            btnCapture.setText("Retry taking Picture");

                            processImageForGas(bitmap);
                        }
                    }
                }
            });

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                Boolean cameraGranted = result.getOrDefault(Manifest.permission.CAMERA, false);
                Boolean locationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                if (cameraGranted && locationGranted) {
                    startCameraAndGps();
                } else {
                    Toast.makeText(getContext(), "Permissions required", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_gas, container, false);

        etStation = view.findViewById(R.id.et_station);
        etLat = view.findViewById(R.id.et_lat);
        etLong = view.findViewById(R.id.et_long);
        tvResults = view.findViewById(R.id.tv_results);
        ivPreview = view.findViewById(R.id.iv_preview);
        ivStationPhoto = view.findViewById(R.id.iv_station_photo);
        btnCapture = view.findViewById(R.id.btn_capture);
        btnSave = view.findViewById(R.id.btn_save);
        btnCaptureStation = view.findViewById(R.id.btn_capture_station);
        btnAddItem = view.findViewById(R.id.btn_add_item);

        btnSave.setVisibility(View.GONE);
        btnCaptureStation.setVisibility(View.GONE);
        rvGasList = view.findViewById(R.id.rv_gas_list);

        rvGasList.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new GasAdapter(gasDataList);
        rvGasList.setAdapter(adapter);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        btnCapture.setOnClickListener(v -> {
            isStationPhoto = false;
            checkPermissionsAndStart();
        });

        btnCaptureStation.setOnClickListener(v -> {
            isStationPhoto = true;
            startCamera();
        });

        btnSave.setOnClickListener(v -> saveGasData());
        fetchGpsCoordinates();

        btnAddItem.setOnClickListener(v -> {
            gasDataList.add(new Gas("New Fuel", 0.0f));
            adapter.notifyItemInserted(gasDataList.size() - 1);
            rvGasList.scrollToPosition(gasDataList.size() - 1);

            // Ensure save buttons are visible if we have items
            btnSave.setVisibility(View.VISIBLE);
            btnCaptureStation.setVisibility(View.VISIBLE);
        });

        return view;
    }

    private void saveGasData() {
        if (gasDataList.isEmpty()) {
            Toast.makeText(getContext(), "Nothing to scan.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String station = etStation.getText().toString();
            double lat = Double.parseDouble(etLat.getText().toString().isEmpty() ? "0" : etLat.getText().toString());
            double lon = Double.parseDouble(etLong.getText().toString().isEmpty() ? "0" : etLong.getText().toString());
            String currentTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());

            String gasPath = (photoFile != null) ? photoFile.getAbsolutePath() : "";
            String stationPath = (stationPhotoFile != null) ? stationPhotoFile.getAbsolutePath() : "";

            JSONObject scanJson = new JSONObject();
            scanJson.put("station", station);
            scanJson.put("latitude", lat);
            scanJson.put("longitude", lon);
            scanJson.put("timestamp", currentTime);
            scanJson.put("gasImagePath", gasPath);
            scanJson.put("stationImagePath", stationPath);

            JSONArray gasArray = new JSONArray();
            for (Gas g : gasDataList) {
                JSONObject item = new JSONObject();
                item.put("name", g.getName());
                item.put("price", g.getPrice());
                gasArray.put(item);
            }
            scanJson.put("prices", gasArray);

            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", android.content.Context.MODE_PRIVATE);
            String existingQueue = prefs.getString("queue_data", "[]");
            JSONArray queue = new JSONArray(existingQueue);
            queue.put(scanJson);

            prefs.edit().putString("queue_data", queue.toString()).apply();
            Toast.makeText(getContext(), "Saved to Queue!", Toast.LENGTH_LONG).show();
            resetUI();

        } catch (Exception e) {
            Toast.makeText(getContext(), "Error saving data", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetUI() {
        gasDataList.clear();
        adapter.notifyDataSetChanged();
        etStation.setText("");

        // Reset button texts to original
        btnCapture.setText("Take Picture of Gas Sign");
        btnCaptureStation.setText("Step 2: Take Photo of Station");

        ivPreview.setImageResource(0);
        ivStationPhoto.setImageResource(0);
        ivStationPhoto.setVisibility(View.GONE);
        btnCaptureStation.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndGps();
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        }
    }

    private void startCameraAndGps() {
        fetchGpsCoordinates();
        startCamera();
    }

    private void fetchGpsCoordinates() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            CancellationTokenSource cts = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                    .addOnSuccessListener(requireActivity(), location -> {
                        if (location != null) {
                            etLat.setText(String.valueOf(location.getLatitude()));
                            etLong.setText(String.valueOf(location.getLongitude()));
                        }
                    });
        }
    }

    private void startCamera() {
        try {
            String fileName = isStationPhoto ? "STATION_" : "GAS_IMG_";
            File file = File.createTempFile(fileName, ".jpg", requireContext().getExternalCacheDir());

            if (isStationPhoto) stationPhotoFile = file;
            else photoFile = file;

            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            Toast.makeText(getContext(), "Storage error", Toast.LENGTH_SHORT).show();
        }
    }

    private void processImageForGas(Bitmap bitmap) {
        tvResults.setText("Analyzing image...");
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        recognizer.process(image)
                .addOnSuccessListener(this::analyzeGasText)
                .addOnFailureListener(e -> tvResults.setText("OCR Error: " + e.getMessage()));
    }

    private void analyzeGasText(Text visionText) {
        String stationName = "Unknown Station";
        Pattern pricePattern = Pattern.compile("\\d{1,3}[.,]\\d{2,3}");
        boolean brandFound = false;
        ArrayList<String> temp = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            String line = block.getText().trim();
            if (!brandFound && line.matches("^[a-zA-Z\\s]+$") && line.length() > 3) {
                stationName = line;
                brandFound = true;
            }

            String lowerLine = line.toLowerCase();
            if (lowerLine.contains("reg") || lowerLine.contains("unl") ||
                    lowerLine.contains("pre") || lowerLine.contains("die") ||
                    lowerLine.contains("plus") || lowerLine.contains("plat") ||
                    lowerLine.contains("sil") || lowerLine.contains("gaso") ||
                    lowerLine.contains("xcs") || lowerLine.contains("blaz") ||
                    lowerLine.contains("xtra") || lowerLine.contains("ext") ||
                    lowerLine.contains("pul") || lowerLine.contains("uni") ||
                    lowerLine.contains("pow") || lowerLine.contains("tur")) {
                temp.add(line);
            } else {
                Matcher m = pricePattern.matcher(line);
                while (m.find()) {
                    temp.add("Price: " + m.group());
                }
            }
        }

        ArrayList<Gas> gasList = new ArrayList<>();
        boolean flag = false;
        String fname = "";
        float fprice = 0;

        for (String line : temp) {
            if (line.contains("Price:")) {
                if (flag) {
                    gasList.add(new Gas(fname, fprice));
                    fname = "";
                }
                String[] parts = line.split(":");
                fprice = Float.parseFloat(parts[1].trim().replace(",", "."));
                flag = true;
            } else if (line.toLowerCase().contains("plat")) {
                // Special handling for Platinum if it contains both name and price
                String tfn = line.replaceAll("\\d+", "").trim();
                String priceStr = line.replaceAll("[^\\d.]", "");
                if (!priceStr.isEmpty()) {
                    gasList.add(new Gas(tfn, Float.parseFloat(priceStr)));
                }
            } else {
                fname = line.trim();
            }
        }

        if (flag) gasList.add(new Gas(fname, fprice));

        etStation.setText(stationName);

        if (gasList.isEmpty()) {
            gasList = analyzeGasText2(visionText);
        }

        // 1. Filter and sort by price descending
        List<Gas> dieselOnly = gasList.stream()
                .filter(gas -> gas.getName().toLowerCase().contains("diesel"))
                .sorted(Comparator.comparing(Gas::getPrice).reversed())
                .collect(Collectors.toList());

        // 2. Update names based on position
        if (dieselOnly.size() == 1) {
            dieselOnly.get(0).setName("Diesel - Standard");
        } else if (dieselOnly.size() > 1) {
            // Highest price (index 0) gets Premium
            dieselOnly.get(0).setName("Diesel - Premium");

            // All others get Standard
            dieselOnly.stream()
                    .skip(1)
                    .forEach(gas -> gas.setName("Diesel - Standard"));
        }

        List<Gas> noDiesel = gasList.stream()
                .filter(gas -> !gas.getName().toLowerCase().contains("diesel"))
                .collect(Collectors.toList());

        noDiesel.forEach(gas -> {
            String newName = gas.getName().toLowerCase().contains("prem")
                    ? "Gasoline - Premium"
                    : "Gasoline - Standard";
            gas.setName(newName);
        });

        List<Gas> allGasProcessed = new ArrayList<>();
        allGasProcessed.addAll(dieselOnly);
        allGasProcessed.addAll(noDiesel);

        if (allGasProcessed.isEmpty()) {
            tvResults.setText("No fuel data detected.");
            btnSave.setVisibility(View.GONE);
            btnCaptureStation.setVisibility(View.GONE);
        } else {
            tvResults.setText("Success! Now take a photo of the station storefront.");
            btnCaptureStation.setVisibility(View.VISIBLE);
            gasDataList.clear();
            gasDataList.addAll(allGasProcessed);
            adapter.notifyDataSetChanged();
        }
    }

    private ArrayList<Gas> analyzeGasText2(Text visionText) {
        ArrayList<Gas> detectedGases = new ArrayList<>();
        String lastFuelName = "";
        Pattern pricePattern = Pattern.compile("\\d{1,3}([.,]\\d{2,3})?");

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText().trim();
                String lowerText = text.toLowerCase();

                if (lowerText.contains("diesel") || lowerText.contains("unleaded") ||
                        lowerText.contains("premium") || lowerText.contains("regular")) {
                    lastFuelName = text;
                } else if (!lastFuelName.isEmpty()) {
                    Matcher m = pricePattern.matcher(text);
                    if (m.find()) {
                        try {
                            float price = Float.parseFloat(m.group().replace(",", "."));
                            detectedGases.add(new Gas(lastFuelName, price));
                            lastFuelName = "";
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return detectedGases;
    }
}