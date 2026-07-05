package de.danoeh.antennapod.ui.audioeditor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import de.danoeh.antennapod.R;

import java.util.List;
import java.util.Locale;

public class CutRegionAdapter extends RecyclerView.Adapter<CutRegionAdapter.ViewHolder> {

    public interface Listener {
        void onCutRegionTapped(CutRegion region);

        void onCutRegionDeleted(CutRegion region);
    }

    private final List<CutRegion> cutRegions;
    private final Listener listener;

    public CutRegionAdapter(List<CutRegion> cutRegions, Listener listener) {
        this.cutRegions = cutRegions;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.audio_editor_cut_region_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CutRegion region = cutRegions.get(position);
        holder.label.setText(String.format(Locale.getDefault(), "%s - %s",
                formatTime(region.startMs), formatTime(region.endMs)));
        holder.itemView.setOnClickListener(v -> listener.onCutRegionTapped(region));
        holder.deleteButton.setOnClickListener(v -> listener.onCutRegionDeleted(region));
    }

    @Override
    public int getItemCount() {
        return cutRegions.size();
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final ImageButton deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.cutRegionLabel);
            deleteButton = itemView.findViewById(R.id.deleteCutRegionButton);
        }
    }
}
