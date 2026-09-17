# Release shrinking for the van-core app.
#
# The default optimize file already keeps @JavascriptInterface methods (the
# VanApp save bridge), manifest components, and WorkManager's own rules keep
# worker constructors. Only readability is added here: keep names, so a crash
# report from the van points at real code without a mapping file.
-dontobfuscate
