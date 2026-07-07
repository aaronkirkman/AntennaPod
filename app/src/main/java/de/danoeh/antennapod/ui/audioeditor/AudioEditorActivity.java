package de.danoeh.antennapod.ui.audioeditor;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.ActivityAudioEditorBinding;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.common.ToolbarActivity;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AudioEditorActivity extends ToolbarActivity implements CutRegionAdapter.Listener {
    private static final String EXTRA_MEDIA_ID = "media_id";
    private static final int WAVEFORM_BUCKETS = 300;
    private static final long PLAYHEAD_UPDATE_INTERVAL_MS = 200;
    private static final long SKIP_MS = 5000;

    private ActivityAudioEditorBinding viewBinding;
    private ExoPlayer player;
    private FeedItem feedItem;
    private FeedMedia media;
    private long durationMs;
    @Nullable private float[] pendingAmplitudes;
    private long pendingCutStartMs = -1;
    private boolean cutStartMarked = false;
    private final List<CutRegion> cutRegions = new ArrayList<>();
    private CutRegionAdapter cutRegionAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private Disposable disposable;

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                skipOverCutRegionIfNeeded();
                viewBinding.waveformView.setPlayheadMs(player.getCurrentPosition());
                updateTimeLabel(player.getCurrentPosition());
                if (player.isPlaying()) {
                    handler.postDelayed(this, PLAYHEAD_UPDATE_INTERVAL_MS);
                }
            }
        }
    };

    public static Intent newIntent(Context context, long feedMediaId) {
        Intent intent = new Intent(context, AudioEditorActivity.class);
        intent.putExtra(EXTRA_MEDIA_ID, feedMediaId);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(R.string.edit_audio_label);
        viewBinding = ActivityAudioEditorBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        cutRegionAdapter = new CutRegionAdapter(cutRegions, this);
        viewBinding.cutRegionsList.setLayoutManager(new LinearLayoutManager(this));
        viewBinding.cutRegionsList.setAdapter(cutRegionAdapter);

        viewBinding.waveformView.setOnSeekListener(positionMs -> {
            if (player != null) {
                player.seekTo(positionMs);
                viewBinding.waveformView.setPlayheadMs(positionMs);
                updateTimeLabel(positionMs);
            }
        });
        viewBinding.playPauseButton.setOnClickListener(v -> togglePlayback());
        viewBinding.rewindButton.setOnClickListener(v -> seekBy(-SKIP_MS));
        viewBinding.fastForwardButton.setOnClickListener(v -> seekBy(SKIP_MS));
        viewBinding.markCutStartButton.setOnClickListener(v -> markCutStart());
        viewBinding.markCutEndButton.setOnClickListener(v -> markCutEnd());
        viewBinding.saveButton.setOnClickListener(v -> exportEditedEpisode());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmExit();
            }
        });

        long mediaId = getIntent().getLongExtra(EXTRA_MEDIA_ID, -1);
        loadEpisode(mediaId);
    }

    private void loadEpisode(long mediaId) {
        showLoading(getString(R.string.audio_editor_loading_waveform));
        disposable = Single.fromCallable(() -> DBReader.getFeedMedia(mediaId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(feedMedia -> {
                    this.media = feedMedia;
                    this.feedItem = feedMedia.getItem();
                    setupPlayer();
                    loadWaveform();
                }, error -> {
                    error.printStackTrace();
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.setMediaItem(MediaItem.fromUri(media.getLocalFileUrl()));
        player.prepare();
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                viewBinding.playPauseButton.setImageResource(
                        isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_48dp);
                if (isPlaying) {
                    handler.post(playheadUpdater);
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && durationMs <= 0) {
                    durationMs = player.getDuration();
                    updateTimeLabel(player.getCurrentPosition());
                    applyWaveformIfReady();
                }
            }
        });
    }

    private void loadWaveform() {
        disposable = Single.fromCallable(() -> {
                    float[] cached = WaveformCache.read(this, media.getLocalFileUrl(), WAVEFORM_BUCKETS);
                    if (cached != null) {
                        return cached;
                    }
                    float[] extracted = WaveformExtractor.extract(this, media.getLocalFileUrl(), WAVEFORM_BUCKETS, null);
                    WaveformCache.write(this, media.getLocalFileUrl(), WAVEFORM_BUCKETS, extracted);
                    return extracted;
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(amplitudes -> {
                    pendingAmplitudes = amplitudes;
                    applyWaveformIfReady();
                }, error -> {
                    error.printStackTrace();
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    hideLoading();
                });
    }

    private void applyWaveformIfReady() {
        if (pendingAmplitudes != null && durationMs > 0) {
            viewBinding.waveformView.setAmplitudes(pendingAmplitudes, durationMs);
            hideLoading();
        }
    }

    private void togglePlayback() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void seekBy(long deltaMs) {
        if (player == null) {
            return;
        }
        long newPositionMs = Math.max(0, Math.min(durationMs, player.getCurrentPosition() + deltaMs));
        player.seekTo(newPositionMs);
        viewBinding.waveformView.setPlayheadMs(newPositionMs);
        updateTimeLabel(newPositionMs);
    }

    private void skipOverCutRegionIfNeeded() {
        if (!player.isPlaying()) {
            return;
        }
        long positionMs = player.getCurrentPosition();
        for (CutRegion region : cutRegions) {
            if (positionMs >= region.startMs && positionMs < region.endMs) {
                player.seekTo(region.endMs);
                return;
            }
        }
    }

    private void markCutStart() {
        if (player == null) {
            return;
        }
        pendingCutStartMs = player.getCurrentPosition();
        cutStartMarked = true;
    }

    private void markCutEnd() {
        if (player == null) {
            return;
        }
        if (!cutStartMarked) {
            Toast.makeText(this, R.string.audio_editor_no_cut_start_marked, Toast.LENGTH_SHORT).show();
            return;
        }
        long endMs = player.getCurrentPosition();
        if (endMs <= pendingCutStartMs) {
            Toast.makeText(this, R.string.audio_editor_cut_end_before_start, Toast.LENGTH_SHORT).show();
            return;
        }
        cutRegions.add(new CutRegion(pendingCutStartMs, endMs));
        cutStartMarked = false;
        refreshCutRegions();
    }

    private void refreshCutRegions() {
        cutRegionAdapter.notifyDataSetChanged();
        viewBinding.waveformView.setCutRegions(cutRegions);
    }

    @Override
    public void onCutRegionTapped(CutRegion region) {
        if (player != null) {
            player.seekTo(region.startMs);
            viewBinding.waveformView.setPlayheadMs(region.startMs);
            updateTimeLabel(region.startMs);
        }
    }

    @Override
    public void onCutRegionDeleted(CutRegion region) {
        cutRegions.remove(region);
        refreshCutRegions();
    }

    private void updateTimeLabel(long positionMs) {
        viewBinding.timeLabel.setText(String.format(Locale.getDefault(), "%s / %s",
                formatTime(positionMs), formatTime(durationMs)));
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void exportEditedEpisode() {
        if (cutRegions.isEmpty()) {
            Toast.makeText(this, R.string.audio_editor_no_cut_start_marked, Toast.LENGTH_SHORT).show();
            return;
        }
        showLoading(getString(R.string.audio_editor_exporting));
        if (player != null) {
            player.pause();
        }

        File outputFolder = EditedEpisodesFeedHelper.getOrCreateFolder(this);
        String baseName = sanitizeFilename(feedItem.getTitle()) + getString(R.string.audio_editor_title_suffix);
        File outputFile = new File(outputFolder, baseName + "_" + UUID.randomUUID() + ".m4a");

        AudioTrimExporter.export(this, media.getLocalFileUrl(), cutRegions, durationMs,
                outputFile.getAbsolutePath(), new AudioTrimExporter.Callback() {
                    @Override
                    public void onSuccess() {
                        onExportSuccess();
                    }

                    @Override
                    public void onError(Exception e) {
                        e.printStackTrace();
                        hideLoading();
                        Toast.makeText(AudioEditorActivity.this,
                                R.string.audio_editor_export_failure, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void onExportSuccess() {
        disposable = Completable.fromAction(() -> {
                    Feed feed = EditedEpisodesFeedHelper.getOrCreateFeed(this);
                    EditedEpisodesFeedHelper.rescan(this, feed);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    hideLoading();
                    Toast.makeText(this, R.string.audio_editor_export_success, Toast.LENGTH_LONG).show();
                    finish();
                }, error -> {
                    error.printStackTrace();
                    hideLoading();
                    Toast.makeText(this, R.string.audio_editor_export_failure, Toast.LENGTH_LONG).show();
                });
    }

    private static String sanitizeFilename(String title) {
        if (title == null) {
            return "episode";
        }
        return title.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void showLoading(String label) {
        viewBinding.loadingLabel.setText(label);
        viewBinding.loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        viewBinding.loadingOverlay.setVisibility(View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            confirmExit();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmExit() {
        if (cutRegions.isEmpty()) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.audio_editor_discard_title)
                .setMessage(R.string.audio_editor_discard_message)
                .setPositiveButton(R.string.audio_editor_discard_confirm, (dialog, which) -> finish())
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (disposable != null) {
            disposable.dispose();
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
