package org.traccar.session.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfidenceWindow<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfidenceWindow.class);

    @JsonProperty
    private List<T> window = new ArrayList<>();

    @JsonProperty
    private int windowSize;

    @JsonProperty
    private double thresholdPercent;

    public ConfidenceWindow() {
    }

    public ConfidenceWindow(int windowSize, double thresholdPercent) {
        this.windowSize = windowSize;
        this.thresholdPercent = thresholdPercent;
    }


    public void add(T value) {
        if (window == null) {
            window = new ArrayList<>();
        }
        window.add(value);
        if (window.size() > windowSize) {
            T evicted = window.remove(0);
            LOGGER.debug("[ConfidenceWindow] Evicted oldest value='{}' | window={}/{} | current={}",
                    evicted, window.size(), windowSize, window);
        }
        double fillPct = getFillPercent();
        LOGGER.debug("[ConfidenceWindow] Added value='{}' | fill={}/{} ({}%) | threshold={}% | window={}",
                value, window.size(), windowSize, String.format("%.1f", fillPct), thresholdPercent, window);
    }


    public T getDominantValue() {
        if (window == null || window.size() < windowSize) {
            LOGGER.trace("[ConfidenceWindow] Window not full yet ({}/{}) — no dominant value.",
                    window == null ? 0 : window.size(), windowSize);
            return null;
        }

        T bestValue = null;
        int bestCount = 0;
        for (T candidate : window) {
            if (candidate == null) {
                continue;
            }
            int count = 0;
            for (T v : window) {
                if (candidate.equals(v)) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestValue = candidate;
            }
        }

        if (bestValue == null) {
            LOGGER.debug("[ConfidenceWindow] All values null — no dominant value.");
            return null;
        }

        double percent = (bestCount * 100.0) / windowSize;
        LOGGER.debug("[ConfidenceWindow] Dominant='{}' | count={}/{} | achieved={}% | required={}% | {}",
                bestValue, bestCount, windowSize,
                String.format("%.1f", percent),
                thresholdPercent,
                percent >= thresholdPercent ? "THRESHOLD MET ✓" : "below threshold ✗");

        return percent >= thresholdPercent ? bestValue : null;
    }


    @JsonIgnore
    public boolean isDominantTrue() {
        boolean result = Boolean.TRUE.equals(getDominantValue());
        LOGGER.debug("[ConfidenceWindow] isDominantTrue={} | window={}", result, window);
        return result;
    }


    public List<T> getWindow() {
        return window;
    }

    public void setWindow(List<T> window) {
        this.window = window;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public double getThresholdPercent() {
        return thresholdPercent;
    }

    public void setThresholdPercent(double thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    @JsonIgnore
    public double getFillPercent() {
        if (windowSize == 0) {
            return 0;
        }
        return (window.size() * 100.0) / windowSize;
    }

    @Override
    public String toString() {
        return "ConfidenceWindow{size=" + windowSize
                + ", threshold=" + thresholdPercent + "%"
                + ", current=" + window + "}";
    }
}
