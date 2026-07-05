package de.danoeh.antennapod.ui.audioeditor;

public class CutRegion {
    public final long startMs;
    public final long endMs;

    public CutRegion(long startMs, long endMs) {
        this.startMs = startMs;
        this.endMs = endMs;
    }
}
