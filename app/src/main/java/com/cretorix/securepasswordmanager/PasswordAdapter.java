package com.cretorix.securepasswordmanager;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.cretorix.securepasswordmanager.crypto.VaultItem;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders vault entries as Material cards without exposing plaintext passwords in the list. */
/**
 * RecyclerView adapter for the encrypted entry list.
 *
 * <p>It never renders password plaintext. Copying or opening a record is an explicit
 * user action handled by the Activity.</p>
 */
public final class PasswordAdapter extends RecyclerView.Adapter<PasswordAdapter.EntryHolder> {

    public interface Listener {
        void onOpen(VaultItem item);

        void onCopyPassword(VaultItem item);

        void onToggleFavorite(VaultItem item);

        void onLoadThumbnail(VaultItem item, ImageView target);
    }

    private final List<VaultItem> items = new ArrayList<>();
    private final Listener listener;

    public PasswordAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<VaultItem> next) {
        items.clear();
        items.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EntryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_password, parent, false);
        return new EntryHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryHolder holder, int position) {
        VaultItem item = items.get(position);
        String title = item.title.isEmpty() ? "—" : item.title;
        holder.title.setText(title);
        holder.subtitle.setText(item.isImage()
                ? holder.itemView.getContext().getString(R.string.item_image_kind)
                : item.isDocument()
                ? holder.itemView.getContext().getResources().getQuantityString(
                R.plurals.item_document_pages,
                item.documentPageIds.size(), item.documentPageIds.size())
                : item.isFile()
                ? holder.itemView.getContext().getString(R.string.item_file_kind)
                : (item.username.isEmpty() ? item.url : item.username));
        boolean attachment = item.isImage() || item.isFile() || item.isDocument();
        holder.copy.setVisibility(attachment ? View.GONE : View.VISIBLE);
        holder.avatar.setVisibility(attachment ? View.GONE : View.VISIBLE);
        holder.typeIcon.setVisibility(attachment ? View.VISIBLE : View.GONE);
        holder.typeIcon.setTag(item.entryId);
        resetTypeIconStyle(holder.typeIcon);
        if (item.isImage()) {
            holder.typeIcon.setImageResource(R.drawable.ic_image);
            if (item.thumbnailId != null) {
                listener.onLoadThumbnail(item, holder.typeIcon);
            }
        } else if (item.isFile()) {
            holder.typeIcon.setImageResource(R.drawable.ic_note);
        } else if (item.isDocument()) {
            holder.typeIcon.setImageResource(R.drawable.ic_note);
        }
        holder.avatar.setText(initial(title));
        holder.favorite.setIconTint(ColorStateList.valueOf(item.favorite
                ? holder.itemView.getContext().getColor(R.color.spm_favorite)
                : MaterialColors.getColor(holder.favorite,
                com.google.android.material.R.attr.colorOutline)));
        holder.favorite.setAlpha(item.favorite ? 1f : 0.6f);
        holder.itemView.setOnClickListener(view -> listener.onOpen(item));
        holder.copy.setOnClickListener(view -> listener.onCopyPassword(item));
        holder.favorite.setOnClickListener(view -> listener.onToggleFavorite(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** Restores the icon look of a recycled tile that may previously have shown a thumbnail. */
    private static void resetTypeIconStyle(ImageView icon) {
        int padding = Math.round(12 * icon.getResources().getDisplayMetrics().density);
        icon.setPadding(padding, padding, padding, padding);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(MaterialColors.getColor(
                icon, androidx.appcompat.R.attr.colorPrimary)));
    }

    /** Lets a decrypted thumbnail fill the whole rounded tile. */
    static void applyThumbnailStyle(ImageView icon) {
        icon.setPadding(0, 0, 0, 0);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    private static String initial(String title) {
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return String.valueOf(Character.toUpperCase(c)).toUpperCase(Locale.getDefault());
            }
        }
        return "?";
    }

    static final class EntryHolder extends RecyclerView.ViewHolder {
        final TextView avatar;
        final TextView title;
        final TextView subtitle;
        final MaterialButton copy;
        final MaterialButton favorite;
        final ImageView typeIcon;

        EntryHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            copy = itemView.findViewById(R.id.btnCopy);
            favorite = itemView.findViewById(R.id.btnFavorite);
            typeIcon = itemView.findViewById(R.id.typeIcon);
        }
    }
}
