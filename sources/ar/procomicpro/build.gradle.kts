listOf("ar").map { lang ->
  Extension(
    name = "ProComic Pro",
    versionCode = 2,
    libVersion = "2",
    lang = lang,
    description = "برو كوالك - قاعدة بيانات المانجا والمانهوا والروايات",
    nsfw = false,
    icon = DEFAULT_ICON,
    remoteDependencies = setOf("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0"),
  )
}.also(::register)
