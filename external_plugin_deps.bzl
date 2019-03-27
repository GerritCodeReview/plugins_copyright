load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "commons_io",
        artifact = "commons-io:commons-io:1.4",
        sha1 = "a8762d07e76cfde2395257a5da47ba7c1dbd3dce",
    )
