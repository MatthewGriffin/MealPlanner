# Live retailer smoke tests

The backend's ordinary test suite uses fixtures and mocks. The following opt-in tests make real HTTP requests to public catalogue/search pages and should be run separately when checking current retailer-site compatibility.

Run from the repository root, enabling one retailer at a time:

```bash
ALDI_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.aldiPublicCatalogueSearch'
TESCO_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.tescoPublicCatalogueSearch'
SAINSBURYS_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.sainsburysPublicCatalogueSearch'
LIDL_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*RetailerLiveSmokeTest.lidlPublishedProductsSearch'
ICELAND_LIVE_TESTS=true ./gradlew :backend:test --tests '*IcelandLiveSmokeTest'
MORRISONS_LIVE_SMOKE_TEST=true ./gradlew :backend:test --tests '*MorrisonsSitemapLiveSmokeTest'
```

The tests are opt-in because they depend on external websites and can fail when a retailer changes its site, rate-limits requests, or is temporarily unavailable. An enabled test should fail if the adapter cannot produce parseable catalogue results; a disabled test is reported as skipped by the test runner.

These tests validate catalogue retrieval and parser compatibility only. They do **not** prove selected-branch pricing or stock. The adapters must continue to report the price scope and availability supported by their source; a public catalogue result must not be upgraded to branch-specific evidence.

## ASDA remains gated

ASDA is deliberately excluded from automated live smoke tests. Its published grocery terms say site data may not be used by others without express permission (section 2.2.4): https://www.asda.com/terms/grocery. The current Algolia endpoint/key has not been established as an approved integration source, and no express permission or approved feed is recorded in this repository. Do not enable the existing client until that source/permission gap is resolved.
