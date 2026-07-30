listOf("ar").map { lang ->
    Extension(
        name = "ProComic",
        versionCode = 1,
        libVersion = "2",
        lang = lang,
        description = "روايات مترجمة - ProComic",
        nsfw = false,
        icon = DEFAULT_ICON,
    )
}.also(::register)
