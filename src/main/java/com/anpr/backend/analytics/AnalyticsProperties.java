package com.anpr.backend.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

    private int densityBucketMinutes = 5;
    private int edgeBucketMinutes = 15;
    private int odBucketMinutes = 60;

    /**
     * Below this many traversals an edge's duration statistics are written as null. Three
     * cars do not make a median, and a null that says "not enough data" beats a confident
     * wrong number.
     */
    private int minSamples = 5;

    /**
     * How many recent buckets the scheduled job recomputes. More than one, so data that
     * arrives slightly late is absorbed without any special handling.
     */
    private int rollupBuckets = 3;

    /** Window used when a query supplies no range -- the same one live mode polls with. */
    private int defaultWindowMinutes = 15;

    private double bandModerate = 1.2;
    private double bandHeavy = 1.6;
    private double bandSevere = 2.2;

    public int getDensityBucketMinutes() { return densityBucketMinutes; }
    public void setDensityBucketMinutes(int v) { this.densityBucketMinutes = v; }
    public int getEdgeBucketMinutes() { return edgeBucketMinutes; }
    public void setEdgeBucketMinutes(int v) { this.edgeBucketMinutes = v; }
    public int getOdBucketMinutes() { return odBucketMinutes; }
    public void setOdBucketMinutes(int v) { this.odBucketMinutes = v; }
    public int getMinSamples() { return minSamples; }
    public void setMinSamples(int v) { this.minSamples = v; }
    public int getRollupBuckets() { return rollupBuckets; }
    public void setRollupBuckets(int v) { this.rollupBuckets = v; }
    public int getDefaultWindowMinutes() { return defaultWindowMinutes; }
    public void setDefaultWindowMinutes(int v) { this.defaultWindowMinutes = v; }
    public double getBandModerate() { return bandModerate; }
    public void setBandModerate(double v) { this.bandModerate = v; }
    public double getBandHeavy() { return bandHeavy; }
    public void setBandHeavy(double v) { this.bandHeavy = v; }
    public double getBandSevere() { return bandSevere; }
    public void setBandSevere(double v) { this.bandSevere = v; }

    /** Null ratio means the sample was too thin to band. */
    public String bandFor(Double ratio) {
        if (ratio == null) {
            return null;
        }
        if (ratio < bandModerate) {
            return "free";
        }
        if (ratio < bandHeavy) {
            return "moderate";
        }
        return ratio <= bandSevere ? "heavy" : "severe";
    }
}
