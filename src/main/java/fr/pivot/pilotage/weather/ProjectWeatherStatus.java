package fr.pivot.pilotage.weather;

/**
 * Normalized "weather" status of a project (US23.2.4) — the synthetic, homogeneous health
 * indicator surfaced at portfolio altitude, computed from data this pilotage domain owns (the
 * temporal graph EN22.1 and its progress records).
 *
 * <p><strong>A11y (RGAA 4 / WCAG 2.1 AA).</strong> Each constant carries both a French
 * {@link #label()} and a stable {@link #icon()} token alongside its name — a consumer (API
 * client, UI) never has to fall back to color alone to convey the indicator; the color mapping
 * itself is a presentation concern left to the consumer (e.g. {@code pivot-pilotage-ui}), never
 * baked in here.
 *
 * <p>Thresholds driving this classification are fixed, homogeneous constants in
 * {@link ProjectWeatherService} — identical for every project regardless of its organizational
 * profile (PME, Grand groupe, Publique…) and not customizable per tenant/organization for this
 * US (see the backlog item's "Hors périmètre").
 */
public enum ProjectWeatherStatus {

    /** On track or ahead of the normalized schedule expectation. */
    SUNNY("Beau temps", "weather-sunny"),

    /** Mildly behind the normalized schedule expectation — attention warranted. */
    CLOUDY("Nuageux", "weather-cloudy"),

    /** Significantly behind the normalized schedule expectation — at risk. */
    STORMY("Orageux", "weather-stormy"),

    /**
     * Insufficient data to compute a meaningful indicator (missing progress and/or dates) — never
     * defaulted to a color-bearing status, per the US's explicit error case.
     */
    INDETERMINATE("Indéterminé", "weather-unknown");

    private final String label;
    private final String icon;

    ProjectWeatherStatus(final String label, final String icon) {
        this.label = label;
        this.icon = icon;
    }

    /**
     * Returns the French, human-readable label associated with this status (A11y — never color
     * alone).
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * Returns the stable icon token associated with this status (A11y — never color alone). The
     * consumer maps this token to an actual glyph/icon asset.
     *
     * @return the icon token
     */
    public String icon() {
        return icon;
    }
}
