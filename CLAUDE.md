# CLAUDE.md — PIVOT-PILOTAGE-CORE

## Projet

**PIVOT-PILOTAGE-CORE** — backend Java/Spring Boot du domaine **Pilotage** de la suite
collaborative PIVOT : roadmap/Gantt, portefeuille de projets.

Ce dépôt est un **module fonctionnel**, pas le shell applicatif. Il :

1. Expose l'API REST du domaine Pilotage (roadmap, Gantt, portefeuille) sous le préfixe nginx
   `/api/pilotage/` — process et port dédiés (`:8081`), isolation de panne vis-à-vis de
   `pivot-core` (`:8080`).
2. Consomme (ne possède pas) l'authentification, le multi-tenant et le registre de modules —
   ces briques vivent dans **pivot-core**, exportées via `fr.pivot:pivot-core-starter`.
3. Possède son propre schéma PostgreSQL (`pilotage`) sur l'instance partagée de la
   plateforme — voir section BDD ci-dessous.

Le frontend Angular correspondant est **pivot-pilotage-ui** (à créer). La documentation
générale et le backlog vivent dans **pivot-docs**. L'orchestration multi-repo est décrite dans
`pivot-platform/CLAUDE.md` (racine, non versionné).

**État actuel : bootstrap infrastructure uniquement.** Aucune logique métier n'est encore
implémentée — squelette Maven/Spring Boot minimal (compile + boot), CI/CD complète, posture
sécurité alignée sur `pivot-core`. Le code métier (entités roadmap/Gantt/portefeuille,
services, contrôleurs) arrive US par US, cf. `pivot-docs/docs/backlog/`.

**Gap connu** : `fr.pivot:pivot-core-starter` n'est pas encore publié en tant qu'artifact Maven
consommable séparément — ce repo ne déclare donc aucune dépendance vers lui pour l'instant.
Détail vérifié dans `TODO-SETUP.md` §5 — ne pas dupliquer l'explication ailleurs.

---

## Communication

Concise et directe. Techniquement précise. Pas de récapitulatifs inutiles.

**Exceptions (réponses complètes et structurées) :**
- Rédaction ou revue d'US / Epics
- Décisions d'architecture (schéma BDD, contrat de module, intégration pivot-core)
- Avis cybersécurité ou actions irréversibles — **confirmation obligatoire**
- Backlog et critères d'acceptation

---

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven · `--release 24` (pas de preview features) |
| BDD | PostgreSQL 18 (instance partagée plateforme, schéma dédié `pilotage`) · Spring Data JPA · Flyway |
| Cache | Redis |
| Temps réel | WebSocket (STOMP) — **prévu** pour la collaboration live roadmap/Gantt (route nginx `/ws/pilotage/`, cf. `pivot-docs/docs/architecture/platform-overview.md`) ; pas encore dans le `pom.xml` — aucune US temps réel implémentée à ce stade, ne pas surclasser l'état d'avancement |
| Auth / Multi-tenant | **Déléguée à pivot-core** — ce module consomme `TenantContext` et les rôles via `pivot-core-starter` une fois publié (gap : `TODO-SETUP.md` §5). Aucune logique OIDC/session ici |
| Tests | JUnit 5 · Mockito · Testcontainers (TI) |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud · Semantic Release · Plumber |
| Déploiement | Docker · Docker Compose |
| Frontend | → **pivot-pilotage-ui** (à créer · Angular 22 · TypeScript · SCSS · Vitest · Playwright) |

---

## Structure du dépôt

```
pivot-pilotage-core/
├── src/
│   ├── main/java/fr/pivot/pilotage/
│   │   └── PivotPilotageApplication.java   # point d'entrée — packages métier à venir par US
│   ├── main/resources/
│   │   ├── application.yml / application-test.yml
│   │   └── db/migration/      # Flyway schéma `pilotage` — voir règle V1 unique ci-dessous
│   └── test/java/
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .plumber.yaml
├── Dockerfile
└── TODO-SETUP.md   # gaps connus post-bootstrap (SonarCloud, ruleset stricte, pivot-core-starter…)
```

**Maven :** projet single-module, `artifactId` `pivot-pilotage-core`. Pas de dépendance vers
`fr.pivot:pivot-core-starter` tant que le gap ci-dessus n'est pas résolu — voir `TODO-SETUP.md`.

**Migrations Flyway — fichier V1 unique avant la BETA :** tant que le schéma `pilotage` n'est
pas stabilisé (avant la première BETA du produit), tout changement de schéma est plié dans
l'unique `V1__schema_init.sql` plutôt que d'ajouter un `V2__`/`V3__…` séparé — pas d'historique
de migrations incrémentales à maintenir tant que rien n'est en prod. Ne pas créer de nouveau
fichier de migration numéroté sans feu vert explicite du mainteneur (déclenché au démarrage de
la BETA). Règle identique à `pivot-core/CLAUDE.md`, appliquée ici au schéma `pilotage`.

**Architecture BDD :** une seule instance PostgreSQL partagée avec `pivot-core` (même base
`pivot`), schéma dédié `pilotage` géré par les migrations Flyway de ce repo. FK cross-schéma
**uniquement** vers `public.teams.id` / `public.tenants.id` (entités possédées par
`pivot-core`) — jamais l'inverse, et jamais vers le schéma d'un autre module (`agilite`,
`collaboratif`). Cf. `pivot-platform/CLAUDE.md` (racine) pour la table complète des schémas.

Frontend Angular → **pivot-pilotage-ui**. Documentation → **pivot-docs**. Auth/tenant/registre
de modules → **pivot-core**.

---

## Équipe experte

Toute contribution mobilise les experts concernés — les mentionner explicitement dans la réponse.

| Expert | Domaine |
|--------|---------|
| **Architecte Java / Spring** | Architecture Spring Boot, patterns (Repository, Service, DTO), SOLID |
| **Architecte BDD PostgreSQL** | Schéma `pilotage`, migrations Flyway, index, FK cross-schéma vers `public` |
| **Expert DevSecOps** | CI/CD GitHub Actions, SonarCloud, Semgrep, Gitleaks, Plumber, SBOM, Semantic Release |
| **Expert Red Team** | OWASP Top 10, injection SQL, IDOR, CSRF sur les futurs endpoints roadmap/Gantt/portefeuille |
| **Expert Blue Team** | Hardening applicatif, isolation tenant, audit log, réponse aux rapports Red Team |
| **Expert QA** | Stratégie TU/TI, Testcontainers, coverage ≥ 85 %, non-régression |
| **Expert RGPD** | Conformité RGPD/CNIL sur les données de planning/projet (noms, assignations, commentaires) |
| **Product Owner** | Backlog markdown pivot-docs, Epics, US, critères d'acceptation, priorisation |
| **Scrum Master** | Coordination, sprints, impediments, backlog consistency |
| **Expert PR Review** | Relecture croisée neutre : cohérence architecture, lisibilité, dette technique, respect des standards PIVOT |
| **Expert OIDC / IAM** | Pas de rôle dédié dans ce repo — **délègue à pivot-core** pour tout ce qui est auth/OIDC/rôles ; ce module consomme `TenantContext` en lecture seule une fois `pivot-core-starter` publié |
| **Experts Angular / UX/UI** | → **pivot-pilotage-ui** (à créer) |

### Faire appel aux experts

| Type de tâche | Expert(s) |
|---------------|-----------|
| Controller, Service, Repository Java (roadmap/Gantt/portefeuille) | **Architecte Java / Spring** |
| Schéma BDD `pilotage`, migration Flyway, requête @Query, FK vers `public` | **Architecte BDD PostgreSQL** |
| Tests TU/TI, Testcontainers, couverture | **Expert QA** |
| CI/CD, GitHub Actions, Plumber, SBOM | **Expert DevSecOps** |
| Vulnérabilité sécurité, vecteur d'attaque | **Expert Red Team** → **Expert Blue Team** |
| Isolation tenant sur un endpoint `/api/pilotage/*` | **Expert Blue Team** + **Architecte Java / Spring** |
| RGPD, données de planning/projet | **Expert RGPD** |
| Backlog, US, acceptance criteria | **Product Owner** |
| Intégration `pivot-core-starter` (une fois publié), contrat `PivotModule` | **Architecte Java / Spring** + coordination **pivot-core** |
| Review finale PR (après "prêt pour review") | **Expert PR Review** |
| Bug inexpliqué | **Architecte Java** en premier, puis **Expert Red Team** si suspicion sécurité |
| Auth, OIDC, rôles | → **pivot-core** |
| Frontend Angular, SCSS, composants | → **pivot-pilotage-ui** |

**Règles :**
- Mentionner l'expert explicitement quand son domaine est engagé.
- Toute faille Red Team = correction Blue Team **avant** tout merge.
- Changement du contrat de module (`PivotModule`, une fois consommé) = tous les experts concernés **+ coordination pivot-core ↔ pivot-pilotage-ui obligatoire**.

---

## Backlog — Fichiers markdown

> **Sources de vérité :**
> - Hiérarchie backlog + conventions : `pivot-docs/docs/backlog/README.md`
> - Sprints, assignation US, état avancement : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint, index dans `sprints/README.md`)
> - Backlog opérationnel : **fichiers markdown dans `pivot-docs/docs/backlog/`** — un fichier par US/Enabler avec frontmatter (`Stage`, `Priority`, `Phase`).

### Hiérarchie
`EPIC → FEATURE (valeur) / ENABLER (technique) → US` · clé `E01 → F01.1 / EN01.1 → US01.1.1`.

### Champs du Project

| Champ | Valeurs |
|-------|---------|
| Item Type | Epic / Feature / Enabler / US |
| Parent | clé du parent (ex. `E01`, `F01.1`) |
| Stage | ⬜ (pas encore terminé) / ✅ (Done — recette mainteneur). États intermédiaires internes, non persistés → pivot-docs/docs/backlog/README.md §2/§5 |
| Priority | Critical / High / Medium / Low |
| Module | core / auth / admin / oidc / pilotage / agilite / collaboratif (extensible par domaine) |
| Phase | Socle / v1-enterprise / phase-3 |
| Sprint | Sprint 1…N |
| Size | XS / S / M / L / XL |

### Template US, Definition of Ready, vagues → `pivot-docs/docs/backlog/README.md`.

---

## Breaking Points

### Step 0 — Challenge PO avant implémentation

Avant tout code, le **PO Agent** challenge les ACs de l'US :

1. Vérifier DoR — story complète, ACs Given/When/Then, AC erreur + sécurité
2. Calculer Gate 1 : **= 100** → procéder · **< 100** → PO Agent réécrit ACs → recalculer
3. AC ambigus à l'implémentation → PO Agent clarifie, jamais d'interprétation unilatérale

Pas de blocage humain — Claude autonome de A à Z sur la validation des ACs.

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module (`PivotModule`) sans PRs coordonnées avec `pivot-core`
- Modification touchant l'intégration OIDC / rôles (même en consommation)

→ Label `needs-human-review` + score breakdown + attendre le mainteneur.

---

## Workflow — Organisation par sprint

Travail organisé par sprint. Référence : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint).

**Principes :**
- **Une branche par US / Enabler** — `feat/{us-id}-{slug}` (ex. `feat/us-pilotage-1-1-roadmap-crud`)
- **Agents en parallèle** — un agent par item du sprint, branches séparées
- **Backlog pivot-docs sur la branche courante** — `sprints/sprint-{N}.md` committé sur la branche de l'item (pas de branche docs séparée)
- **Issue GitHub liée** — avant de démarrer un item, vérifier qu'une issue existe dans **ce repo** pour cet US/Enabler (recherche par id/titre). Absente → la créer (titre `{id} — {titre US}`, corps = lien vers le fichier backlog pivot-docs + AC). **Déjà assignée** (humain ou agent en cours) → item déjà pris, ne pas démarrer, passer au suivant. Sinon → se l'auto-assigner immédiatement (`gh issue edit {N} --add-assignee @me`) avant le premier commit — verrouille l'item, empêche qu'un autre agent ou une autre personne ne le reprenne en parallèle. Référencer l'issue dans la PR (`Closes #N`) — fermeture automatique à la fusion, jamais de fermeture manuelle en double.

## Workflow — Merge séquentiel autonome (plusieurs PR)

Quand plusieurs PR sont ouvertes/en attente sur ce repo (ex. plusieurs items d'un même sprint),
Claude détermine seul l'ordre de fusion et l'exécute de bout en bout, sans confirmation par PR :

1. **Ordre** — dépendances fonctionnelles entre items d'abord, puis fichiers partagés
   (migrations Flyway, config Spring commune) pour minimiser les rebases en cascade.
2. **Par PR, dans cet ordre :**
   - Rebase sur `main` à jour (jamais de merge commit)
   - Conflit → résolution manuelle réelle (jamais `--theirs`/`--ours` aveugle) : lire les deux
     côtés, comprendre l'intention de chacun, fusionner le contenu
   - Rebase sans conflit mais fichier partagé → vérifier quand même qu'aucune régression
     sémantique silencieuse ne s'est introduite (ex. une clé de config écrasée par l'auto-merge git)
   - `mvn verify -q` local avant push (ou vérification équivalente si Docker indisponible en
     sandbox — s'appuyer sur la CI réelle pour la partie Testcontainers)
   - Push, attendre la CI réelle en boucle synchrone (jamais d'attente passive d'une notification)
   - Gate 4 selon les seuils déjà définis ci-dessous → squash-merge dès convergence
3. **Dernier item du sprint courant** (vérifier `pivot-docs/docs/backlog/sprints/sprint-{N}.md`)
   → le commit de squash-merge porte le marqueur de release (voir *Workflow — Release*
   ci-dessous), tous les autres non.
4. Incident CI rencontré en cours de route → diagnostiquer et corriger avant de continuer la
   séquence, pas de contournement silencieux.

## Workflow — Release

Le déclenchement d'une release (`release.yml` : version, publish Maven/Docker, tag, changelog)
n'a lieu **qu'en fin de sprint**, jamais à chaque merge — un merge ordinaire ne doit ni bumper de
version ni publier quoi que ce soit.

- **Déclencheur** : le commit du squash-merge du **dernier item d'un sprint** porte le trailer
  `Release-Trigger: true` **sur sa propre ligne, seul, rien d'autre** (`grep -qxE` — match exact
  de ligne entière, jamais une simple sous-chaîne — cf. incident réel documenté sur
  `pivot-core/CLAUDE.md` et `pivot-ui/CLAUDE.md`, section Workflow — Release).
- **Pourquoi** : sans cette règle, chaque merge déclenche `release.yml` — plusieurs merges
  rapprochés calculeraient tous la même "prochaine version" (aucun tag encore créé entre eux) et
  le second à publier échouerait en conflit sur GitHub Packages.
- **Effet** : la release qui finit par se déclencher regroupe automatiquement, dans une seule
  entrée de changelog, tous les commits accumulés depuis le dernier tag — comportement natif de
  semantic-release, pas une fonctionnalité à coder.
- **Ajout du trailer** : `gh pr merge --squash --body "...

Release-Trigger: true"` — trailer sur sa propre ligne finale, précédée d'une ligne vide, jamais
  intégré dans une phrase. Uniquement sur le merge identifié comme dernier item du sprint courant.

## Workflow — Autoloop PR

Après toute modification sur une branche de travail — US/Enabler (`feat/{us-id}-{slug}`) ou
hors sprint (`fix/`, `refactor/`, `chore/`, `docs/`) — **sans exception** :

1. Ouvrir une PR (draft) vers `main`
2. **Autoloop** (20 itérations max) :
   - **En parallèle :**
     - **Review neutre** — Expert PR Review : architecture, AC, sécurité, dette
     - **CI** — `mvn verify -q` = 0 erreur/warning · Gitleaks clean · Gate 3 hard blocks
   - **Corrections** — tous les findings résolus, commit `fix({scope}): ...`
   - **Convergence** — Gate 4 = 100/100 (ou convergence confirmée sans finding restant) ET CI verte → sortir
3. Gate 4 = 100/100 (ou convergence confirmée sans finding restant) :
   - Sortir la PR du mode draft (`gh pr ready`)
   - État interne **Review** (Stage frontmatter US reste `⬜`) — reflété dans `sprints/sprint-{N}.md` (backlog pivot-docs sur la branche courante, cf. règle ci-dessus — pas de branche docs séparée)
   - **Gate 5** — générer/mettre à jour la spec fonctionnelle et technique figée `pivot-docs/docs/specs/{EPIC}/{us-id}-{slug}.md` (branche/PR `pivot-docs` dédiée — jamais de commit cross-repo, voir `pivot-docs/docs/workflow/README.md`)
   - Signal mainteneur
4. Blocage 20 boucles → Breaking Point 2

## Workflow — Ordre d'exécution par US (dans un sprint)

| Étape | Contenu |
|-------|---------|
| **1. Code** | Java + JavaDoc |
| **2. Tests** | JUnit 5 TU + Testcontainers TI — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs verts |
| **4. Gate 2** | Coverage check : ≥ 85 % → continuer · 70–84 % → compléter · < 70 % → stop |
| **5. Backlog** | Mise à jour `sprints/sprint-{N}.md` + statut US **obligatoire avant commit** |
| **6. E2E** | — (délégué à pivot-pilotage-ui) |
| **7. Commit** | `git add` fichier par fichier · commits atomiques sur branche `feat/{us-id}-{slug}` |

> **E2E délégué à pivot-pilotage-ui.** Étapes 5 et 7 non différables (Backlog et Commit).

### Approche tests

Écrire le code d'abord, puis les tests couvrant toutes les branches et conditions limites. TDD strict non utilisé.

**Exception :** quand le contrat d'une API ou d'un service est flou — écrire les tests en premier pour forcer la clarification.

---

## Workflow — Vérifications avant push autonome

**Condition absolue avant tout push autonome : 0 erreur, 0 warning.**

Claude exécute ces commandes **sans attendre d'instruction** :

```bash
mvn verify -q        # compile + tests + Checkstyle + SpotBugs
```

Rapporter ✅ ou stderr complet. Toute erreur ou warning non justifié = **stop, corriger avant push**.

---

## Workflow — Branches

| Préfixe | Usage | Exemple |
|---------|-------|---------|
| `feat/{us-id}-{slug}` | Implémentation d'une US | `feat/us-pilotage-1-1-roadmap-crud` |
| `feat/{en-id}-{slug}` | Implémentation d'un Enabler | `feat/en-pilotage-1-schema-init` |
| `fix/{id}-{slug}` | Correction bug hors sprint | `fix/12-gantt-date-overflow` |
| `refactor/{id}-{slug}` | Refactoring hors sprint | `refactor/18-roadmap-service` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation hors sprint | `docs/adr-pilotage-schema` |

**Règles :**
- Jamais de travail direct sur `main` (le commit de bootstrap initial fait exception —
  autorisé explicitement pour un repo neuf sans historique et sans branch protection)
- **Une branche = un item de sprint** (US ou Enabler)
- **Backlog pivot-docs committé sur la branche de l'item courant**
- Rebase avant merge → squash WIP
- `git push --force-with-lease` uniquement sur branches de travail

**Création de branche item — procédure obligatoire :**
```bash
git checkout main
git pull origin main
git checkout -b feat/{us-id}-{slug}
```
Branche existante → `git checkout feat/{us-id}-{slug}` directement.

---

## Workflow — Commits

Format **Conventional Commits** (`type(scope): message`) — alimente Semantic Release pour le versioning automatique (`feat` → minor, `fix` → patch, `feat!` / `BREAKING CHANGE` → major).

| Commit | Contenu typique |
|--------|----------------|
| `feat(db):` | nouvelle migration Flyway (table, colonne, contrainte) → minor bump |
| `fix(db):` | correction migration Flyway existante → patch bump |
| `chore(db):` | seeds test, commentaires schéma (sans impact utilisateur) |
| `feat(roadmap):` | service, repository, controller roadmap |
| `fix(roadmap):` | correction bug roadmap |
| `feat(gantt):` | service, repository, controller Gantt |
| `fix(gantt):` | correction bug Gantt |
| `feat(portfolio):` | service, repository, controller portefeuille de projets |
| `fix(portfolio):` | correction bug portefeuille |
| `feat(api):` | endpoint REST, DTO |
| `fix(api):` | correction endpoint ou contrat API |
| `test:` | ajout ou correction de tests (TU, TI) sans changement de code prod |
| `ci:` | GitHub Actions workflows, Plumber |
| `docs:` | README, CLAUDE.md, ADR |
| `security:` | correctif sécurité — **hard block Gate 4, review humaine** · label `security` posé automatiquement |

Co-author sur chaque commit : `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

---

## Gates ACDD — Confidence Gates

Score 0–100, jamais booléen. Scores/décisions consignés en **commentaire de PR** (plus de
dossier `gates/`). Le statut vit dans le champ **Stage** du frontmatter US (pivot-docs).

| Gate | Moment | Seuils |
|------|--------|--------|
| **1 — READINESS** | Avant implémentation | PO Agent self-challenge · = 100 → état interne Ready → procéder (Stage frontmatter reste ⬜) · < 100 → PO Agent réécrit ACs |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module |
| **4 — MERGE CONFIDENCE** | Avant merge | = 100/100 → sortie du mode draft (merge autonome) · 60–99 → merge documenté · < 60 → Breaking Point 2 |

**Checks Gate 1 :** AC testables (40) · dépendances résolues (20) · impact contrat module (15) · AC sécurité ≥ 1 (15) · pas de cycle (10)

**Checks Gate 2 :** AC couverts (50) · pas de code non testé (30) · tests non triviaux (20)

**Checks Gate 3 :** SonarCloud ≥ 80 % (25) · zéro finding critique/high (25) · linters clean (20) · Gitleaks clean (20) · build Docker (10)

**Format du commentaire de PR (gate)** : `gate` (READINESS | COVERAGE | QUALITY | MERGE_CONFIDENCE), `score`, `decision`, `breakdown`, `notes`.

---

## Agents IA — Rôles et cycle ACDD

### Philosophie

**ACDD (Acceptance Criteria Driven Development)** — gates de confiance continues.

- Gates → score (0–100), jamais booléen pass/fail
- Chaque gate → consigné en **commentaire de PR** (pas de fichier committé)
- Breaking Points = seuls moments d'intervention humaine obligatoire
- En dehors des Breaking Points : Claude décide selon le score

### Rôles

| Agent | Responsabilité |
|-------|---------------|
| **PO Agent** | Génère Epics et US, rédige AC, clarifie AC ambigus |
| **Architect Agent** | Valide AC techniques, identifie impact contrat de module |
| **Security Agent** | Challenge AC (Red Team), valide fixes (Blue Team) |
| **Dev Agent** | Implémente sur branche dédiée, s'auto-évalue via gates |
| **QA Agent** | Rédige specs E2E, valide couverture, challenge gaps de tests |
| **PR Review Agent** | Exécute Gate 3 + Gate 4, merge ou escalade selon score |

### Cycle

```
PO Agent rédige Epic + US avec AC
    │
    ├── Architect Agent : faisabilité technique, impact contrat de module
    ├── Security Agent : couverture sécurité des AC
    ├── QA Agent : testabilité de chaque AC
    └── → Gate 1 READINESS
           │
           ├── Score = 100 → état interne Ready → procéder (Stage frontmatter reste ⬜) (PO Agent autonome)
           │     └── Le mainteneur valide → Dev Agent implémente
           └── Score < 100 → clarification PO Agent avant tout
```

### Format des AC

```markdown
- [ ] Given [contexte], when [action], then [résultat observable]
- [ ] Error case: given [input invalide], system retourne [erreur / status code]
- [ ] Security: [propriété de sécurité qui doit tenir]
```

Chaque AC mappe à au moins un test. AC sans test = non implémenté, peu importe le code présent.
AC ambigu à l'implémentation → **stopper et demander au PO Agent** — jamais d'interprétation unilatérale.

### Gates (commentaires de PR)

Chaque gate est consigné en **commentaire de PR** (plus de fichiers `gates/`) :
- Gate 1 Readiness (avant implémentation) — PO Agent valide ACs · = 100 → état interne Ready (Stage frontmatter reste ⬜)
- Gate 2 Coverage (après chaque commit)
- Gate 3 Quality (après CI verte)
- Gate 4 Merge confidence (décision finale)

### Labels PR

| Label | Signification |
|-------|--------------|
| `feat` | Nouvelle fonctionnalité |
| `fix` | Correction de bug |
| `security` | Impact sécurité — hard block Gate 4, review humaine |
| `breaking-change` | Changement de contrat — hard block Gate 4, review humaine |
| `module-contract` | Changement contrat de module — hard block Gate 4 |
| `needs-human-review` | Gate 4 < 60 ou hard block — décision humaine requise |
| `auto-approved` | Gate 4 = 100/100 — mergé automatiquement |
| `chore` | Maintenance, CI, dépendances |
| `docs` | Documentation uniquement |

### Post-merge

```bash
# 1. Mainteneur : passe Stage: ⬜ → ✅ dans le frontmatter US (recette humaine — jamais Claude)
# 2. Débloquer les US dépendantes
# 3. Nettoyer la branche
git push origin --delete feat/{us-id}-{slug}
```

---

## Standards de code

### Java (backend)

- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle (config projet — `checkstyle.xml`)
- SpotBugs — zéro warning ignoré · aucune suppression inline (`@SuppressFBWarnings`) sans validation explicite du mainteneur
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — **jamais les entités JPA directement**
- Pas de `@Transactional` sur les contrôleurs — uniquement sur les services

### Général

- Pas de secrets dans le code — variables d'environnement
- Toute action state-changing → log structuré JSON (backend)
- **`// NOSONAR` : zéro, jamais.** Tout faux positif Sonar se marque côté SonarCloud (UI "Won't fix" / "False positive", ou exclusion centralisée) — aucune exception.
- **`// nosemgrep` : interdit par défaut**, autorisé **uniquement avec la validation explicite du mainteneur**. Sans validation, exclusion côté config Semgrep (`.semgrepignore` / `--exclude-rule`), jamais en commentaire inline.

---

## Système de modules

Ce dépôt ne possède **pas** le registre de modules (`ModuleRegistry`, `ModuleActivationService`,
cache Redis `module:{tenantId}:{moduleId}`) — propriété exclusive de **pivot-core**.
`pivot-pilotage-core` est un **module consommateur** : il implémentera l'interface `PivotModule`
(contrat repris de `pivot-core/CLAUDE.md`) dès que `fr.pivot:pivot-core-starter` sera publié en
artifact Maven consommable séparément.

```java
public interface PivotModule {
    String getId();        // "pilotage"
    String getName();      // "Pilotage" (roadmap, Gantt, portefeuille de projets)
    String getVersion();
    boolean isEnabled(TenantContext ctx);
}
```

**Gap actuel** : voir `TODO-SETUP.md` §5 — pas de dépendance fictive ajoutée en attendant.

- Module désactivé (décidé côté `pivot-core`) = 403 sur tout endpoint `/api/pilotage/*` — à
  appliquer dès la première implémentation d'endpoint
- Aucune logique inter-module directe — bus d'événements typés (`ApplicationEventPublisher`),
  cohérent avec `pivot-core`
- Changement de contrat de module = **hard block Gate 4 + Breaking Point 2**

---

## Isolation tenant (rappel — auth déléguée à pivot-core)

Ce module ne gère pas l'authentification, mais **tout endpoint `/api/pilotage/*` futur** devra
respecter la même règle transversale que `pivot-core` :

- Extraire le `tenantId` **exclusivement** du `TenantContext` propagé par `pivot-core-starter`
  (une fois consommable — gap `TODO-SETUP.md` §5), jamais du body JSON, d'un query param ou
  d'un header custom
- Vérifier l'appartenance au tenant courant **avant** tout traitement sur une ressource
  identifiée par path (`{roadmapId}`, `{ganttTaskId}`, `{portfolioId}`…)
- Appartenance invalide → **404** (pas 403 — ne pas confirmer l'existence de la ressource
  cross-tenant)
- Test TI cross-tenant **obligatoire** sur chaque endpoint dès son implémentation

---

## Audits

Dans **pivot-docs** — un fichier par catégorie, mis à jour en place. **Jamais de fichiers datés.**

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push origin main` (push direct) hors bootstrap initial | Jamais — tout code passe par PR + review |
| `git push --force` sur `main` | Jamais — le mainteneur uniquement si nécessaire |
| `git add .` en bloc | Risque d'inclure `.env`, clés, binaires |
| Merger avec label `security` sans revue humaine | Hard block Gate 4 |
| Commiter `.env`, tokens, secrets, certificats | Exposition définitive |
| Entités JPA exposées directement en API | Fuite de schéma, IDOR |
| Logique métier dans les contrôleurs | Viole la séparation des couches |
| Module désactivé avec routes accessibles | Contournement restriction admin |
| Implémenter sans US tracée dans les fichiers markdown backlog | Perte de traçabilité |
| Ajouter une dépendance Maven fictive vers `fr.pivot:pivot-core-starter` avant sa publication réelle | Voir `TODO-SETUP.md` §5 — gap vérifié, ne pas inventer de version |
| `tenantId` extrait du body / header dans un endpoint `/api/pilotage/*` | IDOR cross-tenant — extrait exclusivement du `TenantContext` du token porteur |

---

## Boucles de problèmes — règle d'escalade

### Limite 10 commandes en échec successif

Si **10 commandes consécutives échouent** (toute combinaison : build, test, lint, push, CI) sur une tâche :
1. **Stopper la tâche courante** — ne pas impacter les agents parallèles sur d'autres US
2. **Poster un commentaire de gate** avec `decision: ESCALATED`, liste des 10 échecs, contexte
3. **Label `needs-human-review`** + signal mainteneur
4. **Proposer une alternative** (approche différente, découpage)

Le compteur se remet à zéro dès qu'une commande réussit.

### Limite 20 push — autoloop PR Review

Voir section **Workflow — Autoloop PR** — au-delà de 20 push correctifs → Breaking Point 2 automatique.

### Règle 2 tentatives (stratégie identique)

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Poster un commentaire de gate sur la PR** avec `decision: ESCALATED`, contexte complet, tentatives effectuées — **jamais committer un fichier de gate**
3. **Signaler** au mainteneur : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer le mainteneur.

---

## Template Review PR uniforme

Toutes les reviews de PR (Gate 4) postées en commentaire GitHub suivent ce template exact.
Charger `skill-pr-reviewer` avant d'écrire le commentaire, **une fois disponible dans ce repo**
(cf. section Skills ci-dessous — pas encore créé, se référer à
`pivot-core/.project/skills/skill-pr-reviewer.yaml` en attendant sa réplication ici).

```markdown
## PR Review — Gate 4

**US :** {us-id} — {titre}
**Score : {score}/100**
**Décision : MERGE_AUTONOMOUS | MERGE_DOCUMENTED | NEEDS_HUMAN_REVIEW**

### Breakdown
| Dimension | Score | Détail |
|-----------|-------|--------|
| Architecture (Controller/Service/Repository/DTO, JavaDoc) | /25 | |
| Traçabilité AC (AC → test, coverage Gate 2) | /25 | |
| Sécurité (isolation tenant, secrets, test cross-tenant) | /25 | |
| Qualité (Checkstyle/SpotBugs verts) | /25 | |

### Traçabilité AC
| AC | Implémentation | Test | Statut |
|----|----------------|------|--------|
| AC-{id}-01 | ... | ... | ✅/⬜ |

### Gate 3 — hard blocks
- [ ] Gitleaks clean
- [ ] CI 0 erreur / 0 warning
- [ ] Pas de secret committé
- [ ] Pas de `breaking-change` non documenté
- [ ] Pas de modification contrat module sans coordination pivot-core/pivot-pilotage-ui

### Findings
| # | Sévérité | Fichier | Description | Correction |
|---|----------|---------|--------------|------------|

### Notes
{notes libres}
```

**Règles d'application :**
- Posté uniquement en **commentaire PR** — jamais de fichier committé
- Score calculé dimension par dimension (0–25 chacune)
- Findings classés : 🔴 Bloquant · 🟡 Mineur · 🔵 Cohérent
- Un finding 🔴 = itération obligatoire, quel que soit le score total

---

## Skills — Knowledge Cards

Index à créer (`.project/skills/_index.yaml`) — **pas encore de skills spécifiques au domaine
Pilotage** dans ce repo à ce stade (bootstrap infrastructure uniquement, aucune logique métier).
Ne pas fabriquer de fichiers de skill fictifs : les créer au fil des premières US, sur le
modèle de `pivot-core/.project/skills/` (`skill-spring-architecture.yaml`,
`skill-bdd-flyway.yaml`, `skill-ac-traceability.yaml`, `skill-testing-strategy.yaml`,
`skill-devops-cicd.yaml`, `skill-observability.yaml`, `skill-rgpd.yaml`,
`skill-security-redteam.yaml`, `skill-security-blueteam.yaml`, `skill-pr-reviewer.yaml` —
tous transposables tels quels au domaine Pilotage, sans le contenu spécifique OIDC/module-system
qui reste propriété de pivot-core).

---

## Parallélisation

Lancer un maximum d'actions en parallèle dans chaque message :

| Actions parallélisables | Exemples |
|------------------------|---------|
| Lectures indépendantes | Plusieurs `Read` / `Grep` / `Glob` |
| Linters | Checkstyle + SpotBugs lancés simultanément |
| Créations de fichiers indépendants | TU + TI d'une même feature |
| Recherches codebase | Plusieurs `Grep` sur cibles différentes |

Ne séquencer que ce qui dépend du résultat d'une étape précédente.
