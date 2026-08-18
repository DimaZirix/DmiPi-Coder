package com.dmipi.coder.core.domain.llm;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The configured models, resolved to clients at construction: every declaration is matched to
 * the provider speaking its protocol — no match is a startup error, not a runtime surprise.
 * Selection answers "fastest", "strongest" and "cheapest at least tier X"; ties go to
 * declaration order. The active model starts as the first declared and is switchable at runtime.
 *
 * <p>Thread-safety: the model map is construction-confined and immutable; the active name is a
 * single volatile value, so {@link #activate} may be called from outside the loop thread.
 */
public final class ModelRegistry {

    private final Map<String, ConnectedModel> byName = new LinkedHashMap<>();
    private volatile String activeName;

    public ModelRegistry(final List<ModelDeclaration> declarations, final List<ProtocolProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        if (Objects.requireNonNull(declarations, "declarations").isEmpty()) {
            throw new IllegalArgumentException("At least one model must be declared.");
        }
        for (final ModelDeclaration declaration : declarations) {
            if (byName.containsKey(declaration.name())) {
                throw new IllegalArgumentException("Model '" + declaration.name() + "' is declared twice.");
            }
            byName.put(declaration.name(), new ConnectedModel(declaration, connect(declaration, providers)));
        }
        activeName = declarations.getFirst().name();
    }

    public List<ModelDeclaration> declarations() {
        return byName.values()
                .stream()
                .map(ConnectedModel::declaration)
                .toList();
    }

    public ConnectedModel named(final String name) {
        final ConnectedModel model = byName.get(name);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model '" + name + "'. Declared models: " + byName.keySet() + ".");
        }
        return model;
    }

    public ConnectedModel active() {
        return named(activeName);
    }

    /** Makes the named model the active conversation model. */
    public void activate(final String name) {
        named(name);
        activeName = name;
    }

    /** The cheapest model of the set. */
    public ConnectedModel fastest() {
        return byTier(Comparator.comparingInt(model -> model.declaration().tier().ordinal()));
    }

    /** The most capable model of the set. */
    public ConnectedModel strongest() {
        return byTier(Comparator.comparingInt((ConnectedModel model) -> model.declaration().tier().ordinal()).reversed());
    }

    /** The cheapest model meeting the bar; falls back to the strongest when none does. */
    public ConnectedModel atLeast(final Tier bar) {
        return byName.values()
                .stream()
                .filter(model -> model.declaration().tier().atLeast(bar))
                .min(Comparator.comparingInt(model -> model.declaration().tier().ordinal()))
                .orElseGet(this::strongest);
    }

    private ConnectedModel byTier(final Comparator<ConnectedModel> order) {
        return byName.values()
                .stream()
                .min(order)
                .orElseThrow();
    }

    private static LlmClient connect(final ModelDeclaration declaration, final List<ProtocolProvider> providers) {
        return providers.stream()
                .filter(provider -> provider.protocol().equals(declaration.protocol()))
                .findFirst()
                .map(provider -> provider.connect(declaration))
                .orElseThrow(() -> new IllegalStateException("Model '" + declaration.name() + "' declares protocol '" + declaration.protocol() + "', but no registered provider speaks it."));
    }
}
