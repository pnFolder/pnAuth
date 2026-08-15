package ru.privatenull.pnauth.dialog;

import java.util.Map;

/** Every vanilla dialog action, including the two input-aware dynamic variants. */
public sealed interface DialogAction permits DialogAction.None, DialogAction.Static,
        DialogAction.DynamicRunCommand, DialogAction.DynamicCustom {
    record None() implements DialogAction { }
    record Static(String type, Map<String, Object> payload) implements DialogAction {
        public Static { payload = Map.copyOf(payload); }
    }
    record DynamicRunCommand(String template) implements DialogAction { }
    record DynamicCustom(String id, Map<String, Object> additions) implements DialogAction {
        public DynamicCustom { additions = Map.copyOf(additions); }
    }
}
