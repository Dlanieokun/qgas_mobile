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

import okhttp3.*;

public class UpdatePriceFragment extends BottomSheetDialogFragment {

    private String stationJson;
    private final OkHttpClient client = new OkHttpClient();
    private ImageView imgStation, imgCaptured;
    private Uri uriStation, uriCaptured, cameraTempUri;
    private boolean isPickingStation = true;
    private final List<FuelEntry> fuelEntries = new ArrayList<>();

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
                    Uri finalUri = (result.getData() != null && result.getData().getData() != null)
                            ? result.getData().getData() : cameraTempUri;

                    if (finalUri != null) {
                        if (isPickingStation) {
                            uriStation = finalUri;
                            imgStation.setImageURI(finalUri);
                        } else {
                            uriCaptured = finalUri;
                            imgCaptured.setImageURI(finalUri);
                            runOcrOnCapturedImage(finalUri);
                        }
                    }
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
        Button btnSave = v.findViewById(R.id.btn_save_prices);

        try {
            JSONObject station = new JSONObject(stationJson);
            title.setText(station.optString("station_name"));
            String pid = station.getString("pid");

            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String base = sp.getString("api_base_url", "http://113.19.12.104:8180/qgas/public/api")
                    .replace("/api", "").replace("public", "storage");

            loadPhoto(station.optString("station_photo"), imgStation, base);
            loadPhoto(station.optString("photo"), imgCaptured, base);

            String stationPath = station.optString("station_photo");
            String capturedPath = station.optString("photo");

            imgStation.setOnClickListener(view -> showSourceDialog(true));
            imgCaptured.setOnClickListener(view -> showSourceDialog(false));

            if (stationPath != null && !stationPath.isEmpty() && !stationPath.equals("null")) {
                uriStation = Uri.parse(base + (stationPath.startsWith("/") ? "" : "/") + stationPath);
            }
            if (capturedPath != null && !capturedPath.isEmpty() && !capturedPath.equals("null")) {
                uriCaptured = Uri.parse(base + (capturedPath.startsWith("/") ? "" : "/") + capturedPath);
            }

            JSONArray fuels = new JSONArray(station.getString("data"));
            fuelEntries.clear(); // Clear to prevent duplicates on orientation change
            for (int i = 0; i < fuels.length(); i++) {
                JSONObject f = fuels.getJSONObject(i);
                addFuelInput(containerLayout, f.getString("name"), (float) f.optDouble("price", 0.0));
            }

            btnSave.setOnClickListener(view -> uploadAll(pid));
        } catch (Exception e) { e.printStackTrace(); }
        return v;
    }

    private void addFuelInput(LinearLayout container, String name, Float price) {
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
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "OCR Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processOcrResults(Text visionText) {
        // Regex handles patterns like 54.20, 120.00, or 61,50
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
            Toast.makeText(getContext(), "Detected " + allGasProcessed.size() + " fuel prices!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "No matching prices found in image.", Toast.LENGTH_SHORT).show();
        }
    }

    private void displayProcessedGas(List<Gas> processedList) {
        LinearLayout containerLayout = getView().findViewById(R.id.fuel_input_container);
        containerLayout.removeAllViews();
        fuelEntries.clear();
        for (Gas entry : processedList) {
            addFuelInput(containerLayout, entry.getName(), entry.getPrice());
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

    private void showSourceDialog(boolean isStation) {
        this.isPickingStation = isStation;
        openCamera();
//        String[] options = {"Camera", "Gallery"};
//        new AlertDialog.Builder(requireContext()).setTitle("Select Image Source")
//                .setItems(options, (d, which) -> {
//                    if (which == 0) openCamera(); else openGallery();
//                }).show();
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
            // 1. Collect current values from UI
            JSONArray updatedFuels = new JSONArray();
            for (FuelEntry entry : fuelEntries) {
                JSONObject obj = new JSONObject();
                obj.put("name", entry.name.getText());
                // Get the text directly from the EditText field
                String priceText = entry.input.getText().toString();
                obj.put("price", Double.parseDouble(priceText));
                updatedFuels.put(obj);
            }

            // 2. Format the current timestamp for 'date_captured'
            String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            // 3. Build Multipart Body matching Postman screenshot
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("fuels", updatedFuels.toString()) // Key is "fuels"
                    .addFormDataPart("date_captured", currentTime);   // Required field

            if (uriStation != null) addFile(builder, "station_photo", uriStation);
            if (uriCaptured != null) addFile(builder, "photo", uriCaptured);

            // 4. Construct URL with PID as path variable
            SharedPreferences sp = requireActivity().getSharedPreferences("AppConfig", Context.MODE_PRIVATE);
            String baseUrl = sp.getString("api_base_url", "http://113.19.12.104:8180/qgas/public/api");
            String url = baseUrl + "/station-fuel/" + pid; // PID in path

            Request request = new Request.Builder().url(url).post(builder.build()).build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    if(isAdded()) requireActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "Network Error", Toast.LENGTH_SHORT).show());
                }
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            if (response.isSuccessful()) {
                                Toast.makeText(getContext(), "Update Successful!", Toast.LENGTH_SHORT).show();
                                dismiss();
                            } else {
                                // Helps debug 422 or other server errors
                                Toast.makeText(getContext(), "Error: " + response.code(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Invalid Input", Toast.LENGTH_SHORT).show();
        }
    }

    private void addFile(MultipartBody.Builder builder, String key, Uri uri) throws IOException {
        File file = new File(requireContext().getExternalCacheDir(), key + "_upload.jpg");
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        }
        builder.addFormDataPart(key, file.getName(), RequestBody.create(file, MediaType.parse("image/jpeg")));
    }
}