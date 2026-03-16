package com.example.qgas;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private RecyclerView recyclerView;
    private QueueAdapter adapter;
    private List<JSONObject> queueList = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        recyclerView = view.findViewById(R.id.rv_home_queue);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new QueueAdapter(queueList);
        recyclerView.setAdapter(adapter);

        loadQueue();
        return view;
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                holder.tvStation.setText(obj.getString("station"));
                holder.tvTime.setText(obj.getString("timestamp"));
                holder.tvLat.setText("Lat: " + obj.getDouble("latitude"));
                holder.tvLong.setText("Long: " + obj.getDouble("longitude"));
                holder.tvDetails.setText("Items: " + obj.getJSONArray("prices").length());
            } catch (Exception e) { e.printStackTrace(); }
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
}