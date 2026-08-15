package ru.privatenull.pnauth.dialog;

import net.kyori.adventure.text.Component;
import java.util.List;

/** Fields shared by every vanilla dialog type. */
public record DialogLayout(Component title, Component externalTitle, List<DialogBody> body,
                           List<DialogInput> inputs, boolean canCloseWithEscape, boolean pause,
                           AfterAction afterAction) {
    public DialogLayout {
        externalTitle = externalTitle == null ? title : externalTitle;
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        afterAction = afterAction == null ? AfterAction.CLOSE : afterAction;
    }

    public enum AfterAction { CLOSE, NONE, WAIT_FOR_RESPONSE }
}
