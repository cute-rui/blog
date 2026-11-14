package blog.writer.content

object FrontMatterCodec {
    fun decode(path: String, source: String): PostDocument {
        val (yaml, body) = split(source)
        val matter = if (yaml == null) {
            PostFrontMatter(title = "未命名", draft = true)
        } else {
            readMatter(parseYamlMap(yaml))
        }
        return PostDocument(path = path, frontMatter = matter, body = body)
    }

    fun encode(document: PostDocument): String = encode(document.frontMatter, document.body)

    fun encode(frontMatter: PostFrontMatter, body: String): String {
        val yaml = writeYamlMap(toMap(frontMatter))
        return "---\n$yaml---\n\n$body"
    }

    private fun split(source: String): Pair<String?, String> {
        val text = source.replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return null to text.removePrefix("\n")
        val rest = text.removePrefix("---\n")
        val end = rest.indexOf("\n---")
        if (end < 0) return null to text
        val yaml = rest.substring(0, end + 1)
        var after = rest.substring(end + "\n---".length)
        if (after.startsWith("\n")) after = after.drop(1)
        val body = if (after.startsWith("\n")) after.drop(1) else after
        return yaml to body
    }

    private fun readMatter(map: Map<String, YamlValue>): PostFrontMatter {
        val known = KNOWN_KEYS
        val publish = map["publish"]?.asMap().orEmpty()
        val wechat = map["wechat"]?.asMap().orEmpty()
        val crop = wechat["coverCrop"]?.asMap()
        return PostFrontMatter(
            title = map["title"]?.asString().orEmpty(),
            description = map["description"]?.asString().orEmpty(),
            pubDate = map["pubDate"]?.asString().orEmpty(),
            updatedDate = map["updatedDate"]?.asString(),
            tags = map["tags"]?.asStringList().orEmpty(),
            draft = map["draft"]?.asBoolean() ?: false,
            heroImage = map["heroImage"]?.asString(),
            heroAlt = map["heroAlt"]?.asString(),
            publish = PublishTargets(
                blog = publish["blog"]?.asBoolean() ?: true,
                wechat = publish["wechat"]?.asBoolean() ?: false,
            ),
            wechat = WechatFields(
                title = wechat["title"]?.asString(),
                author = wechat["author"]?.asString(),
                digest = wechat["digest"]?.asString(),
                cover = wechat["cover"]?.asString(),
                coverCrop = crop?.let { readCrop(it) },
                openComment = wechat["openComment"]?.asBoolean(),
                fansOnlyComment = wechat["fansOnlyComment"]?.asBoolean(),
                sourceUrl = wechat["sourceUrl"]?.asString(),
            ),
            extra = map.filterKeys { it !in known },
        )
    }

    private fun readCrop(map: Map<String, YamlValue>): CoverCrop? {
        val x1 = map["x1"]?.asString()?.toDoubleOrNull() ?: return null
        val y1 = map["y1"]?.asString()?.toDoubleOrNull() ?: return null
        val x2 = map["x2"]?.asString()?.toDoubleOrNull() ?: return null
        val y2 = map["y2"]?.asString()?.toDoubleOrNull() ?: return null
        return CoverCrop(x1, y1, x2, y2)
    }

    private fun toMap(matter: PostFrontMatter): Map<String, YamlValue> {
        val map = linkedMapOf<String, YamlValue>()
        map["title"] = YamlValue.Text(matter.title)
        map["description"] = YamlValue.Text(matter.description)
        if (matter.pubDate.isNotBlank()) map["pubDate"] = YamlValue.Text(matter.pubDate)
        matter.updatedDate?.takeIf { it.isNotBlank() }?.let { map["updatedDate"] = YamlValue.Text(it) }
        map["tags"] = YamlValue.ListValue(matter.tags.map { YamlValue.Text(it) })
        map["draft"] = YamlValue.Bool(matter.draft)
        matter.heroImage?.takeIf { it.isNotBlank() }?.let { map["heroImage"] = YamlValue.Text(it) }
        matter.heroAlt?.takeIf { it.isNotBlank() }?.let { map["heroAlt"] = YamlValue.Text(it) }
        map["publish"] = YamlValue.MapValue(
            linkedMapOf(
                "blog" to YamlValue.Bool(matter.publish.blog),
                "wechat" to YamlValue.Bool(matter.publish.wechat),
            ),
        )
        val wechat = linkedMapOf<String, YamlValue>()
        matter.wechat.title?.takeIf { it.isNotBlank() }?.let { wechat["title"] = YamlValue.Text(it) }
        matter.wechat.author?.takeIf { it.isNotBlank() }?.let { wechat["author"] = YamlValue.Text(it) }
        matter.wechat.digest?.takeIf { it.isNotBlank() }?.let { wechat["digest"] = YamlValue.Text(it) }
        matter.wechat.cover?.takeIf { it.isNotBlank() }?.let { wechat["cover"] = YamlValue.Text(it) }
        matter.wechat.coverCrop?.let { crop ->
            wechat["coverCrop"] = YamlValue.MapValue(
                linkedMapOf(
                    "x1" to YamlValue.Number(trimNumber(crop.x1)),
                    "y1" to YamlValue.Number(trimNumber(crop.y1)),
                    "x2" to YamlValue.Number(trimNumber(crop.x2)),
                    "y2" to YamlValue.Number(trimNumber(crop.y2)),
                ),
            )
        }
        matter.wechat.openComment?.let { wechat["openComment"] = YamlValue.Bool(it) }
        matter.wechat.fansOnlyComment?.let { wechat["fansOnlyComment"] = YamlValue.Bool(it) }
        matter.wechat.sourceUrl?.takeIf { it.isNotBlank() }?.let { wechat["sourceUrl"] = YamlValue.Text(it) }
        if (wechat.isNotEmpty()) map["wechat"] = YamlValue.MapValue(wechat)
        matter.extra.forEach { (key, value) -> if (key !in map) map[key] = value }
        return map
    }

    private fun trimNumber(value: Double): String {
        val raw = value.toString()
        return if (raw.endsWith(".0")) raw.dropLast(2) else raw
    }

    private val KNOWN_KEYS = setOf(
        "title",
        "description",
        "pubDate",
        "updatedDate",
        "tags",
        "draft",
        "heroImage",
        "heroAlt",
        "publish",
        "wechat",
    )
}
