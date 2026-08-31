package com.anpr.backend.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "alerts")
public class AlertProperties {

    /**
     * A repeat of the same alert type for the same vehicle inside this window updates the
     * existing row instead of creating another. Without it a blacklisted vehicle crossing
     * eight cameras produces eight alerts and the feed is unreadable within a minute.
     */
    private int suppressionWindowSeconds = 300;

    /** How far back to look for another vehicle wearing the same plate. */
    private int clonePlateWindowSeconds = 900;

    /** Above this implied speed a fallback-pool link is physically impossible. */
    private double impossibleSpeedKmh = 150.0;

    public int getSuppressionWindowSeconds() { return suppressionWindowSeconds; }
    public void setSuppressionWindowSeconds(int v) { this.suppressionWindowSeconds = v; }
    public int getClonePlateWindowSeconds() { return clonePlateWindowSeconds; }
    public void setClonePlateWindowSeconds(int v) { this.clonePlateWindowSeconds = v; }
    public double getImpossibleSpeedKmh() { return impossibleSpeedKmh; }
    public void setImpossibleSpeedKmh(double v) { this.impossibleSpeedKmh = v; }
}
