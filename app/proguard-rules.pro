# R8 rules for release builds.
# Cycler stores data with hand-written org.json code and uses no reflection, so no keep
# rules are needed for its own classes. MapLibre and Health Connect ship their own rules.

# Keep file names and line numbers so crash stack traces can be decoded with mapping.txt
# (app/build/outputs/mapping/release/mapping.txt; keep it with each release).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
