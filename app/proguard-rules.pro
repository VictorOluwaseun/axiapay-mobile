# Keeps Kotlin collection facade superclasses and subclasses in one access boundary.
# AGP 9.4.1 scoped R8 moved MutableCollectionsJVMKt out of its package while its
# IterablesKt superclass remained package-private, causing IllegalAccessError at startup.
# Retain this package identity only; shrinking, optimization and class renaming remain enabled.
-keeppackagenames kotlin.collections
