# ML-based ad detection — plan

Status: in progress (steps 1-4 of 6 implemented and committed). Builds on the audio editor
(`app/src/main/java/de/danoeh/antennapod/ui/audioeditor/`). Branch: `feature/audio-editor`.

## Progress

Implemented so far (compiles clean, all committed and pushed to `feature/audio-editor`):

1. **Done** — Bundled YAMNet TFLite model at `app/src/main/assets/models/yamnet.tflite`
   (~4MB, Apache 2.0, from `storage.googleapis.com/download.tensorflow.org`), plus the
   `org.tensorflow:tensorflow-lite-task-audio:0.4.4` dependency (added to
   `gradle/libs.versions.toml` and `app/build.gradle`) and `noCompress += ["tflite"]` packaging
   option.
2. **Done** — `AudioSignalAnalyzer.java`: steps through an episode at a fixed time stride,
   decodes a native-format PCM window per step (reusing the seek+flush approach from
   `WaveformExtractor`), downmixes/resamples to 16kHz mono, runs YAMNet, and records the
   top-scoring audio tag + an RMS loudness estimate per window (`WindowResult`). Factored a
   shared `AudioTrackUtils.selectAudioTrack()` out of `WaveformExtractor` in the process.
3. **Done** — `TranscriptAdScanner.java` + `AdCandidateRegion.java`: scans transcript segments
   (via the existing `TranscriptUtils.loadTranscript`) against strong/medium regex keyword
   tiers ("sponsored by", "promo code", "% off", etc.), scores matches, merges nearby matching
   segments into candidate regions.
4. **Done** — `AdDetectionFusion.java`: combines the audio timeline and transcript candidates —
   audio windows count as ad-like via label keyword match ("music"/"jingle"/"advertis"/"theme")
   or a loudness jump (≥6dB vs. recent average); transcript-candidate confidence gets boosted
   when it overlaps an audio-flagged region; audio-only regions are kept too (covers the
   no-transcript fallback). Accepted regions above a confidence threshold get merged and
   converted to the same `CutRegion` type the manual editor already uses. All thresholds are
   named constants at the top of the file, expected to need tuning in step 6.

**Not yet done:**

5. **Next up** — Add a "Detect ads" button to `AudioEditorActivity` that runs
   `TranscriptUtils.loadTranscript` (if available) + `AudioSignalAnalyzer.analyze` +
   `AdDetectionFusion.fuse` on demand (background thread — this is real work, not instant),
   and populates the existing `cutRegions` list/adapter with the suggestions for review. This
   is the step that makes any of the above reachable/testable from the running app — nothing
   before this point has a UI hook.
6. **After that** — Verify end-to-end on real episodes (with and without a published
   transcript), tune the confidence thresholds in `AdDetectionFusion` and `TranscriptAdScanner`
   based on false positive/negative rate.

To resume: pick up at step 5 (task #14 in the task list — "Add 'Detect ads' button and wire
into AudioEditorActivity"). Steps 1-4 are committed and pushed; only step 5 onward is pending.

## Goal

Automatically suggest cut regions for likely ad breaks in a downloaded episode, so marking cuts
by hand becomes optional review instead of the only option.

## Decisions

- **Review, not auto-apply.** Detected regions populate the existing `CutRegion` list in
  `AudioEditorActivity` exactly like manually-marked cuts. The user reviews on the waveform,
  previews with the existing skip-during-playback behavior, deletes false positives, and hits
  Save themselves. No new save/export path.
- **Signals: audio + transcript, combined by rules.** Not a custom-trained classifier (no
  training dataset to collect/label), not on-device speech-to-text, not a cloud API. Two inputs:
  1. **Transcript signal** — reuse `TranscriptUtils.loadTranscript(FeedMedia, forceRefresh)`
     (`ui/transcript`, already used by the transcript-viewing screen) to fetch/parse a
     publisher-provided transcript, if one exists. Scan segments for ad-like language (sponsor
     phrases, promo codes, brand-mention density, URL/discount-code patterns).
  2. **Audio signal** — run a bundled, pretrained TensorFlow Lite audio-tagging model (e.g.
     YAMNet) over the episode in windows, flagging music/jingle presence, silence gaps, and
     loudness/tonal shifts that typically bracket ad breaks.
  3. **Fusion** — hand-written rules combine both into candidate `(startMs, endMs)` regions with
     a confidence score (e.g. transcript ad-language + a nearby audio jingle/loudness jump scores
     higher than either alone).
- **Fallback for episodes without a transcript**: audio-signal-only detection, still populates
  the list for review (lower confidence).
- **On-device only.** No audio or transcript leaves the phone; no cloud ML calls.
- **Model delivery: bundled in the app**, not downloaded on first use. Larger APK, but works
  offline immediately with no download/versioning/failure-handling code — acceptable since this
  is a personal fork, not something optimizing for install size.
- **Trigger: a "Detect ads" button inside the editor.** Runs on demand only, never automatically
  after download — keeps CPU/battery cost opt-in.

## Explicitly out of scope (for now)

- On-device speech-to-text / transcript generation for episodes that don't publish one.
- Cloud APIs of any kind (speech-to-text or LLM classification).
- A custom-trained ad/non-ad classifier.
- Automatic (non-reviewed) cutting.

## Open questions to resolve during implementation

- Which specific TFLite audio-tagging model to bundle (YAMNet vs. alternatives), and its
  license/size tradeoffs.
- Exact rule thresholds for the fusion step — will need real episodes to tune against.
- Where the TFLite runtime dependency and model asset should live module-wise (likely a new
  package under `:app`, following the `ui/audioeditor` precedent, unless it warrants its own
  module).
