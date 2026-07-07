# ML-based ad detection — plan

Status: planned, not started. Builds on the audio editor (`app/src/main/java/de/danoeh/antennapod/ui/audioeditor/`).

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
