package com.example.qgas;

import java.util.ArrayList;

public class GasScan {
    public String station;
    public double latitude;
    public double longitude;
    public String dateTime;
    public String gasSignPath;
    public String stationPhotoPath;
    public ArrayList<Gas> gasList;

    public GasScan(String station, double lat, double lon, String dateTime, String gasSignPath, String stationPhotoPath, ArrayList<Gas> gasList) {
        this.station = station;
        this.latitude = lat;
        this.longitude = lon;
        this.dateTime = dateTime;
        this.gasSignPath = gasSignPath;
        this.stationPhotoPath = stationPhotoPath;
        this.gasList = gasList;
    }
}