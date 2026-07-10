-- V1 unique tant que le schema n'est pas stabilise (convention pivot-core, voir CLAUDE.md :
-- "Migrations Flyway — fichier V1 unique avant la BETA"). Tout changement de schema du domaine
-- Pilotage est plie dans ce fichier jusqu'au feu vert BETA du mainteneur — pas de V2/V3 separe.
CREATE SCHEMA IF NOT EXISTS pilotage;

-- EN18.1 — Socle de la hierarchie Pilotage : Application -> Projet.
-- FK cross-schema uniquement vers public.tenants(id) (identite plateforme possedee par
-- pivot-core, cf. ADR-006/ADR-022 et CLAUDE.md "Architecture BDD") — jamais d'entite locale
-- pour public.*, jamais de ON DELETE CASCADE vers public (les tenants ne sont pas supprimes en
-- dur, modele de desactivation/soft-delete). Les FK intra-schema pilotage.* portent au contraire
-- ON DELETE CASCADE (suppression d'une Application -> ses Projets).
--
-- Perimetre STRICT EN18.1 : uniquement application + project. Les entites temporelles (task,
-- dependances, jalons...) relevent d'EN22.1a et ne sont PAS creees ici.

-- Application : racine de la hierarchie de pilotage, rattachee a un tenant.
CREATE TABLE IF NOT EXISTS pilotage.application (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES public.tenants(id),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_tenant_id ON pilotage.application(tenant_id);

-- Project : rattache a exactement une Application (application_id NOT NULL porte la regle
-- metier "un Projet = une Application"). tenant_id est duplique sur le projet pour permettre
-- un filtrage tenant direct (isolation) sans jointure systematique vers l'application.
CREATE TABLE IF NOT EXISTS pilotage.project (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES pilotage.application(id) ON DELETE CASCADE,
    tenant_id       BIGINT       NOT NULL REFERENCES public.tenants(id),
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_project_application_id ON pilotage.project(application_id);
CREATE INDEX IF NOT EXISTS idx_project_tenant_id      ON pilotage.project(tenant_id);

-- =====================================================================================
-- EN22.1a — Schema temporel `pilotage` (contrat fige EN22.1 section (a) — 11 tables).
--
-- Pose UN SEUL graphe temporel dont la roadmap (macro) et le Gantt (detail) sont deux
-- VUES (ADR-010). Ce livrable = SCHEMA UNIQUEMENT (migrations + entites + contraintes DDL).
-- HORS perimetre EN22.1a (releve d'EN22.1b/c, NON implemente ici) :
--   * moteur CPM : calcul des colonnes derivees (early_*/late_*, *_slack_minutes,
--     is_critical) et du wbs_code derive serveur ;
--   * refus 422 a l'ecriture des champs derives (regle SERVICE, pas DDL) ;
--   * acyclicite des dependances (rejet SCHEDULE_CYCLE — regle MOTEUR) ;
--   * coherence applicative de l'invariant d'altitude (quelle borne fait foi selon
--     temporal_precision) — porte par la donnee, arbitre par le service/moteur.
-- Le SCHEMA porte : les colonnes+types, les FK intra-pilotage / vers public.tenants, les
-- index tenant_id, et les contraintes UNIQUE / CHECK explicitement figees au section (a).
--
-- Conventions transverses (section (a)) reprises sur chaque table : id BIGINT identity PK,
-- tenant_id BIGINT NOT NULL REFERENCES public.tenants(id) indexe, created_at/updated_at
-- TIMESTAMPTZ NOT NULL. Enums stockes en VARCHAR + CHECK (convention pivot : @Enumerated
-- STRING cote JPA, cf. pivot-collaboratif-core) plutot qu'en type ENUM natif PostgreSQL —
-- portabilite et evolutivite du domaine de valeurs sans ALTER TYPE.
-- Ordre de creation contraint : calendar avant project.calendar_id ; task avant les tables
-- qui la referencent ; phase.parent_task_id / task.parent_task_id auto-references ajoutees
-- apres coup pour lever le cycle de dependance de creation phase<->task.
-- =====================================================================================

-- --- calendar : cree avant project (project.calendar_id le reference) et avant task -----
-- Calendrier projet | tache | ressource : jours ouvres + exceptions. project_id NULL =>
-- calendrier tenant/base reutilisable. Aucune FK sortante hors pilotage/public.tenants.
CREATE TABLE IF NOT EXISTS pilotage.calendar (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES public.tenants(id),
    project_id        BIGINT      REFERENCES pilotage.project(id) ON DELETE CASCADE,
    scope             VARCHAR(16) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    working_days_mask SMALLINT    NOT NULL,
    working_time      JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_calendar_scope CHECK (scope IN ('PROJECT', 'TASK', 'RESOURCE'))
);
CREATE INDEX IF NOT EXISTS idx_calendar_tenant_id  ON pilotage.calendar(tenant_id);
CREATE INDEX IF NOT EXISTS idx_calendar_project_id ON pilotage.calendar(project_id);

-- --- calendar_exception : jours derogatoires d'un calendrier -----------------------------
CREATE TABLE IF NOT EXISTS pilotage.calendar_exception (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES public.tenants(id),
    calendar_id    BIGINT      NOT NULL REFERENCES pilotage.calendar(id) ON DELETE CASCADE,
    exception_date DATE        NOT NULL,
    is_working     BOOLEAN     NOT NULL,
    working_time   JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_calendar_exception_tenant_id   ON pilotage.calendar_exception(tenant_id);
CREATE INDEX IF NOT EXISTS idx_calendar_exception_calendar_id ON pilotage.calendar_exception(calendar_id);

-- --- project : extension temporelle (section (a) #project) --------------------------------
-- Ajout des colonnes temporelles au project EN18.1. calendar_id FK vers pilotage.calendar
-- (cree ci-dessus). scheduling_mode defaut AUTO. default_temporal_precision = snapshot de
-- resolveProfile(tenant).altitude a la creation (NULL => relire le profil a la volee).
-- NB : le contrat (a) liste application_id NON NULL sur project — deja porte par EN18.1.
ALTER TABLE pilotage.project
    ADD COLUMN IF NOT EXISTS calendar_id                 BIGINT      REFERENCES pilotage.calendar(id),
    ADD COLUMN IF NOT EXISTS scheduling_mode             VARCHAR(8)  NOT NULL DEFAULT 'AUTO',
    ADD COLUMN IF NOT EXISTS status_date                 DATE,
    ADD COLUMN IF NOT EXISTS default_temporal_precision  VARCHAR(16);
ALTER TABLE pilotage.project
    DROP CONSTRAINT IF EXISTS chk_project_scheduling_mode,
    DROP CONSTRAINT IF EXISTS chk_project_default_temporal_precision;
ALTER TABLE pilotage.project
    ADD CONSTRAINT chk_project_scheduling_mode CHECK (scheduling_mode IN ('AUTO', 'MANUAL')),
    ADD CONSTRAINT chk_project_default_temporal_precision
        CHECK (default_temporal_precision IS NULL
               OR default_temporal_precision IN ('SEMESTER', 'QUARTER', 'MONTH', 'WEEK', 'DAY'));
CREATE INDEX IF NOT EXISTS idx_project_calendar_id ON pilotage.project(calendar_id);

-- --- task : noeud central du graphe, porteur de l'altitude EFFECTIVE ----------------------
-- Coexistent sur la MEME ligne (invariant altitude ADR-010 #1) : bornes floues
-- (fuzzy_period_*) pour la vue roadmap ET dates precises (start_date/finish_date) pour le
-- Gantt. Le service/moteur arbitre laquelle fait foi selon temporal_precision — non porte
-- par le DDL. Colonnes DERIVEES (wbs_code, early_*/late_*, *_slack_minutes, is_critical) :
-- creees NULLABLES ici, JAMAIS calculees en EN22.1a (calcul = moteur EN22.1b).
-- parent_task_id (auto-ref WBS) et phase_id ajoutes en FK apres coup (cycle phase<->task).
CREATE TABLE IF NOT EXISTS pilotage.task (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES public.tenants(id),
    project_id          BIGINT       NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    phase_id            BIGINT,
    parent_task_id      BIGINT,
    wbs_code            VARCHAR(64),
    position            INT          NOT NULL,
    name                VARCHAR(512) NOT NULL,
    node_kind           VARCHAR(16)  NOT NULL,
    shared_in_roadmap   BOOLEAN      NOT NULL,
    temporal_precision  VARCHAR(16)  NOT NULL,
    fuzzy_period_start  DATE,
    fuzzy_period_end    DATE,
    start_date          TIMESTAMPTZ,
    finish_date         TIMESTAMPTZ,
    duration_minutes    INT,
    early_start         TIMESTAMPTZ,
    early_finish        TIMESTAMPTZ,
    late_start          TIMESTAMPTZ,
    late_finish         TIMESTAMPTZ,
    total_slack_minutes INT,
    free_slack_minutes  INT,
    is_critical         BOOLEAN,
    scheduling_mode     VARCHAR(8),
    calendar_id         BIGINT       REFERENCES pilotage.calendar(id),
    recurrence_rule     TEXT,
    revision            INT          NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_node_kind CHECK (node_kind IN ('SUMMARY', 'LEAF', 'MILESTONE', 'RECURRING')),
    CONSTRAINT chk_task_temporal_precision
        CHECK (temporal_precision IN ('SEMESTER', 'QUARTER', 'MONTH', 'WEEK', 'DAY')),
    CONSTRAINT chk_task_scheduling_mode
        CHECK (scheduling_mode IS NULL OR scheduling_mode IN ('AUTO', 'MANUAL'))
);
-- Auto-reference WBS + rattachement phase, ajoutes apres creation de task (et de phase).
ALTER TABLE pilotage.task
    DROP CONSTRAINT IF EXISTS fk_task_parent_task;
ALTER TABLE pilotage.task
    ADD CONSTRAINT fk_task_parent_task
        FOREIGN KEY (parent_task_id) REFERENCES pilotage.task(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_task_tenant_id      ON pilotage.task(tenant_id);
CREATE INDEX IF NOT EXISTS idx_task_project_id     ON pilotage.task(project_id);
CREATE INDEX IF NOT EXISTS idx_task_phase_id       ON pilotage.task(phase_id);
CREATE INDEX IF NOT EXISTS idx_task_parent_task_id ON pilotage.task(parent_task_id);

-- --- phase : regroupement macro, adosse a une tache recapitulative racine (optionnel) -----
-- phase.parent_task_id -> task(id) (NULL = regroupement macro simple). Cree apres task pour
-- resoudre la FK ; task.phase_id -> phase(id) ajoute juste apres (cycle phase<->task).
CREATE TABLE IF NOT EXISTS pilotage.phase (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES public.tenants(id),
    project_id     BIGINT       NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    parent_task_id BIGINT       REFERENCES pilotage.task(id) ON DELETE SET NULL,
    name           VARCHAR(255) NOT NULL,
    position       INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_phase_tenant_id      ON pilotage.phase(tenant_id);
CREATE INDEX IF NOT EXISTS idx_phase_project_id     ON pilotage.phase(project_id);
CREATE INDEX IF NOT EXISTS idx_phase_parent_task_id ON pilotage.phase(parent_task_id);

-- Rattachement task -> phase, ajoute maintenant que phase existe.
ALTER TABLE pilotage.task
    DROP CONSTRAINT IF EXISTS fk_task_phase;
ALTER TABLE pilotage.task
    ADD CONSTRAINT fk_task_phase
        FOREIGN KEY (phase_id) REFERENCES pilotage.phase(id) ON DELETE SET NULL;

-- --- task_dependency : arete du graphe (FS/SS/FF/SF + lag) --------------------------------
-- UNIQUE (pred, succ, type) : pas de doublon de lien. CHECK predecessor <> successor : pas
-- d'auto-boucle. L'ACYCLICITE GLOBALE est validee par le MOTEUR (EN22.1b, rejet
-- SCHEDULE_CYCLE) — non exprimable en DDL, hors perimetre EN22.1a.
CREATE TABLE IF NOT EXISTS pilotage.task_dependency (
    id                   BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL REFERENCES public.tenants(id),
    predecessor_task_id  BIGINT      NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    successor_task_id    BIGINT      NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    link_type            VARCHAR(2)  NOT NULL,
    lag_minutes          INT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_dependency_link_type CHECK (link_type IN ('FS', 'SS', 'FF', 'SF')),
    CONSTRAINT chk_task_dependency_no_self CHECK (predecessor_task_id <> successor_task_id),
    CONSTRAINT uq_task_dependency UNIQUE (predecessor_task_id, successor_task_id, link_type)
);
CREATE INDEX IF NOT EXISTS idx_task_dependency_tenant_id   ON pilotage.task_dependency(tenant_id);
CREATE INDEX IF NOT EXISTS idx_task_dependency_predecessor ON pilotage.task_dependency(predecessor_task_id);
CREATE INDEX IF NOT EXISTS idx_task_dependency_successor   ON pilotage.task_dependency(successor_task_id);

-- --- task_constraint : 0..1 par tache (UNIQUE task_id) ------------------------------------
-- constraint_date requis si type != ASAP/ALAP => regle SERVICE (le DDL laisse nullable, le
-- moteur/valideur applicatif l'exige selon le type) — hors perimetre DDL EN22.1a.
CREATE TABLE IF NOT EXISTS pilotage.task_constraint (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES public.tenants(id),
    task_id         BIGINT      NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    constraint_type VARCHAR(8)  NOT NULL,
    constraint_date TIMESTAMPTZ,
    deadline        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_constraint_type
        CHECK (constraint_type IN ('ASAP', 'ALAP', 'MSO', 'MFO', 'SNET', 'SNLT', 'FNET', 'FNLT')),
    CONSTRAINT uq_task_constraint_task UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_task_constraint_tenant_id ON pilotage.task_constraint(tenant_id);

-- --- assignment : ressource affectee a une tache (ref logique, pas de FID inter-modules) --
-- resource_ref = reference LOGIQUE (identite resolue via bus PIVOT — ADR-006, aucune FK
-- inter-modules). cost_amount = cout planning interne au Gantt (pas l'agregat budget E26).
CREATE TABLE IF NOT EXISTS pilotage.assignment (
    id                     BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT        NOT NULL REFERENCES public.tenants(id),
    task_id                BIGINT        NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    resource_ref           VARCHAR(255)  NOT NULL,
    units_percent          NUMERIC(6,2)  NOT NULL DEFAULT 100,
    work_minutes           INT,
    actual_work_minutes    INT,
    remaining_work_minutes INT,
    cost_amount            NUMERIC(18,4),
    cost_currency          CHAR(3),
    actual_cost_amount     NUMERIC(18,4),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_assignment_tenant_id ON pilotage.assignment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_assignment_task_id   ON pilotage.assignment(task_id);

-- --- task_progress : 1:1 task (UNIQUE task_id) --------------------------------------------
CREATE TABLE IF NOT EXISTS pilotage.task_progress (
    id                        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                 BIGINT       NOT NULL REFERENCES public.tenants(id),
    task_id                   BIGINT       NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    percent_complete          NUMERIC(5,2) NOT NULL DEFAULT 0,
    physical_percent_complete NUMERIC(5,2),
    actual_start              TIMESTAMPTZ,
    actual_finish             TIMESTAMPTZ,
    status_date               DATE,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_task_progress_task UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_task_progress_tenant_id ON pilotage.task_progress(tenant_id);

-- --- baseline : 0..10 par projet + baseline_snapshot (fige par tache) ---------------------
-- UNIQUE (project_id, baseline_index) + CHECK baseline_index 0..10.
CREATE TABLE IF NOT EXISTS pilotage.baseline (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES public.tenants(id),
    project_id     BIGINT      NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    baseline_index SMALLINT    NOT NULL,
    captured_at    TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_baseline_index CHECK (baseline_index BETWEEN 0 AND 10),
    CONSTRAINT uq_baseline_project_index UNIQUE (project_id, baseline_index)
);
CREATE INDEX IF NOT EXISTS idx_baseline_tenant_id  ON pilotage.baseline(tenant_id);
CREATE INDEX IF NOT EXISTS idx_baseline_project_id ON pilotage.baseline(project_id);

CREATE TABLE IF NOT EXISTS pilotage.baseline_snapshot (
    id                    BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES public.tenants(id),
    baseline_id           BIGINT        NOT NULL REFERENCES pilotage.baseline(id) ON DELETE CASCADE,
    task_id               BIGINT        NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    bl_start              TIMESTAMPTZ,
    bl_finish             TIMESTAMPTZ,
    bl_duration_minutes   INT,
    bl_work_minutes       INT,
    bl_cost_amount        NUMERIC(18,4),
    bl_temporal_precision VARCHAR(16),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_baseline_snapshot_temporal_precision
        CHECK (bl_temporal_precision IS NULL
               OR bl_temporal_precision IN ('SEMESTER', 'QUARTER', 'MONTH', 'WEEK', 'DAY'))
);
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_tenant_id   ON pilotage.baseline_snapshot(tenant_id);
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_baseline_id ON pilotage.baseline_snapshot(baseline_id);
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_task_id     ON pilotage.baseline_snapshot(task_id);
