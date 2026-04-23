package com.example.qgas;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.LinkedList;
import java.util.Queue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.*;

public class UpdatePriceFragment extends BottomSheetDialogFragment {

    //    private final Queue<Request> updateQueue = new LinkedList<>();
    private List<JSONObject> updateQueue = new ArrayList<>();
    private static final String PREFS_NAME = "UpdatePriceQueue";
    private static final String QUEUE_KEY = "pending_updates";
    private boolean isUploading = false;
    private String stationJson;
    private final OkHttpClient client = getUnsafeOkHttpClient();
    private ImageView imgStation, imgCaptured;
    private Uri uriStation, uriCaptured;
    private Uri cameraTempUri;
    private Button btnSave;
    private boolean isPickingStation = true;
    private final List<FuelEntry> fuelEntries = new ArrayList<>();

    private EditText etDieselPrem, etDieselStd, etGasPrem, etGasStd;

    private static class FuelEntry {
        EditText name;
        EditText input;
        FuelEntry(EditText n, EditText i) { this.name = n; this.input = i; }
    }

    public static UpdatePriceFragment newInstance(String data) {
        UpdatePriceFragment f = new UpdatePriceFragment();
        Bundle args = new Bundle();
        args.putString("station_data", data);
        f.setArguments(args);
        return f;
    }

    private final ActivityResultLauncher<Intent> actionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (cameraTempUri != null) {
                        if (isPickingStation) {
                            uriStation = cameraTempUri;

                            // Use Glide to load the image (Better than setImageURI for large files)
                            Glide.with(this)
                                    .load(uriStation)
                                    .skipMemoryCache(true) // Force reload if using same filename
                                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                    .into(imgStation);

                            btnSave.setVisibility(View.VISIBLE);
                        } else {
                            uriCaptured = cameraTempUri;

                            Glide.with(this)
                                    .load(uriCaptured)
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                    .into(imgCaptured);

                            runOcrOnCapturedImage(uriCaptured);
                            btnSave.setVisibility(View.VISIBLE);
                        }
                    }
                } else {
                    Toast.makeText(getContext(), "Camera cancelled.", Toast.LENGTH_SHORT).show();
                }
            }
    );

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) stationJson = getArguments().getString("station_data");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_update_price, container, false);

        imgStation = v.findViewById(R.id.update_station_image);
        imgCaptured = v.findViewById(R.id.update_captured_image);
        TextView title = v.findViewById(R.id.update_station_name);
        LinearLayout containerLayout = v.findViewById(R.id.fuel_input_container);
        btnSave = v.findViewById(R.id.btn_save_prices);
        etDieselPrem = v.findViewById(R.id.edit_diesel_premium);
        etDieselStd = v.findViewById(R.id.edit_diesel_standard);
        etGasPrem = v.findViewById(R.id.edit_gas_premium);
        etGasStd = v.findViewById(R.id.edit_gas_standard);

        try {
            JSONObject station = new JSONObject(stationJson);
            title.setText(station.optString("station_name"));
            String pid = station.getString("pid");

            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String base = sp.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282");

            loadPhoto(station.optString("station_photo"), imgStation, base + "/qgas/storage");
            loadPhoto(station.optString("photo"), imgCaptured, base + "/qgas/storage");

            String stationPath = station.optString("station_photo");
            String capturedPath = station.optString("photo");

            imgStation.setOnClickListener(view -> showSourceDialog(true));
            imgCaptured.setOnClickListener(view -> showSourceDialog(false));

            if (stationPath != null && !stationPath.isEmpty() && !stationPath.equals("null")) {
                String fullUrl = base + "/qgas/storage" + (stationPath.startsWith("/") ? "" : "/") + stationPath;

                downloadAndSetUri(fullUrl, "temp_station_" + System.currentTimeMillis() + ".jpg", true);
            }
            if (capturedPath != null && !capturedPath.isEmpty() && !capturedPath.equals("null")) {
                uriCaptured = Uri.parse(base + "/qgas/storage" + (capturedPath.startsWith("/") ? "" : "/") + capturedPath);
            }

            JSONArray fuels = new JSONArray(station.getString("data"));
            fuelEntries.clear();
            etDieselPrem.setText("0.00");
            etDieselStd.setText("0.00");
            etGasPrem.setText("0.00");
            etGasStd.setText("0.00");

            for (int i = 0; i < fuels.length(); i++) {
                JSONObject f = fuels.getJSONObject(i);
                String name = f.getString("name");
                String price = String.format("%.2f", (double) f.optDouble("price", 0.00));

                if (name.equals("Diesel - Premium")) etDieselPrem.setText(price);
                else if (name.equals("Diesel - Standard")) etDieselStd.setText(price);
                else if (name.equals("Gasoline - Premium")) etGasPrem.setText(price);
                else if (name.equals("Gasoline - Standard")) etGasStd.setText(price);
            }

            btnSave.setOnClickListener(view -> uploadAll(pid));
        } catch (Exception e) { e.printStackTrace(); }
        return v;
    }

    private void addFuelInput(LinearLayout container, String name, Double price) {
        EditText label = new EditText(getContext());
        label.setText(name);
        label.setBackground(null);
        label.setPadding(0, 10, 0, 5);
        container.addView(label);

        EditText input = new EditText(getContext());
        input.setText(price.toString());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(input);

        fuelEntries.add(new FuelEntry(label, input));
    }

    private void runOcrOnCapturedImage(Uri uri) {
        try {
            InputStream is = requireContext().getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            recognizer.process(image)
                    .addOnSuccessListener(this::processOcrResults)
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "OCR Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processOcrResults(Text visionText) {
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
        double fprice = 0;

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
                    gasList.add(new Gas(tfn, Double.parseDouble(priceStr)));
                }
            } else {
                fname = line.trim();
            }
        }

        if (flag) gasList.add(new Gas(fname, fprice));


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

        if (!allGasProcessed.isEmpty()) {
            displayProcessedGas(allGasProcessed);
            Toast.makeText(getContext(), "Detected " + allGasProcessed.size() + " fuel prices!", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(), "No matching prices found in image.", Toast.LENGTH_LONG).show();
        }
    }

    private void displayProcessedGas(List<Gas> processedList) {
        for (Gas gas : processedList) {
            String name = gas.getName().toLowerCase();
            double price = gas.getPrice();

            if (name.contains("diesel")) {
                if (name.contains("premium")) etDieselPrem.setText(String.valueOf(price));
                else etDieselStd.setText(String.valueOf(String.format("%.2f", (double) price)));
            } else if (name.contains("gasoline")) {
                if (name.contains("premium")) etGasPrem.setText(String.valueOf(price));
                else etGasStd.setText(String.valueOf(String.format("%.2f", (double) price)));
            }
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

    private void showSourceDialog(boolean isStation) {
        this.isPickingStation = isStation;
        openCamera();
    }

    private void openCamera() {
        try {
            File file = new File(requireContext().getExternalCacheDir(), "temp_" + System.currentTimeMillis() + ".jpg");
            cameraTempUri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    file);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraTempUri);
            actionLauncher.launch(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        actionLauncher.launch(i);
    }

    private void loadPhoto(String path, ImageView view, String base) {
        if (path != null && !path.isEmpty() && !path.equals("null")) {
            String url = base + (path.startsWith("/") ? "" : "/") + path;
            Glide.with(this).load(url).placeholder(R.drawable.ic_gas_station).into(view);
        }
    }

    private void uploadAll(String pid) {
        try {
            JSONArray updatedFuels = new JSONArray();
            addFuelToJson(updatedFuels, "Diesel - Premium", etDieselPrem);
            addFuelToJson(updatedFuels, "Diesel - Standard", etDieselStd);
            addFuelToJson(updatedFuels, "Gasoline - Premium", etGasPrem);
            addFuelToJson(updatedFuels, "Gasoline - Standard", etGasStd);

            String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            SharedPreferences settings = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String device_id = settings.getString("device_id", "");

            // Create a JSON object representing the task (matching HomeFragment style)
            JSONObject updateTask = new JSONObject();
            updateTask.put("pid", pid);
            updateTask.put("fuels", updatedFuels.toString());
            updateTask.put("date_captured", currentTime);
            updateTask.put("device_id", device_id);

            // Save paths for images if they exist
            if (uriCaptured != null) updateTask.put("captured_uri", uriCaptured.toString());

            // Add to persistent storage
            addToPersistentQueue(updateTask);

            Toast.makeText(getContext(), "Update queued", Toast.LENGTH_SHORT).show();

            // Start processing
            processNextInQueue(0);

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Invalid Input", Toast.LENGTH_LONG).show();
        }
    }

    private void addToPersistentQueue(JSONObject task) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray currentQueue = new JSONArray(prefs.getString(QUEUE_KEY, "[]"));
            currentQueue.put(task);
            prefs.edit().putString(QUEUE_KEY, currentQueue.toString()).apply();

            // Refresh local list
            updateQueue.clear();
            for (int i = 0; i < currentQueue.length(); i++) {
                updateQueue.add(currentQueue.getJSONObject(i));
            }
        } catch (JSONException e) { e.printStackTrace(); }
    }

    private void processNextInQueue(int index) {
        if (index >= updateQueue.size() || isUploading) return;

        isUploading = true;
        JSONObject task = updateQueue.get(index);

        try {
            String pid = task.getString("pid");
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("fuels", task.getString("fuels"))
                    .addFormDataPart("date_captured", task.getString("date_captured"))
                    .addFormDataPart("device_id", task.getString("device_id"));

            if (task.has("captured_uri")) {
                addFile(builder, "photo", Uri.parse(task.getString("captured_uri")));
            }

            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String baseUrl = sp.getString("api_base_url", "https://services.leyteprovince.gov.ph:8282");
            String url = baseUrl + "/qgas/public/api/station-fuel/" + pid;

            Request request = new Request.Builder().url(url).post(builder.build()).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    isUploading = false;
                    requireActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), "Network Error. Retrying later.", Toast.LENGTH_SHORT).show();
                        processNextInQueue(index + 1); // Skip to next or stop
                    });
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    isUploading = false;
                    requireActivity().runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            removeItemFromQueue(task);
                            // Since item is removed, the next item is now at the same index
                            processNextInQueue(index);
                        } else {
                            processNextInQueue(index + 1);
                        }
                    });
                }
            });
        } catch (Exception e) {
            isUploading = false;
            processNextInQueue(index + 1);
        }
    }

    private void removeItemFromQueue(JSONObject item) {
        updateQueue.remove(item);
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(QUEUE_KEY, new JSONArray(updateQueue).toString()).apply();

        if (updateQueue.isEmpty() && isAdded()) {
            dismiss(); // Auto-close fragment when queue is finished
        }
    }

    private void downloadAndSetUri(String url, String fileName, boolean isStation) {
        Context context = getContext();
        if (context == null) return;

        // Use Application context to prevent memory leaks during the async download
        Context appContext = context.getApplicationContext();

        Glide.with(this)
                .asBitmap()
                .load(url)
                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                        if (!isAdded()) return;

                        try {
                            // 1. Create the file in the same location as openCamera()
                            File storageDir = appContext.getExternalCacheDir();
                            File imageFile = new File(storageDir, fileName);

                            // 2. Save the Bitmap to the file (matches the "temp_upload.jpg" logic)
                            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                                resource.compress(Bitmap.CompressFormat.JPEG, 100, out);
                                out.flush();
                            }

                            // 3. Generate the URI using FileProvider (identical to openCamera)
                            Uri contentUri = FileProvider.getUriForFile(appContext,
                                    appContext.getPackageName() + ".fileprovider",
                                    imageFile);

                            // 4. Assign to the correct global variable
                            if (isStation) {
                                uriStation = contentUri;
                            } else {
                                uriCaptured = contentUri;
                            }

                            Log.d("UpdatePrice", "Downloaded image saved and URI set: " + contentUri.toString());

                        } catch (IOException e) {
                            Log.e("UpdatePrice", "Error saving downloaded bitmap", e);
                        }
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) { }
                });
    }

    private void addFuelToJson(JSONArray array, String name, EditText et) throws Exception {
        if (Double.parseDouble(et.getText().toString()) != 0.00){
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("price", Double.parseDouble(et.getText().toString()));
            array.put(obj);
        }
    }

    private void addFile(MultipartBody.Builder builder, String key, Uri uri) throws IOException {
        if (uri == null || uri.getScheme() == null || uri.getScheme().startsWith("http")) {
            return;
        }

        File file = new File(requireContext().getExternalCacheDir(), key + "_upload.jpg");
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(file)) {
            if (is == null) return;
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        }
        builder.addFormDataPart(key, file.getName(), RequestBody.create(file, MediaType.parse("image/jpeg")));
    }

    private void compressImageFile(File file) {
        if (file == null || !file.exists()) return;

        try {
            // 1. Decode the file into a Bitmap
            Bitmap original = BitmapFactory.decodeFile(file.getAbsolutePath());

            // 2. Prepare output stream to overwrite the same file
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);

            // 3. Compress: 80-90 quality usually hits the ~1MB mark for mobile photos
            // without visible loss in quality.
            original.compress(Bitmap.CompressFormat.JPEG, 85, out);

            out.flush();
            out.close();
            original.recycle(); // Free memory

            Log.d("ImageCompress", "New size: " + (file.length() / 1024) + " KB");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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
}
