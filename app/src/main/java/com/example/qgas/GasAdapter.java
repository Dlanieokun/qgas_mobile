package com.example.qgas;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class GasAdapter extends RecyclerView.Adapter<GasAdapter.GasViewHolder> {

    private List<Gas> gasList;

    public GasAdapter(List<Gas> gasList) {
        this.gasList = gasList;
    }

    @NonNull
    @Override
    public GasViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gas, parent, false);
        return new GasViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GasViewHolder holder, int position) {
        Gas gas = gasList.get(position);

        // Clean up listeners before setting text to prevent recycling bugs
        holder.etName.removeTextChangedListener(holder.nameWatcher);
        holder.etPrice.removeTextChangedListener(holder.priceWatcher);

        holder.etName.setText(gas.getName());
        holder.etPrice.setText(String.valueOf(gas.getPrice()));
        holder.currentPosition = position;

        // Re-attach listeners
        holder.etName.addTextChangedListener(holder.nameWatcher);
        holder.etPrice.addTextChangedListener(holder.priceWatcher);
    }

    @Override
    public int getItemCount() {
        return gasList.size();
    }

    public class GasViewHolder extends RecyclerView.ViewHolder {
        EditText etName, etPrice;
        TextWatcher nameWatcher, priceWatcher;
        int currentPosition;

        public GasViewHolder(@NonNull View itemView) {
            super(itemView);
            etName = itemView.findViewById(R.id.et_item_name);
            etPrice = itemView.findViewById(R.id.et_item_price);

            nameWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    gasList.get(currentPosition).setName(s.toString());
                }
            };

            priceWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    try {
                        float val = Float.parseFloat(s.toString());
                        gasList.get(currentPosition).setPrice(val);
                    } catch (NumberFormatException e) {
                        gasList.get(currentPosition).setPrice(0.0f);
                    }
                }
            };
        }
    }
}