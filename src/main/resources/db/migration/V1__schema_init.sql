-- V1 unique tant que le schema n'est pas stabilise (convention pivot-core, voir CLAUDE.md :
-- "Migrations Flyway — fichier V1 unique avant la BETA"). Tout changement de schema du domaine
-- Pilotage est plie dans ce fichier jusqu'au feu vert BETA du mainteneur — pas de V2/V3 separe.
CREATE SCHEMA IF NOT EXISTS pilotage;

-- =====================================================================================
-- Retrofit team_id (fix/pilotage-schema-team-id-retrofit) — alignement sur le pattern deja
-- en prod dans pivot-agilite-core (V1__schema_init.sql : agilite.retro_sessions/wheel/...) et
-- sur la table des schemas de pivot-platform/CLAUDE.md ("pilotage -> FK -> public.teams.id").
--
-- Constat : chaque table pilotage.* posee jusqu'ici (EN18.1, EN22.1a/b/c, EN18.10) porte
-- tenant_id SEUL, un isolement au niveau tenant entier. Sans team_id, une entreprise avec
-- plusieurs equipes dans le meme tenant partagerait un unique portefeuille Applications/Projets
-- entre toutes ses equipes au lieu d'un portefeuille par equipe — defaut de conception corrige
-- ici en ajoutant team_id BIGINT NOT NULL REFERENCES public.teams(id) sur CHAQUE table qui
-- portait tenant_id seul, avec un index dedie (miroir de l'index tenant_id existant).
--
-- Duplication deliberee : comme tenant_id est deja duplique sur les tables filles (project,
-- task, ...) plutot que de forcer une jointure remontant a application a chaque lecture
-- (cf. commentaire d'origine sur pilotage.project), team_id suit exactement le meme principe —
-- duplique sur chaque table plutot que resolu par jointure.
--
-- Coherence tenant/team (question tranchee ici, PO Agent) : verifie sur pivot-agilite-core
-- (PlatformTeam.java + RetroSessionService) que la coherence "team_id.tenant_id == tenant_id de
-- la ligne" est une regle APPLICATIVE (service), jamais une contrainte SQL composite — public.
-- teams n'expose pas de cle UNIQUE (id, tenant_id) permettant une FK composite cote pilotage,
-- et pivot-agilite-core valide cette coherence cote service (ex. RetroSessionService leve
-- RetroTeamNotFoundException si le teamId n'existe pas OU appartient a un autre tenant). Meme
-- posture ici : simple FK vers public.teams(id) au niveau SQL ; la coherence tenant<->team est
-- une regle de service, a appliquer au fil des futurs endpoints d'ecriture (pas encore de
-- service CRUD pour application/project/task a ce stade — seul le nouvel endpoint d'override du
-- profil d'organisation, ecrit dans cette meme passe, l'applique reellement, en JDBC brut,
-- meme pattern que OrganizationProfileResolver.tenantExists ci-dessous).
-- =====================================================================================

-- EN18.1 — Socle de la hierarchie Pilotage : Application -> Projet.
-- FK cross-schema uniquement vers public.tenants(id) / public.teams(id) (identite plateforme
-- possedee par pivot-core, cf. ADR-006/ADR-022 et CLAUDE.md "Architecture BDD") — jamais
-- d'entite locale pour public.*, jamais de ON DELETE CASCADE vers public (les tenants/equipes
-- ne sont pas supprimes en dur, modele de desactivation/soft-delete). Les FK intra-schema
-- pilotage.* portent au contraire ON DELETE CASCADE (suppression d'une Application -> ses
-- Projets).
--
-- Perimetre STRICT EN18.1 : uniquement application + project. Les entites temporelles (task,
-- dependances, jalons...) relevent d'EN22.1a et ne sont PAS creees ici.

-- Application : racine de la hierarchie de pilotage, rattachee a un tenant ET une equipe —
-- team_id porte le rattachement fin (portefeuille par equipe, retrofit ci-dessus).
CREATE TABLE IF NOT EXISTS pilotage.application (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id     BIGINT       NOT NULL REFERENCES public.teams(id),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_tenant_id ON pilotage.application(tenant_id);
CREATE INDEX IF NOT EXISTS idx_application_team_id   ON pilotage.application(team_id);

-- Project : rattache a exactement une Application (application_id NOT NULL porte la regle
-- metier "un Projet = une Application"). tenant_id ET team_id sont dupliques sur le projet pour
-- permettre un filtrage tenant/equipe direct (isolation) sans jointure systematique vers
-- l'application.
CREATE TABLE IF NOT EXISTS pilotage.project (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    application_id  BIGINT       NOT NULL REFERENCES pilotage.application(id) ON DELETE CASCADE,
    tenant_id       BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id         BIGINT       NOT NULL REFERENCES public.teams(id),
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_project_application_id ON pilotage.project(application_id);
CREATE INDEX IF NOT EXISTS idx_project_tenant_id      ON pilotage.project(tenant_id);
CREATE INDEX IF NOT EXISTS idx_project_team_id        ON pilotage.project(team_id);

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
--
-- Retrofit team_id (voir bandeau en tete de fichier) : chacune de ces tables recoit desormais
-- aussi team_id BIGINT NOT NULL REFERENCES public.teams(id) + index dedie, meme principe de
-- duplication deliberee que tenant_id.
-- =====================================================================================

-- --- calendar : cree avant project (project.calendar_id le reference) et avant task -----
-- Calendrier projet | tache | ressource : jours ouvres + exceptions. project_id NULL =>
-- calendrier tenant/base reutilisable. Aucune FK sortante hors pilotage/public.tenants/public.teams.
CREATE TABLE IF NOT EXISTS pilotage.calendar (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id           BIGINT      NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_calendar_team_id    ON pilotage.calendar(team_id);
CREATE INDEX IF NOT EXISTS idx_calendar_project_id ON pilotage.calendar(project_id);

-- --- calendar_exception : jours derogatoires d'un calendrier -----------------------------
CREATE TABLE IF NOT EXISTS pilotage.calendar_exception (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id        BIGINT      NOT NULL REFERENCES public.teams(id),
    calendar_id    BIGINT      NOT NULL REFERENCES pilotage.calendar(id) ON DELETE CASCADE,
    exception_date DATE        NOT NULL,
    is_working     BOOLEAN     NOT NULL,
    working_time   JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_calendar_exception_tenant_id   ON pilotage.calendar_exception(tenant_id);
CREATE INDEX IF NOT EXISTS idx_calendar_exception_team_id     ON pilotage.calendar_exception(team_id);
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
    team_id             BIGINT       NOT NULL REFERENCES public.teams(id),
    project_id          BIGINT       NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    phase_id            BIGINT,
    parent_task_id      BIGINT,
    wbs_code            VARCHAR(64),
    position            INT          NOT NULL,
    name                VARCHAR(512) NOT NULL,
    node_kind           VARCHAR(16)  NOT NULL,
    shared_in_roadmap   BOOLEAN      NOT NULL,
    -- horizon (EN22.1c, contrat fige §c/§e) : bucket Now/Next/Later de la vue macro
    -- (US22.3.3), nullable, pas d'axe temporel ni table dediee. NULL = non bucketise.
    horizon             VARCHAR(8),
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
        CHECK (scheduling_mode IS NULL OR scheduling_mode IN ('AUTO', 'MANUAL')),
    CONSTRAINT chk_task_horizon
        CHECK (horizon IS NULL OR horizon IN ('NOW', 'NEXT', 'LATER'))
);
-- Auto-reference WBS + rattachement phase, ajoutes apres creation de task (et de phase).
ALTER TABLE pilotage.task
    DROP CONSTRAINT IF EXISTS fk_task_parent_task;
ALTER TABLE pilotage.task
    ADD CONSTRAINT fk_task_parent_task
        FOREIGN KEY (parent_task_id) REFERENCES pilotage.task(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_task_tenant_id      ON pilotage.task(tenant_id);
CREATE INDEX IF NOT EXISTS idx_task_team_id        ON pilotage.task(team_id);
CREATE INDEX IF NOT EXISTS idx_task_project_id     ON pilotage.task(project_id);
CREATE INDEX IF NOT EXISTS idx_task_phase_id       ON pilotage.task(phase_id);
CREATE INDEX IF NOT EXISTS idx_task_parent_task_id ON pilotage.task(parent_task_id);

-- --- phase : regroupement macro, adosse a une tache recapitulative racine (optionnel) -----
-- phase.parent_task_id -> task(id) (NULL = regroupement macro simple). Cree apres task pour
-- resoudre la FK ; task.phase_id -> phase(id) ajoute juste apres (cycle phase<->task).
CREATE TABLE IF NOT EXISTS pilotage.phase (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id        BIGINT       NOT NULL REFERENCES public.teams(id),
    project_id     BIGINT       NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    parent_task_id BIGINT       REFERENCES pilotage.task(id) ON DELETE SET NULL,
    name           VARCHAR(255) NOT NULL,
    position       INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_phase_tenant_id      ON pilotage.phase(tenant_id);
CREATE INDEX IF NOT EXISTS idx_phase_team_id        ON pilotage.phase(team_id);
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
    team_id              BIGINT      NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_task_dependency_team_id     ON pilotage.task_dependency(team_id);
CREATE INDEX IF NOT EXISTS idx_task_dependency_predecessor ON pilotage.task_dependency(predecessor_task_id);
CREATE INDEX IF NOT EXISTS idx_task_dependency_successor   ON pilotage.task_dependency(successor_task_id);

-- --- task_constraint : 0..1 par tache (UNIQUE task_id) ------------------------------------
-- constraint_date requis si type != ASAP/ALAP => regle SERVICE (le DDL laisse nullable, le
-- moteur/valideur applicatif l'exige selon le type) — hors perimetre DDL EN22.1a.
CREATE TABLE IF NOT EXISTS pilotage.task_constraint (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id         BIGINT      NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_task_constraint_team_id   ON pilotage.task_constraint(team_id);

-- --- assignment : ressource affectee a une tache (ref logique, pas de FID inter-modules) --
-- resource_ref = reference LOGIQUE (identite resolue via bus PIVOT — ADR-006, aucune FK
-- inter-modules). cost_amount = cout planning interne au Gantt (pas l'agregat budget E26).
CREATE TABLE IF NOT EXISTS pilotage.assignment (
    id                     BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT        NOT NULL REFERENCES public.tenants(id),
    team_id                BIGINT        NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_assignment_team_id   ON pilotage.assignment(team_id);
CREATE INDEX IF NOT EXISTS idx_assignment_task_id   ON pilotage.assignment(task_id);

-- --- task_progress : 1:1 task (UNIQUE task_id) --------------------------------------------
CREATE TABLE IF NOT EXISTS pilotage.task_progress (
    id                        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                 BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id                   BIGINT       NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_task_progress_team_id   ON pilotage.task_progress(team_id);

-- --- task_progress_history : audit trail append-only (US22.4.8 security AC) ---------------
-- Une ligne par saisie d'avancement (jamais mise a jour) - distinct de task_progress (etat
-- courant, UNIQUE task_id). actor_ref = reference LOGIQUE (identite resolue via bus PIVOT -
-- ADR-006, aucune FK inter-modules, meme esprit que assignment.resource_ref) : TenantContext
-- n'est pas encore consommable (gap pivot-core-starter, CLAUDE.md).
CREATE TABLE IF NOT EXISTS pilotage.task_progress_history (
    id                        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                 BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id                   BIGINT       NOT NULL REFERENCES public.teams(id),
    task_id                   BIGINT       NOT NULL REFERENCES pilotage.task(id) ON DELETE CASCADE,
    actor_ref                 VARCHAR(255) NOT NULL,
    percent_complete          NUMERIC(5,2) NOT NULL,
    physical_percent_complete NUMERIC(5,2),
    actual_start              TIMESTAMPTZ,
    actual_finish             TIMESTAMPTZ,
    status_date               DATE,
    recorded_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_progress_history_tenant_id ON pilotage.task_progress_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_task_progress_history_team_id   ON pilotage.task_progress_history(team_id);
CREATE INDEX IF NOT EXISTS idx_task_progress_history_task_id   ON pilotage.task_progress_history(task_id);

-- --- baseline : 0..10 par projet + baseline_snapshot (fige par tache) ---------------------
-- UNIQUE (project_id, baseline_index) + CHECK baseline_index 0..10.
CREATE TABLE IF NOT EXISTS pilotage.baseline (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id        BIGINT      NOT NULL REFERENCES public.teams(id),
    project_id     BIGINT      NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    baseline_index SMALLINT    NOT NULL,
    captured_at    TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_baseline_index CHECK (baseline_index BETWEEN 0 AND 10),
    CONSTRAINT uq_baseline_project_index UNIQUE (project_id, baseline_index)
);
CREATE INDEX IF NOT EXISTS idx_baseline_tenant_id  ON pilotage.baseline(tenant_id);
CREATE INDEX IF NOT EXISTS idx_baseline_team_id    ON pilotage.baseline(team_id);
CREATE INDEX IF NOT EXISTS idx_baseline_project_id ON pilotage.baseline(project_id);

CREATE TABLE IF NOT EXISTS pilotage.baseline_snapshot (
    id                    BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES public.tenants(id),
    team_id               BIGINT        NOT NULL REFERENCES public.teams(id),
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
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_team_id     ON pilotage.baseline_snapshot(team_id);
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_baseline_id ON pilotage.baseline_snapshot(baseline_id);
CREATE INDEX IF NOT EXISTS idx_baseline_snapshot_task_id     ON pilotage.baseline_snapshot(task_id);

-- =====================================================================================
-- EN18.10 — Profil d'organisation par defaut (couture de decouplage E40).
--
-- Backing REEL du seam DefaultAltitudeProvider (EN22.1c) : resolveProfile(tenant) renvoie
-- un DefaultOrganizationProfile { altitude, classe de souverainete, niveau de rigueur,
-- modules par defaut }. Contrat de lecture STABLE, identique a ce qu'E40 implementera par
-- substitution (aucun changement des consommateurs E22/E03).
--
-- Cette table porte l'OVERRIDE OPTIONNEL en base : UN SEUL profil par tenant (UNIQUE
-- tenant_id — inchange par le retrofit team_id ci-dessous). Absente => la resolution retombe
-- sur le DEFAUT VERSIONNE (constantes / @ConfigurationProperties `pivot.profile.default.*`,
-- resolu a la volee — jamais de ligne fantome ecrite). tenant_id inexistant (pas de
-- public.tenants) => TenantNotFoundException (404 equivalent) cote service, jamais de profil
-- fabrique.
--
-- Retrofit team_id (ecart EN18.10 #1, cf. bandeau en tete de fichier) : team_id BIGINT NOT
-- NULL REFERENCES public.teams(id) + index dedie ajoutes ICI AUSSI, MAIS avec une semantique
-- differente des autres tables de ce fichier : ce profil reste un contrat resolu PAR TENANT
-- (resolveProfile(tenant) — contrat fige EN22.1c/E22/E03, substituable par E40 SANS changer sa
-- signature, cf. en-profil-organisation-defaut.md "resolveProfile(tenant) ... signature
-- identique a celle qu'implementera E40"). team_id ne participe donc PAS a la cle de
-- resolution/unicite (UNIQUE reste (tenant_id) seul) : il porte l'equipe pour le compte de
-- laquelle l'override a ete demande (attribution/audit), exigee par le nouvel endpoint
-- d'ecriture PUT /api/pilotage/organization-profile/{tenantId} (corps JSON incluant teamId) —
-- pas une dimension de filtrage supplementaire. Faire de team_id une cle de resolution
-- casserait le contrat fige consomme par E22 (DefaultAltitudeProvider)/E03 sans justification
-- fonctionnelle (le profil de gouvernance — altitude/souverainete/rigueur — est une politique
-- d'ORGANISATION, pas une variation par equipe) ; documente explicitement ici pour ne pas
-- rouvrir le sujet sans coordination E22/E03/E40.
--
-- default_modules en JSONB (@JdbcTypeCode(SqlTypes.JSON) cote JPA) : l'ensemble par defaut
-- des modules. L'activation REELLE des modules reste propriete de pivot-core (E03/registre),
-- cablee via pivot-core-starter (gap TODO-SETUP §5) — ici on ne PORTE que l'ensemble.
--
-- Vocabulaire de souverainete (ecart EN18.10 #2) : sovereignty_class utilisait NEUTRAL /
-- RESTRICTED / SOVEREIGN, incoherent avec ADR-015 (zones A souveraine / B controlee / C DMZ
-- externe). Renomme en ZONE_A_SOUVERAINE / ZONE_B_CONTROLEE / ZONE_C_DMZ_EXTERNE. Mapping
-- retenu (documente aussi dans SovereigntyClass.java) : SOVEREIGN -> ZONE_A_SOUVERAINE (racine
-- lexicale commune, contraintes de souverainete strictes = zone A "self-host/air-gap") ;
-- NEUTRAL -> ZONE_B_CONTROLEE (c'est aussi le DEFAUT VERSIONNE : "classe la plus neutre" =
-- fonctionnement SaaS standard, tenant UE/VPC controle — PAS la zone A, disproportionnellement
-- restrictive pour un defaut universel, ni la zone C, reservee aux connexions API tierces/DMZ,
-- inappropriee comme posture par defaut) ; RESTRICTED -> ZONE_C_DMZ_EXTERNE (par elimination,
-- posture d'exposition externe explicite).
--
-- Enums (altitude/sovereignty_class/rigor_level) stockes en VARCHAR + CHECK (convention
-- pivot : @Enumerated STRING). FK tenant_id vers public.tenants(id), NOT NULL, indexee via
-- l'index unique ; FK team_id vers public.teams(id), NOT NULL, indexee separement. Aucune FK
-- sortante hors public.tenants/public.teams (ADR-006).
-- =====================================================================================
CREATE TABLE IF NOT EXISTS pilotage.organization_profile (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id           BIGINT      NOT NULL REFERENCES public.teams(id),
    altitude          VARCHAR(16) NOT NULL,
    sovereignty_class VARCHAR(32) NOT NULL,
    rigor_level       VARCHAR(16) NOT NULL,
    default_modules   JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_profile_tenant UNIQUE (tenant_id),
    CONSTRAINT chk_organization_profile_altitude CHECK (altitude IN ('MACRO', 'DETAIL')),
    CONSTRAINT chk_organization_profile_sovereignty_class
        CHECK (sovereignty_class IN ('ZONE_A_SOUVERAINE', 'ZONE_B_CONTROLEE', 'ZONE_C_DMZ_EXTERNE')),
    CONSTRAINT chk_organization_profile_rigor_level
        CHECK (rigor_level IN ('LIGHT', 'STANDARD', 'STRICT'))
);
CREATE INDEX IF NOT EXISTS idx_organization_profile_tenant_id
    ON pilotage.organization_profile(tenant_id);
CREATE INDEX IF NOT EXISTS idx_organization_profile_team_id
    ON pilotage.organization_profile(team_id);

-- =====================================================================================
-- US22.3.1 — Roadmap rapide : lanes (theme / equipe / objectif).
--
-- Concept introduit par cette US (Gate 1 backlog sous-specifie niveau technique -- decision
-- PO Agent + Architecte consignee ici, dans le contrat REST et dans le fichier backlog
-- pivot-docs, section "Notes d'implementation") : une "lane" est un regroupement HORIZONTAL et
-- PLAT (pas de hierarchie) de la roadmap macro -- un theme, une equipe ou un objectif, au choix
-- de l'utilisateur (aucune taxonomie figee cote schema : name est un libelle libre, pas
-- d'enum/colonne "kind"). Distincte de pilotage.phase (regroupement macro adosse a une tache
-- recapitulative racine, axe temps/WBS, EN22.1a) : une lane est un axe ORTHOGONAL au temps,
-- jamais adossee a une tache -- les deux concepts coexistent sans se substituer l'un a l'autre.
--
-- Conforme a la note d'implementation du backlog ("l'initiative creee ici est une vue macro
-- posee sur le meme graphe temporel que le Gantt (EN22.1) -- pas d'entite separee ni de double
-- saisie") : une "initiative" reste un pilotage.task existant (node_kind LEAF, shared_in_roadmap
-- true), jamais une nouvelle table d'entite "initiative" -- seule la colonne task.lane_id
-- ci-dessous est ajoutee pour l'y rattacher.
--
-- task.lane_id est NULLABLE : seules les taches creees via le flux "roadmap rapide" (US22.3.1)
-- portent une lane ; les taches Gantt detaillees (EN22.1a/b) n'en ont pas besoin. Aucun
-- ON DELETE explicite -> comportement par defaut Postgres (NO ACTION) : supprimer une lane qui
-- porte encore des initiatives est refuse -- aucune US de suppression de lane n'est specifiee a
-- ce stade (hors perimetre US22.3.1, cf. fichier backlog).
--
-- UNIQUE (project_id, name) : deux lanes du meme libelle dans le meme projet seraient une
-- ambiguite pour l'utilisateur (quelle lane recoit l'initiative ?) -- rejetee 409 cote service
-- (DuplicateLaneNameException), pas une erreur silencieuse.
--
-- tenant_id/team_id dupliques sur lane, meme principe de duplication deliberee que toutes les
-- autres tables pilotage.* de ce fichier (filtrage direct sans jointure systematique).
-- =====================================================================================
CREATE TABLE IF NOT EXISTS pilotage.lane (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id    BIGINT       NOT NULL REFERENCES public.teams(id),
    project_id BIGINT       NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    position   INT          NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_lane_project_name UNIQUE (project_id, name)
);
CREATE INDEX IF NOT EXISTS idx_lane_tenant_id  ON pilotage.lane(tenant_id);
CREATE INDEX IF NOT EXISTS idx_lane_team_id    ON pilotage.lane(team_id);
CREATE INDEX IF NOT EXISTS idx_lane_project_id ON pilotage.lane(project_id);

-- Rattachement task -> lane (roadmap-rapide uniquement, NULLABLE), ajoute maintenant que lane existe.
ALTER TABLE pilotage.task
    ADD COLUMN IF NOT EXISTS lane_id BIGINT REFERENCES pilotage.lane(id);
CREATE INDEX IF NOT EXISTS idx_task_lane_id ON pilotage.task(lane_id);

-- =====================================================================================
-- US22.3.5 — Partage & export de la roadmap : lien de partage lecture seule.
--
-- Decision PO Agent + Architecte (fiche backlog sous-specifiee niveau technique -- "Notes
-- d'implementation" : "le lien de partage lecture seule necessite un mecanisme de
-- token/permission dedie") : nouvelle petite table dediee, hors du graphe temporel
-- (pilotage.task/lane) -- un lien de partage n'est pas une donnee de planning mais un
-- mecanisme d'acces. Replique exactement le pattern pivot-core public.access_tokens (cf.
-- fr.pivot.auth.entity.AccessToken / fr.pivot.auth.util.CryptoUtils.sha256) : le token BRUT
-- (256 bits SecureRandom, hex-encode 64 caracteres) n'est JAMAIS persiste, seul son hash
-- SHA-256 (token_hash, 64 hex chars) l'est -- meme discipline, pas de sel necessaire (le token
-- est deja une valeur aleatoire opaque a haute entropie, jamais un secret utilisateur a faible
-- entropie type mot de passe/OTP).
--
-- Portee stricte du lien : project_id NOT NULL (jamais un lien "portefeuille" ou "tenant"
-- entier) -- l'AC securite ("le lien ne doit exposer que les donnees de la roadmap
-- concernee") est imposee des le schema : ON DELETE CASCADE avec pilotage.project (un lien de
-- partage n'a plus de sens si son projet est supprime).
--
-- Contrairement aux autres tables pilotage.* de ce fichier, PAS de colonne updated_at : un lien
-- de partage n'est jamais "modifie" au sens metier -- seul un evenement ponctuel (revocation,
-- capture par revoked_at) peut lui arriver, exactement le modele de
-- fr.pivot.auth.entity.AccessToken (created_at/revoked_at/rotated_at, pas d'updated_at).
--
-- revoked_at NULLABLE (NULL = jamais revoque) et expires_at NULLABLE (NULL = pas d'expiration
-- programmee, revocation manuelle uniquement) -- les deux mecanismes de fin de vie coexistent
-- et sont independants, verifies ensemble cote service (isActive() = revoked_at IS NULL AND
-- (expires_at IS NULL OR expires_at > now())).
--
-- token_hash UNIQUE : deux liens ne peuvent jamais partager le meme hash (collision
-- statistiquement nulle sur 256 bits, mais la contrainte protege aussi contre un bug de
-- generation qui reutiliserait l'entropie).
--
-- tenant_id/team_id dupliques sur la table (meme principe de duplication deliberee que toutes
-- les autres tables pilotage.* de ce fichier) : permet un filtrage direct des liens d'un projet
-- sans jointure, utilise par la gestion authentifiee (lister/revoquer, gate RoadmapEditPolicy)
-- -- jamais utilise par la voie de consultation publique (par token), qui ne connait au depart
-- que le token, pas le tenant/team/project.
-- =====================================================================================
CREATE TABLE IF NOT EXISTS pilotage.roadmap_share_link (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id    BIGINT      NOT NULL REFERENCES public.teams(id),
    project_id BIGINT      NOT NULL REFERENCES pilotage.project(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    CONSTRAINT uq_roadmap_share_link_token_hash UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_roadmap_share_link_tenant_id  ON pilotage.roadmap_share_link(tenant_id);
CREATE INDEX IF NOT EXISTS idx_roadmap_share_link_team_id    ON pilotage.roadmap_share_link(team_id);
CREATE INDEX IF NOT EXISTS idx_roadmap_share_link_project_id ON pilotage.roadmap_share_link(project_id);

-- =====================================================================================
-- US23.2.2 — Tableaux de bord personnalisables (E23 Portefeuille, F23.2 portefeuille-comites).
--
-- Deux tables : dashboard_config (1 par utilisateur, dans les bornes tenant/team) et
-- dashboard_widget (0..N par dashboard, ON DELETE CASCADE).
--
-- user_id SANS FK (decision documentee dans DashboardConfig.java) : pivot-pilotage-core/CLAUDE.md
-- restreint les FK cross-schema a UNIQUEMENT public.tenants(id)/public.teams(id) -- jamais
-- public.users(id). Meme principe de "reference logique, pas de FK inter-module" deja utilise par
-- pilotage.assignment.resource_ref (identite resolue ailleurs, jamais jointe). user_id reste un
-- simple BIGINT, palliatif d'ere-gap pour l'identite de l'appelant (cf. DashboardController).
--
-- UNIQUE (tenant_id, team_id, user_id) : contrairement a pilotage.organization_profile (UNIQUE
-- tenant_id seul, team_id attribution seule -- raison historique documentee plus haut dans ce
-- fichier, contrat fige EN22.1c/E22/E03), cette table est nouvelle et suit au contraire la
-- convention dominante du fichier (tenant_id ET team_id comme dimensions de resolution reelles,
-- comme project/task/lane/...) -- un utilisateur peut avoir un dashboard distinct par equipe.
--
-- profile VARCHAR(64) NOT NULL, libelle libre (pas d'enum) : AC1 lit le "profil" comme une entree
-- EXTERNE (persona/role de l'appelant, a terme fournie par TenantContext, cf. CLAUDE.md §gap) --
-- ce module ne possede ni n'invente de taxonomie fermee (frontmatter backlog "Profils: Tous"),
-- meme principe que pilotage.lane.name ("aucune taxonomie figee cote schema").
--
-- dashboard_widget.application_id NOT NULL REFERENCES pilotage.application(id) ON DELETE CASCADE :
-- tous les types de widget definis a ce jour (DashboardWidgetType) sont scopes a une Application
-- (EN18.9). Un widget non scope necessiterait de relacher cette colonne -- pas fait par
-- anticipation, aucun type de widget actuel n'en a besoin (meme posture que "pas de suppression de
-- lane implementee avant qu'une US le demande").
--
-- Bornes de grille (4 colonnes) : CHECK grid_column 0..3, grid_width/grid_height 1..4,
-- grid_column + grid_width <= 4 -- defense-in-depth, la validation porteuse du message
-- utilisateur (AC erreur "disposition hors bornes") vit dans DashboardService, jamais relayee
-- depuis une violation SQL brute (meme principe que l'acyclicite de pilotage.task_dependency,
-- verifiee cote moteur/service, pas en DDL).
--
-- widget_type valide par CHECK IN (...) : contrairement a lane.name, un type de widget inconnu
-- doit etre rejete explicitement (AC erreur "widget inconnu") -- catalogue ferme, pas un libelle.
--
-- tenant_id/team_id dupliques sur dashboard_widget, meme principe de duplication deliberee que
-- toutes les autres tables pilotage.* de ce fichier (filtrage direct sans jointure systematique).
-- =====================================================================================
CREATE TABLE IF NOT EXISTS pilotage.dashboard_config (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id     BIGINT      NOT NULL REFERENCES public.teams(id),
    user_id     BIGINT      NOT NULL,
    profile     VARCHAR(64) NOT NULL,
    view_mode   VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_dashboard_config_tenant_team_user UNIQUE (tenant_id, team_id, user_id),
    CONSTRAINT chk_dashboard_config_view_mode CHECK (view_mode IN ('SYNTHETIC', 'DETAILED')),
    CONSTRAINT chk_dashboard_config_profile_not_blank CHECK (btrim(profile) <> '')
);
CREATE INDEX IF NOT EXISTS idx_dashboard_config_tenant_id ON pilotage.dashboard_config(tenant_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_config_team_id   ON pilotage.dashboard_config(team_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_config_user_id   ON pilotage.dashboard_config(user_id);

CREATE TABLE IF NOT EXISTS pilotage.dashboard_widget (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id             BIGINT      NOT NULL REFERENCES public.teams(id),
    dashboard_config_id BIGINT      NOT NULL REFERENCES pilotage.dashboard_config(id) ON DELETE CASCADE,
    application_id      BIGINT      NOT NULL REFERENCES pilotage.application(id) ON DELETE CASCADE,
    widget_type         VARCHAR(32) NOT NULL,
    position            INT         NOT NULL,
    grid_row            INT         NOT NULL,
    grid_column         INT         NOT NULL,
    grid_width          INT         NOT NULL DEFAULT 1,
    grid_height         INT         NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_dashboard_widget_type
        CHECK (widget_type IN ('PORTFOLIO_STATUS_SUMMARY', 'WEATHER_ALERTS', 'STRATEGIC_MILESTONES')),
    CONSTRAINT chk_dashboard_widget_position CHECK (position >= 0),
    CONSTRAINT chk_dashboard_widget_grid_row CHECK (grid_row >= 0),
    CONSTRAINT chk_dashboard_widget_grid_column CHECK (grid_column BETWEEN 0 AND 3),
    CONSTRAINT chk_dashboard_widget_grid_width CHECK (grid_width BETWEEN 1 AND 4),
    CONSTRAINT chk_dashboard_widget_grid_height CHECK (grid_height BETWEEN 1 AND 4),
    CONSTRAINT chk_dashboard_widget_grid_column_bounds CHECK (grid_column + grid_width <= 4)
);
CREATE INDEX IF NOT EXISTS idx_dashboard_widget_tenant_id      ON pilotage.dashboard_widget(tenant_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_widget_team_id        ON pilotage.dashboard_widget(team_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_widget_config_id      ON pilotage.dashboard_widget(dashboard_config_id);
CREATE INDEX IF NOT EXISTS idx_dashboard_widget_application_id ON pilotage.dashboard_widget(application_id);
