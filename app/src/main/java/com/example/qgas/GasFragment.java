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
import androidx.recyclerview.widget.RecyclerView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GasFragment extends Fragment {

    private TextView tvResults;
    private EditText etStation;
    private ImageView ivPreview;
    private File photoFile;
    private RecyclerView rvGasList;
    private GasAdapter adapter;
    private ArrayList<Gas> gasDataList = new ArrayList<>();

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    if (photoFile != null && photoFile.exists()) {
                        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                        ivPreview.setImageBitmap(bitmap);
                        processImageForGas(bitmap);
                    }
                }
            });

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> { if (isGranted) startCamera(); });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_gas, container, false);

        etStation = view.findViewById(R.id.et_station);
        tvResults = view.findViewById(R.id.tv_results);
        ivPreview = view.findViewById(R.id.iv_preview);
        Button btnCapture = view.findViewById(R.id.btn_capture);
        rvGasList = view.findViewById(R.id.rv_gas_list);
        rvGasList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(getContext()));
        adapter = new GasAdapter(gasDataList);
        rvGasList.setAdapter(adapter);

        btnCapture.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        return view;
    }

    private void startCamera() {
        try {
            photoFile = File.createTempFile("GAS_IMG_", ".jpg", requireContext().getExternalCacheDir());
            Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", photoFile);
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
                    lowerLine.contains("sil") || lowerLine.contains("gaso")) {

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

        if (flag) {
            gasList.add(new Gas(fname, fprice));
        }

        // --- DISPLAY LOGIC ---
        etStation.setText(stationName);
        if (gasList.isEmpty()) {
            tvResults.setText("No fuel data detected.");
            Toast.makeText(getContext(), "No fuel data detected.", Toast.LENGTH_SHORT).show();
        } else {
            tvResults.setText("");
            gasDataList.clear();
            gasDataList.addAll(gasList);
            adapter.notifyDataSetChanged();
        }

//        if (gasList.isEmpty()) {
//            tvResults.setText("No fuel data detected.");
//        } else {
//            StringBuilder display = new StringBuilder();
//            for (Gas gas : gasList) {
//                display.append("⛽ ")
//                        .append(gas.getName())
//                        .append(": ₱")
//                        .append(String.format("%.2f", gas.getPrice()))
//                        .append("\n");
//            }
//            tvResults.setText(display.toString());
//        }
    }
}