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
