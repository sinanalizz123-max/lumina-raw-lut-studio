# Phase 0: no custom rules (release minify is OFF).
#
# Production R8 starter notes — activate when flipping
# release.isMinifyEnabled to true, then keep ONLY what R8 warns about:
# - Room ships its own consumer rules; add targeted -keep for @Entity/@Dao
#   only if the release build warns.
# - Coil/OkHttp/Okio ship their own rules; do not blanket-keep them.
# - Verify: signed release install + open/share image + JPEG/TIFF export.
