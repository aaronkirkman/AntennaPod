package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.Classifications;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the bundled YAMNet model (see app/src/main/assets/models/yamnet.tflite) over an episode
 * at a fixed time stride, producing a timeline of audio tag classifications (e.g. music/jingle)
 * plus a simple loudness estimate per window. This is one of two input signals (the other being
 * transcript text) combined to suggest ad regions - see docs/plans/ml-ad-detection.md.
 */
public class AudioSignalAnalyzer {
    private static final String MODEL_PATH = "models/yamnet.tflite";

    private AudioSignalAnalyzer() {
    }

    public static class WindowResult {
        public final long timestampMs;
        public final String topLabel;
        public final float topScore;
        public final float rmsDbFs;

        WindowResult(long timestampMs, String topLabel, float topScore, float rmsDbFs) {
            this.timestampMs = timestampMs;
            this.topLabel = topLabel;
            this.topScore = topScore;
            this.rmsDbFs = rmsDbFs;
        }
    }

    public interface ProgressListener {
        void onProgress(float fraction);
    }

    public static List<WindowResult> analyze(Context context, String path, long strideMs,
                                             ProgressListener listener) throws IOException {
        AudioClassifier classifier = AudioClassifier.createFromFile(context, MODEL_PATH);
        try {
            return analyzeWithClassifier(context, path, strideMs, classifier, listener);
        } finally {
            classifier.close();
        }
    }

    private static List<WindowResult> analyzeWithClassifier(Context context, String path, long strideMs,
                                                             AudioClassifier classifier,
                                                             ProgressListener listener) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, Uri.parse(path), null);
            int trackIndex = AudioTrackUtils.selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track found in " + path);
            }
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durationUs <= 0) {
                throw new IOException("Unknown duration for " + path);
            }
            int nativeSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            TensorAudio tensorAudio = classifier.createInputTensorAudio();
            int targetSampleRate = tensorAudio.getFormat().getSampleRate();
            int windowSampleCount = tensorAudio.getTensorBuffer().getFlatSize();
            long nativeWindowUs = (long) windowSampleCount * 1_000_000L / targetSampleRate;

            String mime = format.getString(MediaFormat.KEY_MIME);
            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            try {
                codec.configure(format, null, null, 0);
                codec.start();

                List<WindowResult> results = new ArrayList<>();
                long strideUs = strideMs * 1000L;
                long numWindows = durationUs / strideUs + 1;
                for (long windowIndex = 0; windowIndex * strideUs < durationUs; windowIndex++) {
                    long targetUs = windowIndex * strideUs;
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    short[] nativePcm = decodeWindow(extractor, codec, targetUs + nativeWindowUs);

                    float rmsDbFs = computeRmsDbFs(nativePcm);
                    short[] resampled = resampleToMono(nativePcm, nativeSampleRate, channelCount,
                            targetSampleRate, windowSampleCount);

                    tensorAudio.load(resampled);
                    List<Classifications> classifications = classifier.classify(tensorAudio);
                    Category topCategory = findTopCategory(classifications);

                    results.add(new WindowResult(targetUs / 1000, topCategory.getLabel(),
                            topCategory.getScore(), rmsDbFs));

                    if (listener != null) {
                        listener.onProgress(Math.min(1f, (windowIndex + 1) / (float) numWindows));
                    }
                }
                return results;
            } finally {
                codec.stop();
                codec.release();
            }
        } finally {
            extractor.release();
        }
    }

    private static short[] decodeWindow(MediaExtractor extractor, MediaCodec codec, long windowEndUs) {
        List<short[]> chunks = new ArrayList<>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean windowDone = false;
        int totalSamples = 0;

        while (!windowDone) {
            if (!inputDone) {
                int inputBufferId = codec.dequeueInputBuffer(10000);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
                    int sampleSize = inputBuffer != null ? extractor.readSampleData(inputBuffer, 0) : -1;
                    if (sampleSize < 0 || extractor.getSampleTime() > windowEndUs) {
                        codec.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outputBufferId = codec.dequeueOutputBuffer(info, 10000);
            if (outputBufferId >= 0) {
                if (info.size > 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null) {
                        short[] chunk = toShortArray(outputBuffer, info);
                        chunks.add(chunk);
                        totalSamples += chunk.length;
                    }
                }
                boolean isEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputBufferId, false);
                if (isEos || info.presentationTimeUs >= windowEndUs) {
                    windowDone = true;
                }
            }
        }

        short[] pcm = new short[totalSamples];
        int offset = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.length);
            offset += chunk.length;
        }
        return pcm;
    }

    private static short[] toShortArray(ByteBuffer outputBuffer, MediaCodec.BufferInfo info) {
        ByteBuffer buffer = outputBuffer.duplicate();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        short[] samples = new short[buffer.remaining() / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    private static float computeRmsDbFs(short[] pcm) {
        if (pcm.length == 0) {
            return -96f;
        }
        double sumSquares = 0;
        for (short sample : pcm) {
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }
        double rms = Math.sqrt(sumSquares / pcm.length);
        if (rms <= 0) {
            return -96f;
        }
        return (float) Math.max(-96.0, 20.0 * Math.log10(rms));
    }

    /**
     * Downmixes interleaved native-rate PCM to mono, then resamples (via linear interpolation)
     * to exactly {@code outputSampleCount} samples at {@code targetSampleRate}. Missing samples
     * near the end of the file are padded with silence.
     */
    private static short[] resampleToMono(short[] nativePcm, int nativeSampleRate, int channelCount,
                                          int targetSampleRate, int outputSampleCount) {
        int nativeFrameCount = channelCount > 0 ? nativePcm.length / channelCount : 0;
        float[] mono = new float[nativeFrameCount];
        for (int frame = 0; frame < nativeFrameCount; frame++) {
            int sum = 0;
            for (int ch = 0; ch < channelCount; ch++) {
                sum += nativePcm[frame * channelCount + ch];
            }
            mono[frame] = sum / (float) channelCount;
        }

        short[] output = new short[outputSampleCount];
        double rateRatio = nativeSampleRate / (double) targetSampleRate;
        for (int i = 0; i < outputSampleCount; i++) {
            double srcPos = i * rateRatio;
            int srcIndex = (int) srcPos;
            if (srcIndex >= nativeFrameCount - 1) {
                output[i] = srcIndex < nativeFrameCount ? (short) mono[srcIndex] : 0;
                continue;
            }
            double frac = srcPos - srcIndex;
            float interpolated = (float) (mono[srcIndex] * (1 - frac) + mono[srcIndex + 1] * frac);
            output[i] = (short) interpolated;
        }
        return output;
    }

    private static Category findTopCategory(List<Classifications> classifications) {
        Category top = null;
        for (Classifications classification : classifications) {
            for (Category category : classification.getCategories()) {
                if (top == null || category.getScore() > top.getScore()) {
                    top = category;
                }
            }
        }
        if (top == null) {
            throw new IllegalStateException("Classifier returned no categories");
        }
        return top;
    }
}
