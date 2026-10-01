package io.izzel.minecraftmcp.scenario;

import java.util.*;

public final class ScenarioRunOptions {
    private final Set<String> includeTags;
    private final Set<String> excludeTags;
    private final String loader;
    private final Boolean background;

    private ScenarioRunOptions(Set<String> includeTags, Set<String> excludeTags, String loader, Boolean background) {
        this.includeTags = Set.copyOf(includeTags);
        this.excludeTags = Set.copyOf(excludeTags);
        this.loader = loader;
        this.background = background;
    }

    public Set<String> includeTags() { return includeTags; }
    public Set<String> excludeTags() { return excludeTags; }
    public String loader() { return loader; }
    /** Whether the client runs in background mode, or {@code null} when unknown (no background filtering). */
    public Boolean background() { return background; }
    public boolean hasTagFilters() { return !includeTags.isEmpty() || !excludeTags.isEmpty(); }

    public static ScenarioRunOptions defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Set<String> includeTags = new LinkedHashSet<>();
        private final Set<String> excludeTags = new LinkedHashSet<>();
        private String loader;
        private Boolean background;
        public Builder includeTags(String... tags) { includeTags.addAll(Arrays.asList(tags)); return this; }
        public Builder excludeTags(String... tags) { excludeTags.addAll(Arrays.asList(tags)); return this; }
        public Builder loader(String loader) { this.loader = loader; return this; }
        public Builder background(boolean background) { this.background = background; return this; }
        public ScenarioRunOptions build() { return new ScenarioRunOptions(includeTags, excludeTags, loader, background); }
    }
}
