# TODO-SETUP.md — pivot-pilotage-core

Ce dépôt vient d'être bootstrappé (squelette Maven/Spring Boot + CI/CD + sécurité), à l'identique
de la structure `pivot-core`. Une classic branch protection minimale et une ruleset basique
(`protect-main`) sont déjà actives sur `main` — voir le commentaire de PR ou l'historique
`gh api repos/PIVOT-PLATFORM/pivot-pilotage-core/branches/main/protection`. La ruleset stricte
`main-protection` (10 status checks requis, comme sur `pivot-core`) n'est **volontairement pas**
activée — elle rendrait `main` définitivement non-mergeable tant que les points ci-dessous ne sont
pas traités.

## 1. Créer le projet SonarCloud

- Organisation SonarCloud : `pivot-platform`
- Project key attendu (déjà câblé dans `.github/workflows/ci.yml`, job `sonar`) :
  `PIVOT-PLATFORM_pivot-pilotage-core`
- **Important** : le secret `SONAR_TOKEN` est **déjà disponible** — c'est un secret
  d'organisation GitHub (`PIVOT-PLATFORM`), hérité automatiquement par tous les repos de l'org,
  vérifié via `gh api repos/PIVOT-PLATFORM/pivot-pilotage-core/actions/organization-secrets`
  (`SONAR_TOKEN`, `GITLEAKS_LICENCE_KEY`, `PLUMBER_TOKEN`, `SEMANTIC_RELEASE_TOKEN` y figurent
  tous). Ce n'est donc **pas le token qui manque** — c'est le **projet SonarCloud lui-même**.
  Sans lui, le job `SonarCloud Analysis` échoue ("project not found"), même avec un token valide.
- Une fois le projet créé et une première analyse réussie sur `main` : ajouter `SonarCloud
  Analysis` (et `SonarCloud Code Analysis` si un second job Sonar existe) à la liste des status
  checks requis.

## 2. Secrets optionnels (non bloquants, fallback déjà en place)

- `SEMGREP_APP_TOKEN`, `PLUMBER_METADATA_TOKEN` : absents des secrets d'organisation actuels.
  Non bloquants — les workflows gèrent leur absence explicitement (Semgrep tourne avec les
  rulesets publics `p/*` sans token ; Plumber saute juste la vérification de version des actions
  tierces, compliance ~93% > seuil 90%). À ajouter uniquement si l'équipe veut la synchronisation
  Semgrep App ou la résolution de version d'actions tierces complète.

## 3. Étendre la liste des status checks requis (une fois SonarCloud opérationnel)

Actuellement requis (classic branch protection) — tous self-contained, ne dépendent d'aucun
secret externe manquant :
`Code Quality - Java`, `Tests Backend (TU + TI)`, `SCA - Dependency Audit`,
`Gitleaks - Secret Scan`, `CodeQL - SAST`, `Semgrep - SAST`, `Plumber - CI/CD Compliance`.

Volontairement **exclus** pour l'instant (raison) :
- `SonarCloud Analysis` / `SonarCloud Code Analysis` — projet SonarCloud inexistant (§1).
- `Maven deploy preview (PR)` / `Docker preview image (PR)` — poussent vers GitHub
  Packages/GHCR ; fonctionnels dès aujourd'hui via `GITHUB_TOKEN` (pas de secret externe
  manquant), mais délibérément laissés hors du gate initial le temps de valider qu'ils tournent
  proprement sur ce nouveau repo (premier run = création du package, comportement à observer).
- `Mutation Testing (PITest)` — indicateur qualité non bloquant par choix produit
  (`continue-on-error: true`, cf. commentaire dans `pr-preview.yml`), pas un gate de merge.

## 4. Activer la ruleset stricte équivalente à `main-protection` (pivot-core)

Une fois §1–§3 réglés : créer une ruleset `main-protection` sur `~DEFAULT_BRANCH` avec les 10
status checks (calquer sur `pivot-core` :
`gh api repos/PIVOT-PLATFORM/pivot-core/rulesets/17948736`), puis désactiver/retirer la classic
branch protection redondante si souhaité (comme sur `pivot-core`, qui fait cohabiter les deux).

## 5. GAP CONNU — `fr.pivot:pivot-core-starter` n'est pas encore consommé

Ce repo **ne déclare pas** de dépendance Maven vers `fr.pivot:pivot-core-starter` dans son
`pom.xml`. Vérifié en lisant `pivot-core/release.yml` : l'artifact réellement publié aujourd'hui
sur GitHub Packages est `fr.pivot:pivot-core` (le jar applicatif complet — repackagé Spring Boot
via `spring-boot-maven-plugin`, publié par `mvn deploy:deploy-file` vers
`fr/pivot/pivot-core/{version}/`), pas un module `-starter` séparé. `pivot-core/CLAUDE.md` et
`pivot-docs/docs/architecture/platform-overview.md` décrivent tous deux l'intention
(`fr.pivot:pivot-core-starter`), mais ce découpage n'existe pas encore dans le `pom.xml` de
pivot-core (pas de profil `release` qui republie sous un artifactId différent).

**Ne pas ajouter de dépendance fictive.** Dès que pivot-core extrait/publie un module
`pivot-core-starter` réellement consommable (ou republie sous ce nom), l'ajouter ici en
dépendance Maven avec la vraie version publiée.

## 6. `deploy.yml` — stub à brancher

Le job "Deploy to production" est un `TODO` (`echo "TODO — ..."`), identique à pivot-core et
pivot-ui à ce stade. Nécessite un environnement GitHub `production` (approvals, secrets
d'environnement) avant de brancher un déploiement réel (SSH + docker compose, Kubernetes...).
