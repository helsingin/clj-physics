# Releasing clj-physics

This library uses a single source of truth for versions (`version.txt`) and keeps `pom.xml`/JARs in sync via `make` + tools.build.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for versioning rules.

## Prerequisites
- Clojure CLI installed.
- Python 3 available (used in `Makefile`).
- Clojars credentials configured in `~/.m2/settings.xml` (server id `clojars`) or exported as `CLOJARS_USERNAME`/`CLOJARS_PASSWORD`.

## Release steps
1) **Prepare the Codebase**
   - Ensure you are on the `main` branch: `git checkout main`.
   - Merge development changes: `git merge dev` (or your feature branch).
   - Ensure the working tree is clean.

2) **Set the version**
   - Edit `version.txt` with the new version (e.g., `0.1.2`).

3) **Update CHANGELOG.md**
   - Add a new entry for the version.
   - Describe major changes, bug fixes, and additions.

4) **Build**
   - Run `make jar`.
   - This will:
     - Sync `pom.xml` to `version.txt`.
     - Run tools.build to produce `target/physics-<version>.jar`.

5) **Test**
   - Run `clojure -M:test` (or `make test`) and ensure tests pass.

6) **Deploy**
   - Set credentials if not in settings.xml:
     - `export CLOJARS_USERNAME=...`
     - `export CLOJARS_PASSWORD=...`
   - Run `make deploy`.
   - If the version was already published, you’ll see a 403; bump `version.txt` and retry.

7) **Tag and push**
   - Commit changes: `git commit -am "Release 0.3.0"` (ensure version.txt, CHANGELOG.md, pom.xml are included).
   - Tag: `git tag v<version>` (e.g., `git tag v0.1.2`).
   - Push: `git push origin main --tags`.

## Notes
- Version is only edited in `version.txt`.
- `make jar` always refreshes `pom.xml` from `version.txt`.
- Avoid re-deploying a released version; Clojars rejects redeploys for non-snapshots.
