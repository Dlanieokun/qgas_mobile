package com.example.qgas;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class GasFragment extends Fragment {

    private TextView tvResults;
    private EditText etStation, etLat, etLong;
    private ImageView ivPreview, ivStationPhoto;
    private Button btnCapture, btnSave, btnCaptureStation, btnAddItem;

    // Persistent file references
    private File photoFile, stationPhotoFile;
    // Temporary reference to handle cancellations
    private File tempCaptureFile;

    private ArrayList<Gas> gasDataList = new ArrayList<>();
    private View cvStationPreview;
    private boolean isStationPhoto = false;
    private EditText etDieselPremium, etDieselStandard, etGasPremium, etGasStandard;

    private FusedLocationProviderClient fusedLocationClient;
    private final OkHttpClient client = UnsafeOkHttpHelper.getUnsafeOkHttpClient();

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (isStationPhoto) {
                        stationPhotoFile = tempCaptureFile;
                        if (stationPhotoFile != null && stationPhotoFile.exists()) {
                            Bitmap bitmap = handleImageRotation(stationPhotoFile.getAbsolutePath());

                            compressImageFile(stationPhotoFile);

                            ivStationPhoto.setImageBitmap(bitmap);
                            cvStationPreview.setVisibility(View.VISIBLE);
                            btnCaptureStation.setText("Retry Station Photo");
                            btnSave.setVisibility(View.VISIBLE);
                            tvResults.setText("Station photo captured!");

                            fetchGpsCoordinates();
                        }
                    } else {
                        photoFile = tempCaptureFile;
                        if (photoFile != null && photoFile.exists()) {
                            Bitmap bitmap = handleImageRotation(photoFile.getAbsolutePath());

                            compressImageFile(photoFile);

                            fetchGpsCoordinates();

                            ivPreview.setImageBitmap(bitmap);
                            btnCapture.setText("Retry taking Picture");
                            processImageForGas(bitmap);
                        }
                    }
                } else {
                    // Capture cancelled: Clean up the unused temp file
                    if (tempCaptureFile != null && tempCaptureFile.exists()) {
                        tempCaptureFile.delete();
                    }
                    Toast.makeText(getContext(), "Cancelled - kept previous data", Toast.LENGTH_SHORT).show();
                }
            });

    private Bitmap handleImageRotation(String path) {
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        try {
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                default: return bitmap;
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (IOException e) {
            return bitmap;
        }
    }

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                if (result.getOrDefault(Manifest.permission.CAMERA, false) &&
                        result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
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
        etDieselPremium = view.findViewById(R.id.et_diesel_premium);
        etDieselStandard = view.findViewById(R.id.et_diesel_standard);
        etGasPremium = view.findViewById(R.id.et_gas_premium);
        etGasStandard = view.findViewById(R.id.et_gas_standard);
        cvStationPreview = view.findViewById(R.id.cv_station_preview);

        btnSave.setVisibility(View.GONE);
        btnCaptureStation.setVisibility(View.GONE);

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

        return view;
    }

    private void startCamera() {
        try {
            String prefix = isStationPhoto ? "STATION_TMP_" : "GAS_TMP_";

            fetchGpsCoordinates();
            // Create a temporary file first
            tempCaptureFile = File.createTempFile(prefix, ".jpg", requireContext().getExternalCacheDir());

            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", tempCaptureFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            Toast.makeText(getContext(), "Storage error", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndGps();
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    private void startCameraAndGps() {
        fetchGpsCoordinates();

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

        String text = visionText.getText();
        String[] lines = text.split("\n");

        for (String line : lines) {
            Log.d("VisionText", line);
        }


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
                    lowerLine.contains("pow") || lowerLine.contains("tur") || lowerLine.contains("reg")) {
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
        double fprice = 0;

        for (String line : temp) {
            if (line.contains("Price:")) {
                if (flag) {
                    gasList.add(new Gas(fname, fprice));
                    fname = "";
                }
                String[] parts = line.split(":");
                fprice = Double.parseDouble(parts[1].trim().replace(",", "."));
                flag = true;
            } else if (line.toLowerCase().contains("plat")) {
                // Special handling for Platinum if it contains both name and price
                String tfn = line.replaceAll("\\d+", "").trim();
                String priceStr = line.replaceAll("[^\\d.]", "");
                if (!priceStr.isEmpty()) {
                    gasList.add(new Gas(tfn, Double.parseDouble(priceStr)));
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

        if (gasList.isEmpty()) {
            gasList = analyzeGasText3(visionText);
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
                .sorted(Comparator.comparing(Gas::getPrice).reversed())
                .collect(Collectors.toList());

        noDiesel.forEach(gas -> {
            String newName = gas.getName().toLowerCase().contains("prem")
                    ? "Gasoline - Premium"
                    : "Gasoline - Standard";
            gas.setName(newName);
        });

        boolean hasPremium = noDiesel.stream()
                .anyMatch(gas -> "Gasoline - Premium".equals(gas.getName()));

        if (noDiesel.size() == 1) {
            noDiesel.get(0).setName("Gasoline - Standard");
        } else if (noDiesel.size() > 1 && !hasPremium) {
            // Highest price (index 0) gets Premium
            noDiesel.get(0).setName("Gasoline - Premium");

            // All others get Standard
            noDiesel.stream()
                    .skip(1)
                    .forEach(gas -> gas.setName("Gasoline - Standard"));
        }

        List<Gas> allGasProcessed = new ArrayList<>();
        allGasProcessed.addAll(dieselOnly);
        allGasProcessed.addAll(noDiesel);

        allGasProcessed.forEach(gas -> {
            if (gas.getPrice() > 900 ) {
                double exact_price = gas.getPrice() / 100;
                gas.setPrice(exact_price);
            }
        });

        if (allGasProcessed.isEmpty()) {
            tvResults.setText("No fuel data detected.");
            btnSave.setVisibility(View.GONE);
            btnCaptureStation.setVisibility(View.GONE);
            Toast.makeText(getContext(), "No fuel data detected.", Toast.LENGTH_SHORT).show();
            etDieselPremium.setText("0.00");
            etDieselStandard.setText("0.00");
            etGasPremium.setText("0.00");
            etGasStandard.setText("0.00");
            resetUI();
        } else {
            tvResults.setText("Success! Now take a photo of the station storefront.");
            btnCaptureStation.setVisibility(View.VISIBLE);
            gasDataList.clear();
            gasDataList.addAll(allGasProcessed);
            etDieselPremium.setText("0.00");
            etDieselStandard.setText("0.00");
            etGasPremium.setText("0.00");
            etGasStandard.setText("0.00");

            for (Gas gas : allGasProcessed) {
                String name = gas.getName();
                String price = String.format("%.2f", gas.getPrice());

                if (name.equals("Diesel - Premium")) etDieselPremium.setText(price);
                else if (name.equals("Diesel - Standard")) etDieselStandard.setText(price);
                else if (name.equals("Gasoline - Premium")) etGasPremium.setText(price);
                else if (name.equals("Gasoline - Standard")) etGasStandard.setText(price);
            }
        }
    }

    private void saveGasData() {
        if (gasDataList.isEmpty()) {
            Toast.makeText(getContext(), "Nothing to scan.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            boolean required = false;
            String text_price = "";
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
//            for (Gas g : gasDataList) {
//                JSONObject item = new JSONObject();
//                item.put("name", g.getName());
//                item.put("price", g.getPrice());
//                gasArray.put(item);
//                if (g.getPrice() > 200){
//                    required = true;
//                    text_price = "high";
//                }
//                if (g.getPrice() < 50) {
//                    required = true;
//                    text_price = "low";
//                }
//            }
            double d_premium = Double.parseDouble(etDieselPremium.getText().toString());
            double d_standard = Double.parseDouble(etDieselStandard.getText().toString());
            double g_premium = Double.parseDouble(etGasPremium.getText().toString());
            double g_standard = Double.parseDouble(etGasStandard.getText().toString());


            if (d_premium != 0.00){
                if (d_premium > 200.00){
                    required = true;
                    text_price = "high";
                } else if (d_premium < 50.00) {
                    required = true;
                    text_price = "low";
                } else {
                    JSONObject item = new JSONObject();
                    item.put("name", "Diesel - Premium");
                    item.put("price", d_premium);
                    gasArray.put(item);
                }
            }

            if (d_standard != 0.00){
                if (d_standard > 200.00){
                    required = true;
                    text_price = "high";
                } else if (d_standard < 50.00) {
                    required = true;
                    text_price = "low";
                } else {
                    JSONObject item = new JSONObject();
                    item.put("name", "Diesel - Standard");
                    item.put("price", d_standard);
                    gasArray.put(item);
                }
            }

            if (g_premium != 0.00) {
                if (g_premium > 200.00) {
                    required = true;
                    text_price = "high";
                } else if (g_premium < 50.00) {
                    required = true;
                    text_price = "low";
                }else {
                    JSONObject item = new JSONObject();
                    item.put("name", "Gasoline - Premium");
                    item.put("price", g_premium);
                    gasArray.put(item);
                }
            }

            if (g_standard != 0.00) {
                if (g_standard > 200.00) {
                    required = true;
                    text_price = "high";
                } else if (g_standard < 50.00) {
                    required = true;
                    text_price = "low";
                } else {
                    JSONObject item = new JSONObject();
                    item.put("name", "Gasoline - Standard");
                    item.put("price", g_standard);
                    gasArray.put(item);
                }
            }
            if (required){
                Toast.makeText(getContext(), "Gas prices are too " + text_price, Toast.LENGTH_LONG).show();
            }else if (lat == 0 || lon == 0) {
                Toast.makeText(getContext(), "the location is not yet set", Toast.LENGTH_LONG).show();
            } else {
                scanJson.put("prices", gasArray);
                checkNearbyAndSave(lat, lon, scanJson);

            }

        } catch (Exception e) {
            Toast.makeText(getContext(), "Error saving data", Toast.LENGTH_SHORT).show();
        }
    }
    private void checkNearbyAndSave(double lat, double lon, JSONObject scanJson) {
        SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
        String apiUrl = settings.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282");
        String url = apiUrl + "/qgas/public/api/station-fuel/nearby-station?latitude=" + lat + "&longitude=" + lon + "&meters=100";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // On network failure, save to queue by default
                requireActivity().runOnUiThread(() -> {
                    try { savequeue(scanJson); } catch (Exception ignored) {}
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    boolean stationExists = false;
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        JSONArray stations = json.optJSONArray("data");
                        stationExists = (stations != null && stations.length() > 0);
                    }

                    final boolean finalExists = stationExists;
                    requireActivity().runOnUiThread(() -> {
                        if (finalExists) {
                            // Create the Analog/Dialog choice
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Nearby Station Detected")
                                    .setMessage("Station exists nearby! Do you want to continue saving?")
                                    .setPositiveButton("Yes", (dialog, which) -> {
                                        try {
                                            savequeue(scanJson);
                                        } catch (JSONException e) {
                                            e.printStackTrace();
                                        }
                                    })
                                    .setNegativeButton("No", (dialog, which) -> {
                                        dialog.dismiss();
                                        // Redirect to Map via MainActivity
                                        if (getActivity() instanceof MainActivity) {
                                            ((MainActivity) getActivity()).navigateToMap();
                                        }
                                    })
                                    .show();
                        } else {
                            // No station nearby, save automatically
                            try {
                                savequeue(scanJson);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    response.close();
                }
            }
        });
    }
    private void savequeue(JSONObject scan) throws JSONException {
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("GasQueue", android.content.Context.MODE_PRIVATE);
        String existingQueue = prefs.getString("queue_data", "[]");
        JSONArray queue = new JSONArray(existingQueue);
        queue.put(scan);

        prefs.edit().putString("queue_data", queue.toString()).apply();
        Toast.makeText(getContext(), "Saved to Queue!", Toast.LENGTH_LONG).show();
        resetUI();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).navigateToHome();
        }
    }

    private void resetUI() {
        gasDataList.clear();
        etStation.setText("");
        etDieselPremium.setText("0.00");
        etDieselStandard.setText("0.00");
        etGasPremium.setText("0.00");
        etGasStandard.setText("0.00");
        btnCapture.setText("Take Picture of Gas Prices");
        btnCaptureStation.setText("Step 2: Take Photo of Station");
        ivPreview.setImageResource(0);
        ivStationPhoto.setImageResource(0);
        cvStationPreview.setVisibility(View.GONE);
        btnCaptureStation.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
        photoFile = null;
        stationPhotoFile = null;
    }



    private void compressImageFile(File file) {
        if (file == null || !file.exists()) return;

        try {
            // 1. Get original dimensions without loading the whole image into memory
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            int maxWidth = 1200;
            int maxHeight = 1200;
            int width = options.outWidth;
            int height = options.outHeight;

            // 2. Calculate the scaling factor
            int inSampleSize = 1;
            if (width > maxWidth || height > maxHeight) {
                final int halfHeight = height / 2;
                final int halfWidth = width / 2;
                while ((halfHeight / inSampleSize) >= maxHeight && (halfWidth / inSampleSize) >= maxWidth) {
                    inSampleSize *= 2;
                }
            }

            // 3. Decode the bitmap with inSampleSize
            options.inJustDecodeBounds = false;
            options.inSampleSize = inSampleSize;
            Bitmap scaledBitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            // 4. Overwrite the file with aggressive compression
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);

            // Quality 70 is the "sweet spot" for high compression with low visible artifacts
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);

            out.flush();
            out.close();
            scaledBitmap.recycle();

            Log.d("ImageCompress", "Final size: " + (file.length() / 1024) + " KB");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Bitmap processAndSaveImage(File file) {
        if (file == null || !file.exists()) return null;

        try {
            // 1. Load bounds to check rotation
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            // 2. Decode file
            options.inJustDecodeBounds = false;
            // Use inSampleSize to load a smaller version into memory (reduces OOM risks)
            options.inSampleSize = 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);

            // 3. Handle Rotation
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            }

            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();

            // 4. Compress and Overwrite the File
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out); // Quality 60 is very compact
            out.flush();
            out.close();

            return rotatedBitmap;
        } catch (IOException e) {
            e.printStackTrace();
            return BitmapFactory.decodeFile(file.getAbsolutePath());
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
                            double price = Double.parseDouble(m.group().replace(",", "."));
                            detectedGases.add(new Gas(lastFuelName, price));
                            lastFuelName = "";
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return detectedGases;
    }
    private ArrayList<Gas> analyzeGasText3(Text visionText) {
        ArrayList<Gas> detectedGases = new ArrayList<>();

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText().trim();
                String lowerLine = text.toLowerCase();
//                Log.e("analyzeGasText3", "analyzeGasText3: " + lowerLine);
                if (lowerLine.contains("reg") || lowerLine.contains("unl") ||
                        lowerLine.contains("pre") || lowerLine.contains("die") ||
                        lowerLine.contains("plus") || lowerLine.contains("plat") ||
                        lowerLine.contains("sil") || lowerLine.contains("gaso") ||
                        lowerLine.contains("xcs") || lowerLine.contains("blaz") ||
                        lowerLine.contains("xtra") || lowerLine.contains("ext") ||
                        lowerLine.contains("pul") || lowerLine.contains("uni") ||
                        lowerLine.contains("pow") || lowerLine.contains("tur") || lowerLine.contains("reg")) {
//                    temp.add(line);

                    String tfn = text.replaceAll("\\d+", "").trim();
                    String priceStr = text.replaceAll("[^\\d.]", "");
                    if (!priceStr.isEmpty()) {
                        detectedGases.add(new Gas(tfn, Double.parseDouble(priceStr)));
                    } else {
                        detectedGases.add(new Gas(tfn, 0.00));
                    }
                }
            }
        }


        return detectedGases;
    }
}