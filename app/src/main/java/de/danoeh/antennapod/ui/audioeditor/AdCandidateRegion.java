package de.danoeh.antennapod.ui.audioeditor;

/**
 * A region flagged as possibly being an ad by one of the detection signals (transcript language,
 * audio tags), along with a confidence score. Distinct from {@link CutRegion}, which represents a
 * cut the user has actually committed to (manually or by accepting a suggestion).
 */
public class AdCandidateRegion {
    public final long startMs;
    public final long endMs;
    public final float confidence;

    public AdCandidateRegion(long startMs, long endMs, float confidence) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.confidence = confidence;
    }
}
