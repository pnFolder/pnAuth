package ru.privatenull.pnauth.velocity.dialog;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;

/** Transport boundary for Minecraft dialogs on Velocity. */
public interface VelocityDialogService extends AutoCloseable {
    boolean available();
    void show(Player player, DialogForm form);
    void clear(Player player);

    @Override
    default void close() {
    }

    record DialogForm(Component title, Component notice, List<TextField> fields,
                      Component submitLabel, String actionId) {
        public DialogForm {
            fields = List.copyOf(fields);
        }
    }

    record TextField(String key, Component label, int maxLength) {
    }

    @FunctionalInterface
    interface SubmissionHandler {
        void submit(Player player, String actionId, Map<String, String> values);
    }
}
