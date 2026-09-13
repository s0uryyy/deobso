# GitHub Actions templates (inactive)

These files are stored outside `.github/workflows` because the GitHub connection
used to submit this PR does not have permission to create workflows. They do not
run in their current location.

To enable CI, a repository maintainer with workflow write access can copy:

- `build.yml` to `.github/workflows/build.yml` — core tests on push/PR.
- `matrix.yml` to `.github/workflows/matrix.yml` — manually triggered full build matrix.

The templates have not been executed or validated by GitHub Actions yet.
