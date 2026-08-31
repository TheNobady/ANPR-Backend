package com.anpr.backend.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stream")
public class StreamProperties {

    /**
     * Only events whose {@code occurred_at} is this close to now are pushed. Backfilled and
     * rebuilt history is persisted but never streamed -- a rebuild would otherwise flood a
     * live feed with hundreds of historical events.
     */
    private int liveWindowSeconds = 60;

    /** Comment frame interval, so idle connections are not dropped by a proxy mid-demo. */
    private int heartbeatSeconds = 20;

    /** Connections beyond this are refused rather than accumulated. */
    private int maxEmitters = 50;

    public int getLiveWindowSeconds() { return liveWindowSeconds; }
    public void setLiveWindowSeconds(int v) { this.liveWindowSeconds = v; }
    public int getHeartbeatSeconds() { return heartbeatSeconds; }
    public void setHeartbeatSeconds(int v) { this.heartbeatSeconds = v; }
    public int getMaxEmitters() { return maxEmitters; }
    public void setMaxEmitters(int v) { this.maxEmitters = v; }
}
