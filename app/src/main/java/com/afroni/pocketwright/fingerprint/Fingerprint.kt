package com.afroni.pocketwright.fingerprint

import android.os.Build
import com.afroni.pocketwright.session.ApiException
import com.afroni.pocketwright.session.int
import com.afroni.pocketwright.session.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Un vrai téléphone : ce qu'il annonce doit correspondre à un appareil qui existe. */
data class DeviceProfile(
    val vendor: String,
    val name: String,
    val model: String,
    val chipset: String,
    val androidVersions: List<Int>,
    val ramGb: List<Int>,
    val screenHeight: Int = 800,
)

/**
 * Une page peut mesurer le GPU qui l'exécute. Le catalogue ne contient donc que des téléphones au même GPU que
 * celui-ci (PowerVR GE8320) : ce qu'une session annonce reste vrai pour le matériel qui calcule ses pages.
 * Modèles : liste officielle des appareils de Google Play ; caractéristiques : fiches des constructeurs et GSMArena.
 */
object DeviceCatalog {
    const val GPU = "PowerVR Rogue GE8320"

    private val PHONES = listOf(
        DeviceProfile("Xiaomi", "Redmi 9A", "M2006C3LG", "Helio G25", listOf(10, 11), listOf(2, 3, 4)),
        DeviceProfile("Xiaomi", "Redmi 9C", "M2006C3MG", "Helio G35", listOf(10), listOf(2, 3, 4)),
        DeviceProfile("Xiaomi", "Redmi 10A", "220233L2G", "Helio G25", listOf(11), listOf(2, 3, 4)),
        DeviceProfile("Samsung", "Galaxy A03s", "SM-A037F", "Helio P35", listOf(11, 12), listOf(3, 4)),
        DeviceProfile("Samsung", "Galaxy A04", "SM-A045F", "Helio P35", listOf(12), listOf(3, 4)),
        DeviceProfile("Samsung", "Galaxy A04e", "SM-A042F", "Helio P35", listOf(12), listOf(3, 4)),
        DeviceProfile("Samsung", "Galaxy A12", "SM-A125F", "Helio P35", listOf(10, 11, 12), listOf(3, 4)),
        DeviceProfile("OPPO", "A16", "CPH2269", "Helio G35", listOf(11), listOf(3, 4)),
        DeviceProfile("realme", "C21", "RMX3201", "Helio G35", listOf(10), listOf(3, 4)),
        DeviceProfile("TECNO", "Spark 20C", "BG7n", "Helio G36", listOf(13), listOf(4), screenHeight = 806),
    )

    /** Ces puces partagent le GE8320. Sur un autre téléphone, seul l'appareil réel reste crédible. */
    val sharesGpu: Boolean by lazy {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        listOf(Build.HARDWARE, Build.BOARD, soc).any { Regex("mt676[25]", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    val devices: List<DeviceProfile>
        get() = if (sharesGpu) PHONES else listOf(
            DeviceProfile(Build.MANUFACTURER, Build.MODEL, Build.MODEL, Build.HARDWARE, listOf(Build.VERSION.RELEASE.substringBefore('.').toIntOrNull() ?: 13), listOf(4)),
        )

    fun find(model: String): DeviceProfile =
        devices.firstOrNull { it.model.equals(model, true) || it.name.equals(model, true) }
            ?: throw ApiException(404, "Appareil inconnu : $model. Liste : GET /fingerprints/devices")

    fun toJson(): JsonArray = JsonArray(
        devices.map {
            buildJsonObject {
                put("vendor", it.vendor)
                put("name", it.name)
                put("model", it.model)
                put("chipset", it.chipset)
                put("gpu", GPU)
                put("android", JsonArray(it.androidVersions.map(::JsonPrimitive)))
                put("ramGb", JsonArray(it.ramGb.map(::JsonPrimitive)))
            }
        },
    )
}

/**
 * L'empreinte d'une session. Tout en découle de façon cohérente et reproductible : le même [seed] redonne les
 * mêmes valeurs, stables d'une lecture à l'autre, comme sur un vrai téléphone.
 *
 * [identity] à faux : l'appareil réel reste annoncé, seul le bruit canvas et audio est propre à la session.
 */
data class Fingerprint(
    val seed: Long,
    val identity: Boolean,
    val vendor: String = "",
    val device: String = "",
    val model: String = "",
    val chipset: String = "",
    val android: Int = 0,
    val memory: Int = 0,
    val cores: Int = CORES,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val pixelRatio: Double = PIXEL_RATIO,
    /** Ce que barre d'état, barre d'adresse et barre de navigation prennent à la hauteur de l'écran. */
    val browserChrome: Int = 0,
    val languages: List<String> = emptyList(),
    val timezone: String? = null,
) {
    val viewportHeight: Int get() = screenHeight - browserChrome
    val locale: String get() = languages.firstOrNull().orEmpty()
    val platformVersion: String get() = "$android.0.0"

    /** Chrome pour Android fige la version d'Android et le modèle ; le vrai modèle passe par les Client Hints. */
    fun userAgent(major: Int): String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"

    /** Une combinaison déjà prise par une autre session est retirée : voir [FingerprintFactory]. */
    val signature: String get() = listOf(model, android, memory, languages.joinToString()).joinToString("|")

    fun toJson(major: Int): JsonObject = buildJsonObject {
        put("seed", java.lang.Long.toUnsignedString(seed, 16))
        put("identity", identity)
        if (!identity) return@buildJsonObject
        put("device", buildJsonObject {
            put("vendor", vendor)
            put("name", device)
            put("model", model)
            put("chipset", chipset)
            put("gpu", DeviceCatalog.GPU)
        })
        put("android", android)
        put("memory", memory)
        put("cores", cores)
        put("screen", buildJsonObject {
            put("width", screenWidth)
            put("height", screenHeight)
            put("pixelRatio", pixelRatio)
            put("chrome", browserChrome)
        })
        put("languages", JsonArray(languages.map(::JsonPrimitive)))
        put("timezone", timezone ?: TimeZone.getDefault().id)
        put("timezoneOverride", timezone != null)
        put("userAgent", userAgent(major))
    }

    fun toRecord(): JsonObject = buildJsonObject {
        put("seed", seed.toString())
        put("identity", identity)
        put("vendor", vendor)
        put("device", device)
        put("model", model)
        put("chipset", chipset)
        put("android", android)
        put("memory", memory)
        put("cores", cores)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("pixelRatio", pixelRatio)
        put("browserChrome", browserChrome)
        put("languages", JsonArray(languages.map(::JsonPrimitive)))
        timezone?.let { put("timezone", it) }
    }

    companion object {
        const val CORES = 8
        const val PIXEL_RATIO = 2.0
        private const val SCREEN_WIDTH = 360

        fun fromRecord(o: JsonObject?): Fingerprint? {
            o ?: return null
            val seed = o.string("seed")?.toLongOrNull() ?: return null
            return Fingerprint(
                seed = seed,
                identity = o.string("identity") == "true",
                vendor = o.string("vendor").orEmpty(),
                device = o.string("device").orEmpty(),
                model = o.string("model").orEmpty(),
                chipset = o.string("chipset").orEmpty(),
                android = o.int("android", 0),
                memory = o.int("memory", 0),
                cores = o.int("cores", CORES),
                screenWidth = o.int("screenWidth", 0),
                screenHeight = o.int("screenHeight", 0),
                pixelRatio = o.string("pixelRatio")?.toDoubleOrNull() ?: PIXEL_RATIO,
                browserChrome = o.int("browserChrome", 0),
                languages = (o["languages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                timezone = o.string("timezone"),
            )
        }

        fun noiseOnly(seed: Long = Random.nextLong()) = Fingerprint(seed = seed, identity = false)

        internal const val WIDTH = SCREEN_WIDTH
    }
}

/** Ce qu'un client demande : tout est facultatif, le reste est tiré au hasard. */
data class FingerprintRequest(
    val disabled: Boolean = false,
    val explicit: Boolean = false,
    val seed: Long? = null,
    val device: String? = null,
    val android: Int? = null,
    val memory: Int? = null,
    val locale: String? = null,
    val languages: List<String>? = null,
    val timezone: String? = null,
) {
    companion object {
        val AUTO = FingerprintRequest()

        /** `false` pour l'appareil réel, un objet pour imposer des valeurs, rien pour une empreinte tirée au hasard. */
        fun from(element: JsonElement?): FingerprintRequest = when {
            element == null -> AUTO
            element is JsonPrimitive && element.booleanOrNull == false -> FingerprintRequest(disabled = true)
            element is JsonObject -> parse(element)
            element is JsonPrimitive -> FingerprintRequest(explicit = true)
            else -> throw ApiException(400, "fingerprint : un objet, true ou false")
        }

        fun parse(o: JsonObject) = FingerprintRequest(
            explicit = true,
            seed = o.string("seed")?.let {
                it.toLongOrNull() ?: runCatching { java.lang.Long.parseUnsignedLong(it, 16) }.getOrNull()
                    ?: throw ApiException(400, "seed invalide")
            },
            device = o.string("device"),
            android = o.string("android")?.toIntOrNull(),
            memory = o.string("memory")?.toIntOrNull(),
            locale = o.string("locale"),
            languages = (o["languages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() },
            timezone = o.string("timezone")?.also {
                if (it !in TimeZone.getAvailableIDs()) throw ApiException(400, "Fuseau horaire inconnu : $it")
            },
        )
    }
}

object FingerprintFactory {
    private const val ATTEMPTS = 24
    private val BROWSER_CHROME = listOf(126, 84)

    /** [taken] : les combinaisons des autres sessions, à éviter pour que chaque session soit bien différente. */
    fun create(request: FingerprintRequest, taken: Set<String> = emptySet()): Fingerprint {
        val base = request.seed ?: Random.nextLong()
        var candidate = generate(base, request)
        // Avec une graine imposée, le client veut exactement cette empreinte.
        if (request.seed == null) {
            var attempt = 1
            while (candidate.signature in taken && attempt < ATTEMPTS) {
                candidate = generate(base + attempt, request)
                attempt++
            }
        }
        return candidate
    }

    private fun generate(seed: Long, request: FingerprintRequest): Fingerprint {
        val random = Random(seed)
        val device = request.device?.let(DeviceCatalog::find) ?: DeviceCatalog.devices.random(random)
        val android = request.android
            ?.also { if (it !in device.androidVersions) throw ApiException(400, "${device.name} n'existe pas sous Android $it : ${device.androidVersions}") }
            ?: device.androidVersions.random(random)
        val memory = request.memory ?: deviceMemory(device.ramGb.random(random))
        if (request.memory != null && device.ramGb.none { deviceMemory(it) == request.memory }) {
            throw ApiException(400, "${device.name} n'annonce pas $memory Go : ${device.ramGb.map(::deviceMemory).distinct()}")
        }
        return Fingerprint(
            seed = seed,
            identity = true,
            vendor = device.vendor,
            device = device.name,
            model = device.model,
            chipset = device.chipset,
            android = android,
            memory = memory,
            screenWidth = Fingerprint.WIDTH,
            screenHeight = device.screenHeight,
            browserChrome = BROWSER_CHROME.random(random),
            languages = request.languages ?: languages(request.locale?.let(Locale::forLanguageTag) ?: Locale.getDefault(), random),
            timezone = request.timezone,
        )
    }

    /** Comme Chromium : la mémoire vive arrondie à la puissance de deux la plus proche. */
    private fun deviceMemory(ramGb: Int): Int = when {
        ramGb <= 3 -> 2
        ramGb <= 6 -> 4
        else -> 8
    }

    private fun languages(locale: Locale, random: Random): List<String> {
        val tag = locale.toLanguageTag()
        val language = locale.language
        if (language == "en") return listOf(listOf(tag, "en"), listOf(tag, "en", "fr-FR", "fr")).random(random).distinct()
        return listOf(
            listOf(tag, language, "en-US", "en"),
            listOf(tag, language, "en-GB", "en"),
            listOf(tag, language),
        ).random(random).distinct()
    }

    /** L'algorithme de Chromium : les valeurs et l'ordre « graissés » dépendent de la version majeure. */
    fun brands(major: Int, version: String): List<Pair<String, String>> {
        val characters = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val grease = "Not" + characters[major % characters.size] + "A" + characters[(major + 1) % characters.size] + "Brand"
        val greaseVersion = listOf("8", "99", "24")[major % 3]
        val order = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))[major % 6]
        val out = arrayOfNulls<Pair<String, String>>(3)
        out[order[0]] = grease to greaseVersion
        out[order[1]] = "Chromium" to version
        out[order[2]] = "Google Chrome" to version
        return out.filterNotNull()
    }
}
