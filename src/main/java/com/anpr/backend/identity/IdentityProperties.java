package com.anpr.backend.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every constant the resolver uses. These exist to be tuned -- the numbers below are a
 * starting point, not a result, so none of them are hardcoded in the scoring code.
 */
@ConfigurationProperties(prefix = "identity")
public class IdentityProperties {

    /** Total score a candidate must reach to be accepted as the same vehicle. */
    private double threshold = 4.0;

    /** If the runner-up is within this of the winner, both are rejected and a new vehicle is made. */
    private double ambiguityMargin = 1.0;

    /** Seconds of history the skeleton-equality fallback pool looks back over. */
    private int fallbackWindowSeconds = 900;

    /** Link confidence at or above which a sighting is allowed to move the centroid. */
    private double centroidMinConfidence = 0.7;

    /** Sightings at which a provisional vehicle becomes confirmed. */
    private int confirmAtSightings = 2;

    private final Scoring scoring = new Scoring();

    public static class Scoring {
        private double plateExact = 6.0;
        private double plateSkeleton = 4.0;
        private double plateDistance1 = 2.0;
        private double plateDistance2 = 0.5;
        /** Beyond this confusion-weighted distance the candidate is vetoed outright. */
        private double plateVetoDistance = 2.0;

        private double travelWeight = 2.0;
        private double travelSigma = 0.30;

        private double appearanceStrong = 5.0;
        private double appearanceWeak = 2.0;
        private double appearanceNeutral = 0.0;
        private double appearancePenalty = -3.0;
        private double appearanceStrongThreshold = 0.85;
        private double appearanceWeakThreshold = 0.70;
        private double appearanceNeutralThreshold = 0.50;

        public double getPlateExact() { return plateExact; }
        public void setPlateExact(double v) { this.plateExact = v; }
        public double getPlateSkeleton() { return plateSkeleton; }
        public void setPlateSkeleton(double v) { this.plateSkeleton = v; }
        public double getPlateDistance1() { return plateDistance1; }
        public void setPlateDistance1(double v) { this.plateDistance1 = v; }
        public double getPlateDistance2() { return plateDistance2; }
        public void setPlateDistance2(double v) { this.plateDistance2 = v; }
        public double getPlateVetoDistance() { return plateVetoDistance; }
        public void setPlateVetoDistance(double v) { this.plateVetoDistance = v; }
        public double getTravelWeight() { return travelWeight; }
        public void setTravelWeight(double v) { this.travelWeight = v; }
        public double getTravelSigma() { return travelSigma; }
        public void setTravelSigma(double v) { this.travelSigma = v; }
        public double getAppearanceStrong() { return appearanceStrong; }
        public void setAppearanceStrong(double v) { this.appearanceStrong = v; }
        public double getAppearanceWeak() { return appearanceWeak; }
        public void setAppearanceWeak(double v) { this.appearanceWeak = v; }
        public double getAppearanceNeutral() { return appearanceNeutral; }
        public void setAppearanceNeutral(double v) { this.appearanceNeutral = v; }
        public double getAppearancePenalty() { return appearancePenalty; }
        public void setAppearancePenalty(double v) { this.appearancePenalty = v; }
        public double getAppearanceStrongThreshold() { return appearanceStrongThreshold; }
        public void setAppearanceStrongThreshold(double v) { this.appearanceStrongThreshold = v; }
        public double getAppearanceWeakThreshold() { return appearanceWeakThreshold; }
        public void setAppearanceWeakThreshold(double v) { this.appearanceWeakThreshold = v; }
        public double getAppearanceNeutralThreshold() { return appearanceNeutralThreshold; }
        public void setAppearanceNeutralThreshold(double v) { this.appearanceNeutralThreshold = v; }
    }

    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { this.threshold = v; }
    public double getAmbiguityMargin() { return ambiguityMargin; }
    public void setAmbiguityMargin(double v) { this.ambiguityMargin = v; }
    public int getFallbackWindowSeconds() { return fallbackWindowSeconds; }
    public void setFallbackWindowSeconds(int v) { this.fallbackWindowSeconds = v; }
    public double getCentroidMinConfidence() { return centroidMinConfidence; }
    public void setCentroidMinConfidence(double v) { this.centroidMinConfidence = v; }
    public int getConfirmAtSightings() { return confirmAtSightings; }
    public void setConfirmAtSightings(int v) { this.confirmAtSightings = v; }
    public Scoring getScoring() { return scoring; }
}
