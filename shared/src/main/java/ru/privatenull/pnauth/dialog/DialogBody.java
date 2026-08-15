package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import java.util.Map;

/** Content element rendered in a native Minecraft dialog body. */
public sealed interface DialogBody permits DialogBody.PlainMessage, DialogBody.Item {
    record PlainMessage(Component content, int width) implements DialogBody { }
    record Item(Map<String, Object> itemStack, Component description, int descriptionWidth,
                boolean showDecorations, boolean showTooltip, int width, int height) implements DialogBody {
        public Item { itemStack = Map.copyOf(itemStack); }
    }
}
