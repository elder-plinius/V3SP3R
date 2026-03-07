package com.vesper.flipper.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * FapHub - Flipper Application Hub + Public Resource Library
 *
 * Browse apps, remote libraries, evil portal scripts, BadUSB payloads,
 * Sub-GHz files, and more from curated public repositories.
 */

@Serializable
data class FapApp(
    val id: String,
    val name: String,
    val description: String,
    val category: FapCategory,
    val author: String,
    val version: String,
    val downloadUrl: String,
    val iconUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    val size: Long = 0,
    val downloads: Int = 0,
    val rating: Float = 0f,
    val updatedAt: Long = 0,
    val sdkVersion: String = "0.0",
    val targetFirmware: String = "any",
    val isInstalled: Boolean = false,
    val installedVersion: String? = null
)

@Serializable
enum class FapCategory(val displayName: String, val icon: String) {
    @SerialName("games") GAMES("Games", "🎮"),
    @SerialName("tools") TOOLS("Tools", "🔧"),
    @SerialName("nfc") NFC("NFC", "💳"),
    @SerialName("subghz") SUBGHZ("Sub-GHz", "📡"),
    @SerialName("infrared") INFRARED("Infrared", "🔴"),
    @SerialName("gpio") GPIO("GPIO", "⚡"),
    @SerialName("bluetooth") BLUETOOTH("Bluetooth", "🔵"),
    @SerialName("usb") USB("USB", "🔌"),
    @SerialName("media") MEDIA("Media", "🎵"),
    @SerialName("misc") MISC("Misc", "📦")
}

@Serializable
data class FapCatalog(
    val apps: List<FapApp>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val lastUpdated: Long
)

sealed class InstallStatus {
    object Idle : InstallStatus()
    data class Downloading(val progress: Float) : InstallStatus()
    object Installing : InstallStatus()
    object Success : InstallStatus()
    data class Error(val message: String) : InstallStatus()
}

// ═══════════════════════════════════════════════════════════
// PUBLIC RESOURCE LIBRARY
// ═══════════════════════════════════════════════════════════

/**
 * A public repository/collection of Flipper files
 */
data class FlipperResourceRepo(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val resourceType: FlipperResourceType,
    val repoUrl: String,
    val stars: Int = 0,
    val fileCount: Int = 0,
    val lastUpdated: String = "",
    val tags: List<String> = emptyList()
)

enum class FlipperResourceType(
    val displayName: String,
    val icon: String,
    val color: Long,
    val flipperDir: String
) {
    IR_REMOTE("IR Remotes", "🔴", 0xFFF44336, "/ext/infrared"),
    SUB_GHZ("Sub-GHz Files", "📡", 0xFFFF9800, "/ext/subghz"),
    BAD_USB("BadUSB Scripts", "💀", 0xFF9C27B0, "/ext/badusb"),
    NFC_FILES("NFC Files", "💳", 0xFF4CAF50, "/ext/nfc"),
    EVIL_PORTAL("Evil Portal", "🕸", 0xFFE91E63, "/ext/apps_data/evil_portal"),
    MUSIC("Music / RTTTL", "🎵", 0xFF2196F3, "/ext/music_player"),
    ANIMATIONS("Animations", "🐬", 0xFF00BCD4, "/ext/dolphin"),
    GPIO_TOOLS("GPIO / Hardware", "⚡", 0xFFFFEB3B, "/ext/gpio")
}

/**
 * A file entry within a public repository
 */
data class RepoFileEntry(
    val name: String,
    val path: String,
    val downloadUrl: String,
    val size: Long = 0,
    val type: String = "file"
)

/**
 * Curated list of popular Flipper resource repositories
 */
object FlipperResourceLibrary {

    val repositories = listOf(
        // IR Remote Libraries
        FlipperResourceRepo(
            id = "irdb",
            name = "Flipper-IRDB",
            description = "Massive IR remote database. TV, AC, projector, soundbar, fan, and more from thousands of brands.",
            author = "logickworkshop",
            resourceType = FlipperResourceType.IR_REMOTE,
            repoUrl = "https://github.com/logickworkshop/Flipper-IRDB",
            stars = 2200,
            fileCount = 5000,
            lastUpdated = "2025",
            tags = listOf("Samsung", "LG", "Sony", "Vizio", "TCL", "AC", "Projector")
        ),
        FlipperResourceRepo(
            id = "ir_universal",
            name = "Flipper Universal IR Remote",
            description = "Universal remote files organized by device type. Covers TVs, ACs, audio systems, fans, and LED strips.",
            author = "UberGuidoZ",
            resourceType = FlipperResourceType.IR_REMOTE,
            repoUrl = "https://github.com/UberGuidoZ/Flipper",
            stars = 13000,
            fileCount = 3000,
            lastUpdated = "2025",
            tags = listOf("Universal", "All Brands", "LED", "Fan", "Audio")
        ),

        // Sub-GHz Files
        FlipperResourceRepo(
            id = "subghz_bruteforce",
            name = "Sub-GHz Bruteforce Files",
            description = "Pre-generated bruteforce files for common Sub-GHz protocols. Garage doors, gates, and barrier openers.",
            author = "tobiabocchi",
            resourceType = FlipperResourceType.SUB_GHZ,
            repoUrl = "https://github.com/tobiabocchi/flipperzero-bruteforce",
            stars = 1800,
            fileCount = 200,
            lastUpdated = "2024",
            tags = listOf("Bruteforce", "Garage", "Gate", "CAME", "Nice", "Linear")
        ),
        FlipperResourceRepo(
            id = "subghz_collection",
            name = "Sub-GHz Signal Library",
            description = "Curated collection of Sub-GHz signals: Tesla charge port, fans, lights, and protocol examples.",
            author = "UberGuidoZ",
            resourceType = FlipperResourceType.SUB_GHZ,
            repoUrl = "https://github.com/UberGuidoZ/Flipper",
            stars = 13000,
            fileCount = 500,
            lastUpdated = "2025",
            tags = listOf("Tesla", "Protocol", "Weather", "TPMS", "Doorbells")
        ),

        // BadUSB Scripts
        FlipperResourceRepo(
            id = "badusb_scripts",
            name = "Flipper BadUSB Payloads",
            description = "DuckyScript payloads for recon, exfiltration, pranks, and system modification. Windows, Mac, Linux.",
            author = "I-Am-Jakoby",
            resourceType = FlipperResourceType.BAD_USB,
            repoUrl = "https://github.com/I-Am-Jakoby/Flipper-Zero-BadUSB",
            stars = 2500,
            fileCount = 80,
            lastUpdated = "2025",
            tags = listOf("Windows", "Mac", "Exfil", "Recon", "Prank")
        ),
        FlipperResourceRepo(
            id = "badusb_hak5",
            name = "Hak5-Inspired BadUSB",
            description = "BadUSB scripts inspired by Hak5 payloads. Credential harvesting, reverse shells, and system recon.",
            author = "UberGuidoZ",
            resourceType = FlipperResourceType.BAD_USB,
            repoUrl = "https://github.com/UberGuidoZ/Flipper",
            stars = 13000,
            fileCount = 120,
            lastUpdated = "2025",
            tags = listOf("Hak5", "ReverseShell", "Credentials", "WiFi")
        ),

        // Evil Portal
        FlipperResourceRepo(
            id = "evil_portal_html",
            name = "Evil Portal Templates",
            description = "Captive portal HTML templates. Google, Facebook, Twitter, WiFi login, and custom brand pages.",
            author = "bigbrodude6119",
            resourceType = FlipperResourceType.EVIL_PORTAL,
            repoUrl = "https://github.com/bigbrodude6119/flipper-zero-evil-portal",
            stars = 1200,
            fileCount = 30,
            lastUpdated = "2024",
            tags = listOf("Google", "Facebook", "WiFi", "Custom", "Social")
        ),
        FlipperResourceRepo(
            id = "evil_portal_custom",
            name = "Custom Portal Pages",
            description = "Community-contributed evil portal templates for various login portals and services.",
            author = "RogueMaster",
            resourceType = FlipperResourceType.EVIL_PORTAL,
            repoUrl = "https://github.com/RogueMaster/flipperzero-firmware-wPlugins",
            stars = 3500,
            fileCount = 25,
            lastUpdated = "2025",
            tags = listOf("Netflix", "Amazon", "Hotel", "Airport", "Coffee")
        ),

        // NFC Files
        FlipperResourceRepo(
            id = "nfc_amiibo",
            name = "Amiibo NFC Collection",
            description = "Complete Amiibo NFC data files for Nintendo Switch and 3DS compatible figures.",
            author = "Gioman101",
            resourceType = FlipperResourceType.NFC_FILES,
            repoUrl = "https://github.com/Gioman101/FlipperAmiworker",
            stars = 500,
            fileCount = 600,
            lastUpdated = "2024",
            tags = listOf("Amiibo", "Nintendo", "Switch", "3DS")
        ),

        // Music / RTTTL
        FlipperResourceRepo(
            id = "music_rtttl",
            name = "RTTTL Music Collection",
            description = "Ringtone melodies in RTTTL format. Game themes, movie scores, classic tunes, and memes.",
            author = "UberGuidoZ",
            resourceType = FlipperResourceType.MUSIC,
            repoUrl = "https://github.com/UberGuidoZ/Flipper",
            stars = 13000,
            fileCount = 200,
            lastUpdated = "2025",
            tags = listOf("Mario", "Zelda", "Rickroll", "Star Wars", "Nokia")
        ),

        // Animations
        FlipperResourceRepo(
            id = "dolphin_animations",
            name = "Custom Dolphin Animations",
            description = "Custom Flipper dolphin animations. Anime, movies, games, memes, and custom artwork.",
            author = "Talking-Sasquach",
            resourceType = FlipperResourceType.ANIMATIONS,
            repoUrl = "https://github.com/Talking-Sasquach/Flipper-Zero-Animations",
            stars = 300,
            fileCount = 100,
            lastUpdated = "2024",
            tags = listOf("Anime", "Games", "Memes", "Custom", "Dolphin")
        ),

        // GPIO / Hardware
        FlipperResourceRepo(
            id = "gpio_marauder",
            name = "WiFi Marauder Companion",
            description = "ESP32 Marauder flasher and companion files. WiFi scanning, deauth, beacon spam, and PMKID capture.",
            author = "justcallmekoko",
            resourceType = FlipperResourceType.GPIO_TOOLS,
            repoUrl = "https://github.com/justcallmekoko/ESP32Marauder",
            stars = 5000,
            fileCount = 50,
            lastUpdated = "2025",
            tags = listOf("ESP32", "WiFi", "Deauth", "PMKID", "Wardriving")
        )
    )

    fun getByType(type: FlipperResourceType): List<FlipperResourceRepo> {
        return repositories.filter { it.resourceType == type }
    }

    fun search(query: String): List<FlipperResourceRepo> {
        val lq = query.lowercase()
        return repositories.filter {
            it.name.lowercase().contains(lq) ||
            it.description.lowercase().contains(lq) ||
            it.tags.any { t -> t.lowercase().contains(lq) } ||
            it.author.lowercase().contains(lq)
        }
    }
}

/**
 * FapHub catalog (existing curated app list)
 */
object FapHubCatalog {

    val featuredApps = listOf(
        FapApp(id = "picopass", name = "PicoPass Reader", description = "Read and emulate iClass/PicoPass cards", category = FapCategory.NFC, author = "Flipper Team", version = "1.5", downloadUrl = "https://lab.flipper.net/apps/picopass", downloads = 50000, rating = 4.8f),
        FapApp(id = "wifi_marauder", name = "WiFi Marauder", description = "ESP32 Marauder companion app for WiFi attacks", category = FapCategory.BLUETOOTH, author = "0xchocolate", version = "0.6.3", downloadUrl = "https://lab.flipper.net/apps/wifi_marauder", downloads = 120000, rating = 4.9f),
        FapApp(id = "ble_spam", name = "BLE Spam", description = "Bluetooth Low Energy advertisement spam", category = FapCategory.BLUETOOTH, author = "Willy-JL", version = "2.1", downloadUrl = "https://lab.flipper.net/apps/ble_spam", downloads = 85000, rating = 4.7f),
        FapApp(id = "mousejacker", name = "MouseJacker", description = "Exploit wireless mice and keyboards", category = FapCategory.SUBGHZ, author = "Flipper Team", version = "1.1", downloadUrl = "https://lab.flipper.net/apps/mousejacker", downloads = 45000, rating = 4.6f),
        FapApp(id = "sentry_safe", name = "Sentry Safe Cracker", description = "Brute force Sentry Safe electronic locks", category = FapCategory.GPIO, author = "H4ckd4ddy", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/sentry_safe", downloads = 30000, rating = 4.5f),
        FapApp(id = "evil_portal", name = "Evil Portal", description = "Captive portal for credential capture", category = FapCategory.BLUETOOTH, author = "bigbrodude6119", version = "0.3", downloadUrl = "https://lab.flipper.net/apps/evil_portal", downloads = 75000, rating = 4.7f),
        FapApp(id = "flappy_bird", name = "Flappy Bird", description = "Classic Flappy Bird game", category = FapCategory.GAMES, author = "xMasterX", version = "1.2", downloadUrl = "https://lab.flipper.net/apps/flappy_bird", downloads = 100000, rating = 4.8f),
        FapApp(id = "doom", name = "DOOM", description = "The classic DOOM game ported to Flipper", category = FapCategory.GAMES, author = "xMasterX", version = "0.9", downloadUrl = "https://lab.flipper.net/apps/doom", downloads = 95000, rating = 4.9f),
        FapApp(id = "tetris", name = "Tetris", description = "Classic Tetris game", category = FapCategory.GAMES, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/tetris", downloads = 80000, rating = 4.7f),
        FapApp(id = "snake", name = "Snake", description = "Classic Snake game", category = FapCategory.GAMES, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/snake", downloads = 70000, rating = 4.6f),
        FapApp(id = "nfc_magic", name = "NFC Magic", description = "Write to magic/gen1a NFC cards", category = FapCategory.NFC, author = "Flipper Team", version = "1.3", downloadUrl = "https://lab.flipper.net/apps/nfc_magic", downloads = 65000, rating = 4.8f),
        FapApp(id = "seader", name = "Seader", description = "Read HID iClass SE cards with SAM", category = FapCategory.NFC, author = "bettse", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/seader", downloads = 25000, rating = 4.5f),
        FapApp(id = "barcode_gen", name = "Barcode Generator", description = "Generate and display barcodes", category = FapCategory.TOOLS, author = "Flipper Team", version = "1.1", downloadUrl = "https://lab.flipper.net/apps/barcode_gen", downloads = 40000, rating = 4.4f),
        FapApp(id = "qrcode", name = "QR Code", description = "Generate and display QR codes", category = FapCategory.TOOLS, author = "Willy-JL", version = "1.5", downloadUrl = "https://lab.flipper.net/apps/qrcode", downloads = 55000, rating = 4.6f),
        FapApp(id = "pocsag_pager", name = "POCSAG Pager", description = "Send POCSAG pager messages", category = FapCategory.SUBGHZ, author = "xMasterX", version = "0.8", downloadUrl = "https://lab.flipper.net/apps/pocsag_pager", downloads = 20000, rating = 4.3f),
        FapApp(id = "subghz_bruteforcer", name = "Sub-GHz Bruteforcer", description = "Brute force Sub-GHz protocols", category = FapCategory.SUBGHZ, author = "xMasterX", version = "1.3", downloadUrl = "https://lab.flipper.net/apps/subghz_bruteforcer", downloads = 60000, rating = 4.7f),
        FapApp(id = "gps", name = "GPS", description = "GPS NMEA parser with serial module", category = FapCategory.GPIO, author = "Flipper Team", version = "1.2", downloadUrl = "https://lab.flipper.net/apps/gps", downloads = 35000, rating = 4.5f),
        FapApp(id = "signal_generator", name = "Signal Generator", description = "Generate test signals on GPIO", category = FapCategory.GPIO, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/signal_generator", downloads = 15000, rating = 4.2f),
        FapApp(id = "music_player", name = "Music Player", description = "Play RTTTL music files", category = FapCategory.MEDIA, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/music_player", downloads = 50000, rating = 4.4f),
        FapApp(id = "video_player", name = "Video Player", description = "Play video files on the display", category = FapCategory.MEDIA, author = "LTVA1", version = "0.5", downloadUrl = "https://lab.flipper.net/apps/video_player", downloads = 30000, rating = 4.3f),
        FapApp(id = "ir_remote", name = "Universal IR Remote", description = "Comprehensive IR remote database", category = FapCategory.INFRARED, author = "Flipper Team", version = "2.0", downloadUrl = "https://lab.flipper.net/apps/ir_remote", downloads = 90000, rating = 4.8f),
        FapApp(id = "ir_scope", name = "IR Scope", description = "Visualize and decode IR signals", category = FapCategory.INFRARED, author = "xMasterX", version = "1.1", downloadUrl = "https://lab.flipper.net/apps/ir_scope", downloads = 25000, rating = 4.4f),
        FapApp(id = "usb_hid", name = "USB HID", description = "Advanced USB HID controller", category = FapCategory.USB, author = "Flipper Team", version = "1.5", downloadUrl = "https://lab.flipper.net/apps/usb_hid", downloads = 40000, rating = 4.5f),
        FapApp(id = "mass_storage", name = "Mass Storage", description = "Expose SD card as USB mass storage", category = FapCategory.USB, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/mass_storage", downloads = 55000, rating = 4.6f),
        FapApp(id = "spectrum_analyzer", name = "Spectrum Analyzer", description = "Real-time RF spectrum visualization", category = FapCategory.SUBGHZ, author = "jolcese", version = "1.2", downloadUrl = "https://lab.flipper.net/apps/spectrum_analyzer", downloads = 45000, rating = 4.7f),
        FapApp(id = "weather_station", name = "Weather Station", description = "Decode weather station sensors", category = FapCategory.SUBGHZ, author = "Flipper Team", version = "1.1", downloadUrl = "https://lab.flipper.net/apps/weather_station", downloads = 35000, rating = 4.4f),
        FapApp(id = "airtag_scanner", name = "AirTag Scanner", description = "Detect nearby Apple AirTags", category = FapCategory.BLUETOOTH, author = "Willy-JL", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/airtag_scanner", downloads = 60000, rating = 4.6f),
        FapApp(id = "bt_serial", name = "Bluetooth Serial", description = "Bluetooth serial terminal", category = FapCategory.BLUETOOTH, author = "Flipper Team", version = "1.2", downloadUrl = "https://lab.flipper.net/apps/bt_serial", downloads = 30000, rating = 4.3f),
        FapApp(id = "clock", name = "Clock", description = "Analog and digital clock faces", category = FapCategory.MISC, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/clock", downloads = 25000, rating = 4.2f),
        FapApp(id = "dice", name = "Dice Roller", description = "Roll various dice types", category = FapCategory.MISC, author = "Flipper Team", version = "1.0", downloadUrl = "https://lab.flipper.net/apps/dice", downloads = 20000, rating = 4.1f)
    )

    val allApps: List<FapApp> get() = featuredApps.sortedByDescending { it.downloads }

    fun getAppsByCategory(category: FapCategory): List<FapApp> = featuredApps.filter { it.category == category }

    fun searchApps(query: String): List<FapApp> {
        val lq = query.lowercase()
        return featuredApps.filter { it.name.lowercase().contains(lq) || it.description.lowercase().contains(lq) || it.author.lowercase().contains(lq) }
    }
}
