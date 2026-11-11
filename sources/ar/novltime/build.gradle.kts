listOf("ar").map { lang ->
  Extension(
    name = "NovelTime",
    versionCode = 1,
    libVersion = "2",
    lang = lang,
    description = "نوفلتايم - قراءة الروايات والقصص العربية",
    nsfw = false,
    icon = DEFAULT_ICON,
  )
}.also(::register)
