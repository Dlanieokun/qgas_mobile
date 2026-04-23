package com.example.qgas;

public class Gas {
    private String name;
    private Double price;

    public Gas(String name, Double price) {
        this.name = name;
        this.price = price;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) { this.name = name; }

    public Double getPrice() {
        return price;
    }
    public void setPrice(double price) { this.price = price; }
}
