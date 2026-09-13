# GitHub Actions workflows

The workflow files have been moved to `.github/workflows/` now that workflow
write permission is available. `build.yml` runs core tests on push/PR;
`matrix.yml` provides a manually triggered full build matrix.

Check GitHub Actions for actual results; enabling workflows does not itself
confirm compilation or runtime compatibility.
