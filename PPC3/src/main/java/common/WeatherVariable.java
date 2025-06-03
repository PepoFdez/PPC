package common;

import java.io.Serializable;

public class WeatherVariable implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private double value;
    private String unit;

    public WeatherVariable(String name, double value, String unit) {
        this.name = name;
        this.value = value;
        this.unit = unit;
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    @Override
    public String toString() {
        return name + ": " + String.format("%.2f", value) + " " + unit;
    }
}