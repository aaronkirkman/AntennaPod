package de.danoeh.antennapod.ui.audioeditor;

import de.danoeh.antennapod.model.feed.Transcript;
import de.danoeh.antennapod.model.feed.TranscriptSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Scans a publisher-provided transcript for ad-like language (sponsor phrases, promo codes,
 * calls to action) and produces candidate ad regions with a confidence score. One of two input
 * signals combined to suggest ad cuts - see docs/plans/ml-ad-detection.md.
 */
public class TranscriptAdScanner {
    private static final long MERGE_GAP_MS = 5000L;

    private static final Pattern[] STRONG_PATTERNS = {
        Pattern.compile("sponsored by"),
        Pattern.compile("brought to you by"),
        Pattern.compile("today'?s sponsor"),
        Pattern.compile("our sponsor"),
        Pattern.compile("promo code"),
        Pattern.compile("discount code"),
        Pattern.compile("use code"),
        Pattern.compile("use my code"),
        Pattern.compile("terms and conditions apply"),
    };

    private static final Pattern[] MEDIUM_PATTERNS = {
        Pattern.compile("\\bsign up\\b"),
        Pattern.compile("free trial"),
        Pattern.compile("\\d+%\\s*off"),
        Pattern.compile("link in the show notes"),
        Pattern.compile("\\bgo to\\b.{0,30}\\.(com|net|org|io)\\b"),
        Pattern.compile("\\bvisit\\b.{0,30}\\.(com|net|org|io)\\b"),
    };

    private TranscriptAdScanner() {
    }

    public static List<AdCandidateRegion> scan(Transcript transcript) {
        List<AdCandidateRegion> rawMatches = new ArrayList<>();
        for (int i = 0; i < transcript.getSegmentCount(); i++) {
            TranscriptSegment segment = transcript.getSegmentAt(i);
            float score = scoreText(segment.getWords());
            if (score > 0) {
                rawMatches.add(new AdCandidateRegion(segment.getStartTime(), segment.getEndTime(), score));
            }
        }
        return mergeNearby(rawMatches);
    }

    private static float scoreText(String words) {
        if (words == null || words.isEmpty()) {
            return 0f;
        }
        String lower = words.toLowerCase(Locale.US);
        float score = 0f;
        for (Pattern pattern : STRONG_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                score += 1.0f;
            }
        }
        for (Pattern pattern : MEDIUM_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                score += 0.5f;
            }
        }
        return Math.min(1f, score);
    }

    private static List<AdCandidateRegion> mergeNearby(List<AdCandidateRegion> matches) {
        List<AdCandidateRegion> merged = new ArrayList<>();
        int i = 0;
        while (i < matches.size()) {
            long start = matches.get(i).startMs;
            long end = matches.get(i).endMs;
            float confidence = matches.get(i).confidence;
            int j = i + 1;
            while (j < matches.size() && matches.get(j).startMs - end <= MERGE_GAP_MS) {
                end = Math.max(end, matches.get(j).endMs);
                confidence = Math.max(confidence, matches.get(j).confidence);
                j++;
            }
            merged.add(new AdCandidateRegion(start, end, confidence));
            i = j;
        }
        return merged;
    }
}
