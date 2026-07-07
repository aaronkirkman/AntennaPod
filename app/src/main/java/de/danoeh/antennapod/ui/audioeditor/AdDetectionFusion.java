package de.danoeh.antennapod.ui.audioeditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Combines the audio-signal timeline ({@link AudioSignalAnalyzer}) and the transcript-language
 * candidates ({@link TranscriptAdScanner}) into a final list of suggested cut regions, via
 * hand-written rules rather than a trained classifier - see docs/plans/ml-ad-detection.md.
 *
 * Thresholds below are a starting point; expect to tune them against real episodes (task #15).
 */
public class AdDetectionFusion {
    private static final float ACCEPT_THRESHOLD = 0.6f;
    private static final float AUDIO_LABEL_BASE_CONFIDENCE = 0.5f;
    private static final float LOUDNESS_JUMP_CONFIDENCE_BOOST = 0.2f;
    private static final float OVERLAP_CONFIDENCE_BOOST = 0.3f;
    private static final float LOUDNESS_JUMP_THRESHOLD_DB = 6f;
    private static final int LOUDNESS_BASELINE_WINDOW_COUNT = 5;
    private static final long MERGE_GAP_MS = 5000L;
    private static final String[] AD_LABEL_KEYWORDS = {"music", "jingle", "advertis", "theme"};

    private AdDetectionFusion() {
    }

    public static List<CutRegion> fuse(long durationMs, long strideMs,
                                       List<AudioSignalAnalyzer.WindowResult> audioTimeline,
                                       List<AdCandidateRegion> transcriptCandidates) {
        List<AdCandidateRegion> audioCandidates = buildAudioCandidates(audioTimeline, strideMs);
        List<AdCandidateRegion> boosted = boostOverlappingConfidence(transcriptCandidates, audioCandidates);

        List<AdCandidateRegion> accepted = new ArrayList<>();
        for (AdCandidateRegion candidate : boosted) {
            if (candidate.confidence >= ACCEPT_THRESHOLD) {
                accepted.add(candidate);
            }
        }

        return mergeToCutRegions(accepted, durationMs);
    }

    private static List<AdCandidateRegion> buildAudioCandidates(List<AudioSignalAnalyzer.WindowResult> timeline,
                                                                long strideMs) {
        List<AdCandidateRegion> candidates = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            AudioSignalAnalyzer.WindowResult window = timeline.get(i);
            boolean labelMatch = matchesAdKeyword(window.topLabel);
            boolean loudnessJump = isLoudnessJump(timeline, i);
            if (!labelMatch && !loudnessJump) {
                continue;
            }
            float confidence = 0f;
            if (labelMatch) {
                confidence += AUDIO_LABEL_BASE_CONFIDENCE * window.topScore;
            }
            if (loudnessJump) {
                confidence += LOUDNESS_JUMP_CONFIDENCE_BOOST;
            }
            candidates.add(new AdCandidateRegion(window.timestampMs, window.timestampMs + strideMs,
                    Math.min(1f, confidence)));
        }
        return mergeNearby(candidates);
    }

    private static boolean matchesAdKeyword(String label) {
        if (label == null) {
            return false;
        }
        String lower = label.toLowerCase(Locale.US);
        for (String keyword : AD_LABEL_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoudnessJump(List<AudioSignalAnalyzer.WindowResult> timeline, int index) {
        int baselineStart = Math.max(0, index - LOUDNESS_BASELINE_WINDOW_COUNT);
        if (baselineStart == index) {
            return false;
        }
        float sum = 0f;
        for (int i = baselineStart; i < index; i++) {
            sum += timeline.get(i).rmsDbFs;
        }
        float baseline = sum / (index - baselineStart);
        return timeline.get(index).rmsDbFs - baseline >= LOUDNESS_JUMP_THRESHOLD_DB;
    }

    private static List<AdCandidateRegion> boostOverlappingConfidence(List<AdCandidateRegion> transcriptCandidates,
                                                                      List<AdCandidateRegion> audioCandidates) {
        List<AdCandidateRegion> result = new ArrayList<>();
        for (AdCandidateRegion transcriptRegion : transcriptCandidates) {
            float confidence = transcriptRegion.confidence;
            if (overlapsAny(transcriptRegion, audioCandidates)) {
                confidence = Math.min(1f, confidence + OVERLAP_CONFIDENCE_BOOST);
            }
            result.add(new AdCandidateRegion(transcriptRegion.startMs, transcriptRegion.endMs, confidence));
        }
        for (AdCandidateRegion audioRegion : audioCandidates) {
            if (!overlapsAny(audioRegion, transcriptCandidates)) {
                result.add(audioRegion);
            }
        }
        return result;
    }

    private static boolean overlapsAny(AdCandidateRegion region, List<AdCandidateRegion> others) {
        for (AdCandidateRegion other : others) {
            if (region.startMs < other.endMs && other.startMs < region.endMs) {
                return true;
            }
        }
        return false;
    }

    private static List<AdCandidateRegion> mergeNearby(List<AdCandidateRegion> candidates) {
        List<AdCandidateRegion> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted, Comparator.comparingLong(r -> r.startMs));

        List<AdCandidateRegion> merged = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            long start = sorted.get(i).startMs;
            long end = sorted.get(i).endMs;
            float confidence = sorted.get(i).confidence;
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).startMs - end <= MERGE_GAP_MS) {
                end = Math.max(end, sorted.get(j).endMs);
                confidence = Math.max(confidence, sorted.get(j).confidence);
                j++;
            }
            merged.add(new AdCandidateRegion(start, end, confidence));
            i = j;
        }
        return merged;
    }

    private static List<CutRegion> mergeToCutRegions(List<AdCandidateRegion> accepted, long durationMs) {
        List<AdCandidateRegion> merged = mergeNearby(accepted);
        List<CutRegion> regions = new ArrayList<>();
        for (AdCandidateRegion candidate : merged) {
            long start = Math.max(0, candidate.startMs);
            long end = Math.min(durationMs, candidate.endMs);
            if (end > start) {
                regions.add(new CutRegion(start, end));
            }
        }
        return regions;
    }
}
