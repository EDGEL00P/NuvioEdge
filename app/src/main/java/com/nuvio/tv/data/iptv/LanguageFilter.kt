package com.nuvio.tv.data.iptv

import java.util.Locale

/**
 * Hides channels that aren't in the languages picked for a source.
 *
 * IPTV lists rarely state a language, so this reads the tags providers put in group and channel
 * names: a country or language code before a separator ("UK | Sports", "AR: MBC 1", "|FR| TF1"),
 * a code in brackets ("BBC One [UK]"), a country or language word in the group ("ARABIC NEWS",
 * "Deutschland"), or an M3U `tvg-language` / `tvg-country` attribute. The group's tags win over the
 * channel's. A channel with no recognisable tag is kept, so nothing disappears on a guess.
 */
class LanguageFilter(languages: Collection<String>) {

    private val wanted: Set<String> = languages.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }

    val isActive: Boolean get() = wanted.isNotEmpty()

    fun keep(name: String, group: String, tvgLanguage: String? = null, tvgCountry: String? = null): Boolean {
        if (wanted.isEmpty()) return true
        val found = languagesOf(name, group, tvgLanguage, tvgCountry)
        return found.isEmpty() || found.any { it in wanted }
    }

    companion object {
        val NONE = LanguageFilter(emptyList())

        /** Languages offered in the picker: id, English name. */
        val LANGUAGES: List<Pair<String, String>> = listOf(
            "en" to "English", "ar" to "Arabic", "fr" to "French", "de" to "German", "es" to "Spanish",
            "pt" to "Portuguese", "it" to "Italian", "nl" to "Dutch", "tr" to "Turkish", "pl" to "Polish",
            "ru" to "Russian", "uk" to "Ukrainian", "hi" to "Hindi", "ur" to "Urdu", "bn" to "Bengali",
            "pa" to "Punjabi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "zh" to "Chinese",
            "ja" to "Japanese", "ko" to "Korean", "vi" to "Vietnamese", "th" to "Thai", "fil" to "Filipino",
            "ms" to "Malay / Indonesian", "fa" to "Persian", "el" to "Greek", "ro" to "Romanian",
            "hu" to "Hungarian", "cs" to "Czech / Slovak", "sv" to "Swedish", "no" to "Norwegian",
            "da" to "Danish", "fi" to "Finnish", "sq" to "Albanian", "sh" to "Balkan (Serbian, Croatian, Bosnian)",
            "bg" to "Bulgarian", "he" to "Hebrew", "ku" to "Kurdish", "hy" to "Armenian", "so" to "Somali"
        )

        /** Short codes used as tags: country codes map to the country's main broadcast language. */
        private val CODES: Map<String, Set<String>> = buildMap {
            fun tag(language: String, vararg codes: String) = codes.forEach { code ->
                put(code, (get(code) ?: emptySet()) + language)
            }
            tag("en", "EN", "ENG", "UK", "GB", "US", "USA", "CA", "CAN", "AU", "AUS", "NZ", "IE", "IRL")
            tag("ar", "AR", "ARA", "ARAB", "SA", "KSA", "AE", "UAE", "EG", "MA", "DZ", "TN", "IQ", "JO", "LB",
                "KW", "QA", "BH", "OM", "SY", "LY", "PS", "YE", "SD")
            tag("fr", "FR", "FRA", "FRE", "BE")
            tag("de", "DE", "GER", "DEU", "AT", "CH")
            tag("es", "ES", "ESP", "SPA", "MX", "MEX", "CO", "CL", "PE", "VE", "EC", "UY", "PY", "BO", "CR",
                "PA", "DO", "GT", "HN", "SV", "NI", "PR", "LAT", "LATAM", "LATINO")
            tag("pt", "PT", "POR", "BR", "BRA")
            tag("it", "IT", "ITA")
            tag("nl", "NL", "NED", "DUT", "BE")
            tag("tr", "TR", "TUR")
            tag("pl", "PL", "POL")
            tag("ru", "RU", "RUS")
            tag("uk", "UA", "UKR")
            tag("hi", "IN", "IND", "HIN")
            tag("ur", "PK", "PAK", "URD")
            tag("bn", "BD", "BAN", "BEN")
            tag("pa", "PUN")
            tag("ta", "TAM")
            tag("te", "TEL")
            tag("ml", "MAL")
            tag("zh", "CN", "CHN", "HK", "TW", "ZH")
            tag("ja", "JP", "JPN")
            tag("ko", "KR", "KOR")
            tag("vi", "VN", "VIE")
            tag("th", "TH", "THA")
            tag("fil", "PH", "PHI")
            tag("ms", "ID", "IDN", "MY", "MYS")
            tag("fa", "IR", "IRN", "FA", "PER", "AF", "AFG")
            tag("el", "GR", "GRE", "CY")
            tag("ro", "RO", "ROM", "MD")
            tag("hu", "HU", "HUN")
            tag("cs", "CZ", "CZE", "SK", "SVK")
            tag("sv", "SE", "SWE")
            tag("no", "NO", "NOR")
            tag("da", "DK", "DEN", "DAN")
            tag("fi", "FI", "FIN")
            tag("sq", "AL", "ALB", "KS", "KOS")
            tag("sh", "RS", "SRB", "HR", "CRO", "BA", "BIH", "EXYU", "YU")
            tag("bg", "BG", "BUL")
            tag("he", "IL", "ISR", "HEB")
            tag("ku", "KU", "KUR")
            tag("hy", "AM", "ARM")
            tag("so", "SO", "SOM")
        }

        /** Whole words that name a language or country, matched anywhere in a group name. */
        private val WORDS: Map<String, String> = buildMap {
            fun word(language: String, vararg words: String) = words.forEach { put(it, language) }
            word("en", "ENGLISH", "BRITISH", "BRITAIN", "AMERICA", "AMERICAN", "AUSTRALIA", "AUSTRALIAN",
                "CANADA", "CANADIAN", "IRELAND", "IRISH", "ZEALAND")
            word("ar", "ARABIC", "ARABIA", "ARAB", "ARABE", "EGYPT", "MAROC", "MOROCCO", "ALGERIA", "TUNISIA",
                "IRAQ", "LEBANON", "SYRIA", "KUWAIT", "QATAR", "EMIRATES", "JORDAN", "SAUDI")
            word("fr", "FRENCH", "FRANCE", "FRANCAIS", "FRANÇAIS", "QUEBEC")
            word("de", "GERMAN", "GERMANY", "DEUTSCH", "DEUTSCHLAND", "AUSTRIA", "OSTERREICH", "ÖSTERREICH", "SCHWEIZ")
            word("es", "SPANISH", "SPAIN", "ESPANA", "ESPAÑA", "ESPANOL", "ESPAÑOL", "MEXICO", "LATIN",
                "LATINOAMERICA", "ARGENTINA", "COLOMBIA", "CHILE", "PERU", "VENEZUELA", "ECUADOR")
            word("pt", "PORTUGUESE", "PORTUGAL", "PORTUGUES", "BRASIL", "BRAZIL")
            word("it", "ITALIAN", "ITALIA", "ITALY", "ITALIANO")
            word("nl", "DUTCH", "NETHERLANDS", "NEDERLAND", "HOLLAND", "FLEMISH", "VLAAMS")
            word("tr", "TURKISH", "TURKEY", "TURKIYE", "TÜRKIYE", "TURK", "TÜRK")
            word("pl", "POLISH", "POLAND", "POLSKA")
            word("ru", "RUSSIAN", "RUSSIA")
            word("uk", "UKRAINE", "UKRAINIAN")
            word("hi", "HINDI", "INDIA", "INDIAN", "BOLLYWOOD")
            word("ur", "URDU", "PAKISTAN", "PAKISTANI")
            word("bn", "BENGALI", "BANGLA", "BANGLADESH")
            word("pa", "PUNJABI")
            word("ta", "TAMIL")
            word("te", "TELUGU")
            word("ml", "MALAYALAM")
            word("zh", "CHINESE", "CHINA", "MANDARIN", "CANTONESE", "TAIWAN")
            word("ja", "JAPAN", "JAPANESE")
            word("ko", "KOREA", "KOREAN")
            word("vi", "VIETNAM", "VIETNAMESE")
            word("th", "THAI", "THAILAND")
            word("fil", "FILIPINO", "PHILIPPINES", "PINOY", "TAGALOG")
            word("ms", "INDONESIA", "INDONESIAN", "MALAYSIA", "MALAY")
            word("fa", "PERSIAN", "IRAN", "FARSI", "AFGHAN", "AFGHANISTAN")
            word("el", "GREEK", "GREECE")
            word("ro", "ROMANIA", "ROMANIAN", "MOLDOVA")
            word("hu", "HUNGARY", "HUNGARIAN", "MAGYAR")
            word("cs", "CZECH", "SLOVAK", "SLOVAKIA")
            word("sv", "SWEDEN", "SWEDISH", "SVERIGE")
            word("no", "NORWAY", "NORWEGIAN", "NORGE")
            word("da", "DENMARK", "DANISH", "DANSK")
            word("fi", "FINLAND", "FINNISH", "SUOMI")
            word("sq", "ALBANIA", "ALBANIAN", "SHQIP", "KOSOVO")
            word("sh", "SERBIA", "CROATIA", "BOSNIA", "BALKAN", "MONTENEGRO")
            word("bg", "BULGARIA", "BULGARIAN")
            word("he", "ISRAEL", "HEBREW")
            word("ku", "KURDISH", "KURDISTAN")
            word("hy", "ARMENIA", "ARMENIAN")
            word("so", "SOMALI", "SOMALIA")
        }

        /** A channel name's tag: an upper-case code before a separator ("UK | x", "AR: x", "|FR| x", "DE - x"). */
        private val NAME_PREFIX = Regex("""^[\s|▎┃│\[(]*([A-Z]{2,5})\s*(?:[|:▎┃│\])]|\s[-–]\s)""")
        /** Group names are split on separators and each part's leading upper-case code is read ("VIP | UK SPORTS"). */
        private val SEPARATORS = Regex("""[|:▎┃│]|\s[-–]\s""")
        private val LEADING_CODE = Regex("""^[\s\[(]*([A-Z]{2,5})(?![A-Za-z0-9])""")
        private val BRACKETED = Regex("""[\[(]([A-Z]{2,5})[\])]""")
        private val WORD = Regex("""\p{L}+""")

        /** The languages tagged on a channel; empty when nothing recognisable is there. */
        internal fun languagesOf(name: String, group: String, tvgLanguage: String?, tvgCountry: String?): Set<String> {
            groupTags(group).takeIf { it.isNotEmpty() }?.let { return it }
            nameTags(name).takeIf { it.isNotEmpty() }?.let { return it }
            return attributeLanguages(tvgLanguage, tvgCountry)
        }

        private fun groupTags(group: String): Set<String> {
            if (group.isBlank() || group == M3uParser.UNCATEGORIZED) return emptySet()
            val found = HashSet<String>()
            for (part in group.split(SEPARATORS)) {
                LEADING_CODE.find(part)?.let { CODES[it.groupValues[1]]?.let(found::addAll) }
            }
            BRACKETED.findAll(group).forEach { match -> CODES[match.groupValues[1]]?.let(found::addAll) }
            if (found.isNotEmpty()) return found
            WORD.findAll(group).forEach { match -> WORDS[match.value.uppercase(Locale.ROOT)]?.let(found::add) }
            return found
        }

        private fun nameTags(name: String): Set<String> {
            if (name.isBlank()) return emptySet()
            NAME_PREFIX.find(name)?.let { match -> CODES[match.groupValues[1]]?.let { return it } }
            val found = HashSet<String>()
            BRACKETED.findAll(name).forEach { match -> CODES[match.groupValues[1]]?.let(found::addAll) }
            return found
        }

        private fun attributeLanguages(tvgLanguage: String?, tvgCountry: String?): Set<String> {
            val found = HashSet<String>()
            tvgLanguage?.split(',', ';', '/')?.forEach { part ->
                val value = part.trim().uppercase(Locale.ROOT)
                if (value.isEmpty()) return@forEach
                WORDS[value]?.let(found::add)
                    ?: LANGUAGES.firstOrNull { it.first.equals(value, true) || it.second.equals(value, true) }
                        ?.let { found += it.first }
                    ?: CODES[value]?.let(found::addAll)
            }
            if (found.isEmpty()) {
                tvgCountry?.split(',', ';', '/')?.forEach { part ->
                    CODES[part.trim().uppercase(Locale.ROOT)]?.let(found::addAll)
                }
            }
            return found
        }
    }
}
