package com.anpr.backend.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the query API the frontend consumes. */
@ConfigurationProperties(prefix = "api")
public class ApiProperties {

    /**
     * A hop is flagged as a suspected detour when it took more than this multiple of the
     * edge's typical travel time -- the vehicle plausibly drove a different route than the
     * geometry shows.
     */
    private double detourRatio = 1.6;

    /** Default and maximum result count for vehicle search. */
    private int searchLimit = 20;
    private int searchMaxLimit = 200;

    public double getDetourRatio() { return detourRatio; }
    public void setDetourRatio(double v) { this.detourRatio = v; }
    public int getSearchLimit() { return searchLimit; }
    public void setSearchLimit(int v) { this.searchLimit = v; }
    public int getSearchMaxLimit() { return searchMaxLimit; }
    public void setSearchMaxLimit(int v) { this.searchMaxLimit = v; }
}
