package club.rivnerun.android

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * URL звідки застосунок тягне актуальні правила скрейпінгу (регулярки).
 * Розмістіть тут посилання на сирий JSON-файл (наприклад,
 * raw.githubusercontent.com/<ваш-акаунт>/<репозиторій>/main/scrape-config.json),
 * щоб мати змогу виправляти парсинг без випуску нової версії застосунку.
 * Поки URL не налаштований або недоступний, застосунок безпечно працює
 * на вбудованих значеннях за замовчуванням [ScrapeConfig.default].
 */
private const val SCRAPE_CONFIG_URL =
    "https://raw.githubusercontent.com/REPLACE_ME/REPLACE_ME/main/scrape-config.json"
private const val SCRAPE_CONFIG_CACHE_NAME = "scrape_config_cache"
private const val SCRAPE_CONFIG_CACHE_KEY = "config"

internal data class ScrapeConfig(
    val version: Int,
    val home: Home,
    val article: Article,
    val news: News,
    val training: Training,
    val races: Races,
    val products: Products
) {
    internal data class Home(val descriptionMeta: String)

    internal data class Article(val articleTag: String, val bodyDivMarker: String)

    internal data class News(
        val articleLink: String,
        val image: String,
        val title: String,
        val date: String
    )

    internal data class Training(
        val table: String,
        val row: String,
        val cell: String,
        val firstParagraph: String,
        val date: String,
        val time: String,
        val removeStrong: String,
        val removeSpan: String,
        val paragraph: String
    )

    internal data class Races(
        val listDivMarker: String,
        val link: String,
        val titleStrong: String,
        val titleHeading: String,
        val date: String
    )

    internal data class Products(
        val link: String,
        val image: String,
        val title: String,
        val price: String
    )

    companion object {
        fun default() = ScrapeConfig(
            version = 1,
            home = Home(
                descriptionMeta = """(?is)<meta\s+name=["']description["']\s+content=["'](.*?)["']"""
            ),
            article = Article(
                articleTag = """(?is)<article\b[^>]*>(.*?)</article>""",
                bodyDivMarker = """(?is)<div\b(?=[^>]*itemprop=["']articleBody["'])[^>]*>"""
            ),
            news = News(
                articleLink = """(?is)<a\s+href="/([^"#?]+)"[^>]*>(.*?)</a>""",
                image = """(?is)<img[^>]+src=["']([^"']*/img/500/[^"']+)["']""",
                title = """(?is)<strong[^>]*>(.*?)</strong>""",
                date = """(?is)<span[^>]*>\s*(\d{2}\.\d{2}\.\d{4})"""
            ),
            training = Training(
                table = """(?is)<table\b[^>]*class=["'][^"']*training-schedule__info[^"']*["'][^>]*>(.*?)</table>""",
                row = """(?is)<tr\b[^>]*>(.*?)</tr>""",
                cell = """(?is)<td\b[^>]*>(.*?)</td>""",
                firstParagraph = """(?is)<p\b[^>]*>(.*?)</p>""",
                date = """(?is)<strong\b[^>]*>(.*?)</strong>""",
                time = """(?is)<span\b[^>]*>(.*?)</span>""",
                removeStrong = """(?is)<strong\b[^>]*>.*?</strong>""",
                removeSpan = """(?is)<span\b[^>]*>.*?</span>""",
                paragraph = """(?is)<p\b[^>]*>(.*?)</p>"""
            ),
            races = Races(
                listDivMarker = """(?is)<div\b(?=[^>]*class=["'][^"']*race-list[^"']*["'])[^>]*>""",
                link = """(?is)<a\b[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
                titleStrong = """(?is)<strong\b[^>]*>(.*?)</strong>""",
                titleHeading = """(?is)<h[1-6]\b[^>]*>(.*?)</h[1-6]>""",
                date = """(?is)<(?:time|span)\b[^>]*>(.*?)</(?:time|span)>"""
            ),
            products = Products(
                link = """(?is)<a\b[^>]*href=["'](/tovar/[^"']+)["'][^>]*>(.*?)</a>""",
                image = """(?is)<img\b[^>]*src=["']([^"']+)["']""",
                title = """(?is)<strong\b[^>]*>(.*?)</strong>""",
                price = """(?is)<span\b[^>]*>(.*?)</span>"""
            )
        )
    }
}

/**
 * Намагається завантажити свіжий конфіг скрейпінгу з мережі; у разі невдачі
 * використовує останній успішно завантажений (кешований), а якщо кешу ще
 * немає — вбудовані значення за замовчуванням. Викликається з IO-потоку.
 */
internal fun resolveScrapeConfig(context: Context): ScrapeConfig {
    val remote = fetchRemoteScrapeConfig()
    if (remote != null) {
        saveCachedScrapeConfig(context, remote)
        return remote
    }
    return loadCachedScrapeConfig(context) ?: ScrapeConfig.default()
}

private fun fetchRemoteScrapeConfig(): ScrapeConfig? = try {
    decodeScrapeConfig(JSONObject(fetchSiteHtml(SCRAPE_CONFIG_URL)))
} catch (error: IOException) {
    null
} catch (error: JSONException) {
    null
}

private fun loadCachedScrapeConfig(context: Context): ScrapeConfig? {
    val json = context.getSharedPreferences(SCRAPE_CONFIG_CACHE_NAME, Context.MODE_PRIVATE)
        .getString(SCRAPE_CONFIG_CACHE_KEY, null) ?: return null
    return try {
        decodeScrapeConfig(JSONObject(json))
    } catch (error: JSONException) {
        null
    }
}

private fun saveCachedScrapeConfig(context: Context, config: ScrapeConfig) {
    context.getSharedPreferences(SCRAPE_CONFIG_CACHE_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(SCRAPE_CONFIG_CACHE_KEY, encodeScrapeConfig(config).toString())
        .apply()
}

private fun encodeScrapeConfig(config: ScrapeConfig): JSONObject = JSONObject().apply {
    put("version", config.version)
    put("home", JSONObject().apply {
        put("descriptionMeta", config.home.descriptionMeta)
    })
    put("article", JSONObject().apply {
        put("articleTag", config.article.articleTag)
        put("bodyDivMarker", config.article.bodyDivMarker)
    })
    put("news", JSONObject().apply {
        put("articleLink", config.news.articleLink)
        put("image", config.news.image)
        put("title", config.news.title)
        put("date", config.news.date)
    })
    put("training", JSONObject().apply {
        put("table", config.training.table)
        put("row", config.training.row)
        put("cell", config.training.cell)
        put("firstParagraph", config.training.firstParagraph)
        put("date", config.training.date)
        put("time", config.training.time)
        put("removeStrong", config.training.removeStrong)
        put("removeSpan", config.training.removeSpan)
        put("paragraph", config.training.paragraph)
    })
    put("races", JSONObject().apply {
        put("listDivMarker", config.races.listDivMarker)
        put("link", config.races.link)
        put("titleStrong", config.races.titleStrong)
        put("titleHeading", config.races.titleHeading)
        put("date", config.races.date)
    })
    put("products", JSONObject().apply {
        put("link", config.products.link)
        put("image", config.products.image)
        put("title", config.products.title)
        put("price", config.products.price)
    })
}

private fun decodeScrapeConfig(json: JSONObject): ScrapeConfig {
    val default = ScrapeConfig.default()

    fun JSONObject?.str(key: String, fallback: String): String =
        this?.optString(key, fallback) ?: fallback

    val home = json.optJSONObject("home")
    val article = json.optJSONObject("article")
    val news = json.optJSONObject("news")
    val training = json.optJSONObject("training")
    val races = json.optJSONObject("races")
    val products = json.optJSONObject("products")

    return ScrapeConfig(
        version = json.optInt("version", default.version),
        home = ScrapeConfig.Home(
            descriptionMeta = home.str("descriptionMeta", default.home.descriptionMeta)
        ),
        article = ScrapeConfig.Article(
            articleTag = article.str("articleTag", default.article.articleTag),
            bodyDivMarker = article.str("bodyDivMarker", default.article.bodyDivMarker)
        ),
        news = ScrapeConfig.News(
            articleLink = news.str("articleLink", default.news.articleLink),
            image = news.str("image", default.news.image),
            title = news.str("title", default.news.title),
            date = news.str("date", default.news.date)
        ),
        training = ScrapeConfig.Training(
            table = training.str("table", default.training.table),
            row = training.str("row", default.training.row),
            cell = training.str("cell", default.training.cell),
            firstParagraph = training.str("firstParagraph", default.training.firstParagraph),
            date = training.str("date", default.training.date),
            time = training.str("time", default.training.time),
            removeStrong = training.str("removeStrong", default.training.removeStrong),
            removeSpan = training.str("removeSpan", default.training.removeSpan),
            paragraph = training.str("paragraph", default.training.paragraph)
        ),
        races = ScrapeConfig.Races(
            listDivMarker = races.str("listDivMarker", default.races.listDivMarker),
            link = races.str("link", default.races.link),
            titleStrong = races.str("titleStrong", default.races.titleStrong),
            titleHeading = races.str("titleHeading", default.races.titleHeading),
            date = races.str("date", default.races.date)
        ),
        products = ScrapeConfig.Products(
            link = products.str("link", default.products.link),
            image = products.str("image", default.products.image),
            title = products.str("title", default.products.title),
            price = products.str("price", default.products.price)
        )
    )
}
