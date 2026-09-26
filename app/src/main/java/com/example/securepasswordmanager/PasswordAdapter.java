package com.example.securepasswordmanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.securepasswordmanager.crypto.VaultItem;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders vault entries as Material cards without exposing plaintext passwords in the list. */
public final class PasswordAdapter extends RecyclerView.Adapter<PasswordAdapter.EntryHolder> {

    public interface Listener {
        void onOpen(VaultItem item);

        void onCopyPassword(VaultItem item);

        void onToggleFavorite(VaultItem item);
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
        holder.subtitle.setText(item.username.isEmpty() ? item.url : item.username);
        holder.avatar.setText(initial(title));
        holder.favorite.setAlpha(item.favorite ? 1f : 0.25f);
        holder.itemView.setOnClickListener(view -> listener.onOpen(item));
        holder.copy.setOnClickListener(view -> listener.onCopyPassword(item));
        holder.favorite.setOnClickListener(view -> listener.onToggleFavorite(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
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

        EntryHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            copy = itemView.findViewById(R.id.btnCopy);
            favorite = itemView.findViewById(R.id.btnFavorite);
        }
    }
}
