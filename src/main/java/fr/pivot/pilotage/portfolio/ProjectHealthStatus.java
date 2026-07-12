package fr.pivot.pilotage.portfolio;

/**
 * Health ("santé/météo") status of a single project as surfaced in the consolidated portfolio view
 * (US23.2.1). A <strong>semantic</strong> state only — this backend never emits a raw color; the
 * A11y AC ("les indicateurs de santé/météo ne sont pas restitués uniquement par la couleur") is a
 * frontend rendering concern ({@code pivot-pilotage-ui} maps each literal to an icon + text label),
 * but this contract structurally supports it by never carrying color as data.
 *
 * <p><strong>The calculation</strong> of {@link #ON_TRACK}/{@link #AT_RISK}/{@link #CRITICAL} is out
 * of this US's scope ("Hors périmètre": "le calcul détaillé de l'indicateur de santé/météo est
 * défini par US23.2.4 ; cette US consomme l'indicateur, elle ne le calcule pas") — see
 * {@link ProjectHealthProvider}. This US only defines the vocabulary and the explicit
 * {@link #NOT_SET} state a project with no computed indicator reports (error AC: "given un projet
 * sans indicateur de santé, system le signale comme « non renseigné » (état explicite) plutôt que
 * de l'omettre ou d'afficher une valeur par défaut trompeuse").
 */
public enum ProjectHealthStatus {

    /** The project's health indicator reports on track — no attention needed. */
    ON_TRACK,

    /** The project's health indicator reports at risk — needs attention. */
    AT_RISK,

    /** The project's health indicator reports critical — needs immediate attention. */
    CRITICAL,

    /**
     * No health indicator is available for this project yet — no {@link ProjectHealthProvider} has
     * one. The explicit, never-omitted, never-defaulted-to-something-misleading state the error AC
     * requires.
     */
    NOT_SET
}
