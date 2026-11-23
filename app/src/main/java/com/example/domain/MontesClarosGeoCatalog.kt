package com.example.domain

import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.NeighborhoodStatus
import java.text.Normalizer

enum class NeighborhoodConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNIDENTIFIED
}

enum class FieldConfidence(val labelPt: String) {
    HIGH("Alta"),
    MEDIUM("Média"),
    LOW("Baixa"),
    UNIDENTIFIED("Não identificado");

    companion object {
        fun fromStored(value: String?): FieldConfidence {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNIDENTIFIED
        }

        fun fromNeighborhood(confidence: NeighborhoodConfidence): FieldConfidence = when (confidence) {
            NeighborhoodConfidence.HIGH -> HIGH
            NeighborhoodConfidence.MEDIUM -> MEDIUM
            NeighborhoodConfidence.LOW -> LOW
            NeighborhoodConfidence.UNIDENTIFIED -> UNIDENTIFIED
        }
    }
}

data class GeocoderPlaceMetadata(
    val subLocality: String? = null,
    val thoroughfare: String? = null,
    val featureName: String? = null,
    val addressLine: String? = null,
    val locality: String? = null
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String = "",
    val geocoderMetadata: GeocoderPlaceMetadata? = null
)

data class KnownNeighborhood(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val defaultStatus: NeighborhoodStatus,
    val defaultNote: String = ""
)

data class NeighborhoodMatchResult(
    val name: String,
    val confidence: NeighborhoodConfidence,
    val rule: NeighborhoodRuleEntity?,
    val sourceDetail: String
)

object MontesClarosGeoCatalog {

    const val UNIDENTIFIED_NEIGHBORHOOD = "Não identificado"
    private const val LEGACY_UNIDENTIFIED_NEIGHBORHOOD = "Bairro não identificado"

    val DOWNTOWN_MONTES_CLAROS = GeoPoint(
        latitude = -16.7282,
        longitude = -43.8617,
        label = "Centro de Montes Claros - MG"
    )

    // Bounding box covering Montes Claros urban area and districts
    const val MIN_LAT = -16.86
    const val MAX_LAT = -16.60
    const val MIN_LON = -44.00
    const val MAX_LON = -43.75

    fun isWithinMontesClarosBounds(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON

    fun isUnidentified(label: String): Boolean =
        label.isBlank() ||
            label.equals(UNIDENTIFIED_NEIGHBORHOOD, ignoreCase = true) ||
            label.equals(LEGACY_UNIDENTIFIED_NEIGHBORHOOD, ignoreCase = true)

    val knownNeighborhoods: List<KnownNeighborhood> = listOf(
        KnownNeighborhood("Centro", -16.7282, -43.8617, NeighborhoodStatus.PREFERRED, "Polo central de restaurantes e retorno rápido"),
        KnownNeighborhood("Todos os Santos", -16.7338, -43.8669, NeighborhoodStatus.PREFERRED, "Área central nobre, alta liquidez"),
        KnownNeighborhood("Melo", -16.7245, -43.8705, NeighborhoodStatus.PREFERRED, "Próximo ao Centro e Av. Dulce Sarmento"),
        KnownNeighborhood("Ibituruna", -16.7385, -43.8832, NeighborhoodStatus.PREFERRED, "Boa demanda, vias largas"),
        KnownNeighborhood("São José", -16.7210, -43.8668, NeighborhoodStatus.PREFERRED, "Acesso rápido ao Centro"),
        KnownNeighborhood("Cândida Câmara", -16.7346, -43.8732, NeighborhoodStatus.PREFERRED, "Região residencial segura"),
        KnownNeighborhood("Morada do Sol", -16.7432, -43.8775, NeighborhoodStatus.PREFERRED, "Próximo à área comercial sul"),
        KnownNeighborhood("Major Prates", -16.7458, -43.8672, NeighborhoodStatus.PREFERRED, "Grande polo gastronômico e fácil retorno"),
        KnownNeighborhood("Augusta Mota", -16.7310, -43.8768, NeighborhoodStatus.PREFERRED, "Próximo ao Montes Claros Shopping"),
        KnownNeighborhood("Jardim São Luiz", -16.7382, -43.8634, NeighborhoodStatus.PREFERRED, "Fácil acesso pela Av. Mestra Fininha"),
        KnownNeighborhood("Morada do Parque", -16.7485, -43.8738, NeighborhoodStatus.PREFERRED, "Próximo ao Parque Municipal"),
        KnownNeighborhood("Vila Guilhermina", -16.7322, -43.8626, NeighborhoodStatus.PREFERRED, "Região central tranquila"),
        KnownNeighborhood("Funcionários", -16.7315, -43.8588, NeighborhoodStatus.PREFERRED, "Vizinho ao Centro"),
        KnownNeighborhood("São Geraldo", -16.7230, -43.8608, NeighborhoodStatus.PREFERRED, "Acesso rápido ao polo central"),
        KnownNeighborhood("Jardim Brasil", -16.7164, -43.8674, NeighborhoodStatus.NEUTRAL, "Corredor da Av. Plínio Ribeiro"),
        KnownNeighborhood("Sagrada Família", -16.7224, -43.8765, NeighborhoodStatus.NEUTRAL, "Bairro residencial próximo à região oeste"),
        KnownNeighborhood("Edgar Pereira", -16.7168, -43.8602, NeighborhoodStatus.NEUTRAL, "Corredor norte central"),
        KnownNeighborhood("Canelas", -16.7524, -43.8631, NeighborhoodStatus.NEUTRAL, "Região sul intermediária"),
        KnownNeighborhood("Vila Brasília", -16.7186, -43.8512, NeighborhoodStatus.NEUTRAL, "Atenção ao trânsito em horários de pico"),
        KnownNeighborhood("Santa Rita", -16.7351, -43.8548, NeighborhoodStatus.NEUTRAL, "Próximo ao centro leste"),
        KnownNeighborhood("São Judas Tadeu", -16.7135, -43.8722, NeighborhoodStatus.NEUTRAL, "Região noroeste"),
        KnownNeighborhood("Maracanã", -16.7568, -43.8780, NeighborhoodStatus.NEUTRAL, "Extremo sul, avaliar distância de retorno"),
        KnownNeighborhood("Jardim Panorama", -16.7460, -43.8505, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Cidade Nova", -16.7192, -43.8794, NeighborhoodStatus.NEUTRAL, "Próximo à saída para Juramento"),
        KnownNeighborhood("Santo Antônio", -16.7124, -43.8562, NeighborhoodStatus.NEUTRAL, "Região norte intermediária"),
        KnownNeighborhood("Lourdes", -16.7295, -43.8520, NeighborhoodStatus.NEUTRAL, "Próximo ao centro"),
        KnownNeighborhood("Esplanada", -16.7160, -43.8442, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Exposição", -16.7418, -43.8588, NeighborhoodStatus.NEUTRAL, "Próximo ao Parque de Exposições"),
        KnownNeighborhood("Santa Lúcia", -16.7478, -43.8615, NeighborhoodStatus.NEUTRAL, "Região sul próximo ao Major Prates"),
        KnownNeighborhood("Vera Cruz", -16.7392, -43.8468, NeighborhoodStatus.NEUTRAL, "Região leste intermediária"),
        KnownNeighborhood("Alcides Rabelo", -16.7068, -43.8605, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Monte Carmelo", -16.7202, -43.8842, NeighborhoodStatus.NEUTRAL, "Região oeste"),
        KnownNeighborhood("Morrinhos", -16.7262, -43.8548, NeighborhoodStatus.NEUTRAL, "Região centro-leste"),
        KnownNeighborhood("Camilo Prates", -16.7340, -43.8452, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Mauricéia", -16.7375, -43.8698, NeighborhoodStatus.NEUTRAL, "Próximo ao Ibituruna"),
        KnownNeighborhood("Universitário", -16.7128, -43.8788, NeighborhoodStatus.NEUTRAL, "Próximo à Unimontes / Campus"),
        KnownNeighborhood("Jaraguá", -16.7088, -43.8695, NeighborhoodStatus.NEUTRAL, "Região norte-noroeste"),
        KnownNeighborhood("Alto São João", -16.7254, -43.8635, NeighborhoodStatus.NEUTRAL, "Região central"),
        KnownNeighborhood("Vila Atlântida", -16.7276, -43.8482, NeighborhoodStatus.NEUTRAL, "Região leste próxima ao centro"),
        KnownNeighborhood("Santo Inácio", -16.7538, -43.8712, NeighborhoodStatus.NEUTRAL, "Região sul"),
        KnownNeighborhood("Nossa Senhora das Graças", -16.7438, -43.8448, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Jardim Alvorada", -16.7504, -43.8455, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Roxo Verde", -16.7318, -43.8422, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Sion", -16.7412, -43.8708, NeighborhoodStatus.NEUTRAL, "Próximo ao Morada do Sol"),
        KnownNeighborhood("Alice Maia", -16.7274, -43.8405, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Cidade Industrial", -16.6980, -43.8350, NeighborhoodStatus.AVOIDED, "Distrito industrial afastado e retorno longo"),
        KnownNeighborhood("Village do Lago", -16.6885, -43.8412, NeighborhoodStatus.AVOIDED, "Retorno longo e baixa iluminação em trechos"),
        KnownNeighborhood("Delfino Magalhães", -16.6962, -43.8585, NeighborhoodStatus.AVOIDED, "Zona de atenção redobrada no período noturno"),
        KnownNeighborhood("Conferência Cristo Rei", -16.6915, -43.8672, NeighborhoodStatus.AVOIDED, "Distante do polo central"),
        KnownNeighborhood("Clarice Athayde", -16.6995, -43.8460, NeighborhoodStatus.AVOIDED, "Bairro periférico, retorno sem demanda"),
        KnownNeighborhood("Vila Oliveira", -16.7062, -43.8415, NeighborhoodStatus.AVOIDED, "Atenção redobrada segundo relatos de entregadores"),
        KnownNeighborhood("Alterosa", -16.7032, -43.8825, NeighborhoodStatus.AVOIDED, "Acesso difícil e retorno longo"),
        KnownNeighborhood("Eldorado", -16.7082, -43.8895, NeighborhoodStatus.AVOIDED, "Extremo noroeste, baixa taxa de retorno"),
        KnownNeighborhood("Renascença", -16.6940, -43.8768, NeighborhoodStatus.AVOIDED, "Zona periférica norte"),
        KnownNeighborhood("Santos Reis", -16.7018, -43.8648, NeighborhoodStatus.AVOIDED, "Atenção em trechos secundários"),
        KnownNeighborhood("João Gordo", -16.7042, -43.8495, NeighborhoodStatus.AVOIDED, "Região norte de atenção redobrada"),
        KnownNeighborhood("Independência", -16.7370, -43.8295, NeighborhoodStatus.AVOIDED, "Muito afastado do Centro (retorno longo)"),
        KnownNeighborhood("Planalto", -16.7104, -43.8330, NeighborhoodStatus.AVOIDED, "Região leste afastada"),
        KnownNeighborhood("Jardim Primavera", -16.7590, -43.8462, NeighborhoodStatus.AVOIDED, "Extremo sudeste com retorno longo"),
        KnownNeighborhood("João Alves", -16.7150, -43.8650, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("São Norberto", -16.7190, -43.8590, NeighborhoodStatus.NEUTRAL, "Região nordeste"),
        KnownNeighborhood("Jardim Palmeiras", -16.7420, -43.8810, NeighborhoodStatus.NEUTRAL, "Região sul"),
        KnownNeighborhood("Vila Ipiranga", -16.7350, -43.8510, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Luiza", -16.7280, -43.8570, NeighborhoodStatus.NEUTRAL, "Região centro-leste"),
        KnownNeighborhood("Morada da Serra", -16.7510, -43.8750, NeighborhoodStatus.NEUTRAL, "Região sul"),
        KnownNeighborhood("Cidade Santa Maria", -16.6950, -43.8520, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Antônio Pimenta", -16.7230, -43.8550, NeighborhoodStatus.NEUTRAL, "Região centro-leste"),
        KnownNeighborhood("Canelas II", -16.7550, -43.8650, NeighborhoodStatus.NEUTRAL, "Região sul"),
        KnownNeighborhood("Village do Lago II", -16.6890, -43.8420, NeighborhoodStatus.AVOIDED, "Região norte"),
        KnownNeighborhood("Village do Lago III", -16.6870, -43.8430, NeighborhoodStatus.AVOIDED, "Região norte"),
        KnownNeighborhood("Parque Cecap", -16.7110, -43.8510, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Chiquinho Guimarães", -16.7140, -43.8540, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Vila Telma", -16.7180, -43.8480, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Regina", -16.7210, -43.8490, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Anália", -16.7240, -43.8480, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Sumaré", -16.7260, -43.8470, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Jardim Liberdade", -16.7450, -43.8520, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Jardim Olímpico", -16.7480, -43.8550, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Conjunto Joaquim Costa", -16.7150, -43.8580, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Vila Tupã", -16.7120, -43.8620, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Interlagos", -16.7520, -43.8580, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Bela Paisagem", -16.7550, -43.8510, NeighborhoodStatus.NEUTRAL, "Região sudeste"),
        KnownNeighborhood("Santa Cecília", -16.7290, -43.8450, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Castelo Branco", -16.7320, -43.8430, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Vila Greice", -16.7350, -43.8420, NeighborhoodStatus.NEUTRAL, "Região leste"),
        KnownNeighborhood("Amazonas", -16.7200, -43.8720, NeighborhoodStatus.NEUTRAL, "Região oeste"),
        KnownNeighborhood("Guarujá", -16.7230, -43.8750, NeighborhoodStatus.NEUTRAL, "Região oeste"),
        KnownNeighborhood("Conjunto José Carlos de Lima", -16.7080, -43.8550, NeighborhoodStatus.NEUTRAL, "Região norte"),
        KnownNeighborhood("Belvedere", -16.7380, -43.8650, NeighborhoodStatus.NEUTRAL, "Região central/sul")
    )

    private val neighborhoodAliases: Map<String, String> = mapOf(
        "jd sao luiz" to "Jardim São Luiz",
        "jd sao luis" to "Jardim São Luiz",
        "jardim sao luis" to "Jardim São Luiz",
        "jd panorama" to "Jardim Panorama",
        "jd primavera" to "Jardim Primavera",
        "jd brasil" to "Jardim Brasil",
        "jd alvorada" to "Jardim Alvorada",
        "maj prates" to "Major Prates",
        "vl brasilia" to "Vila Brasília",
        "vl oliveira" to "Vila Oliveira",
        "vl guilhermina" to "Vila Guilhermina",
        "vl sion" to "Vila Sion",
        "vl atlantida" to "Vila Atlântida",
        "vl exposicao" to "Vila Exposição",
        "vl mauriceia" to "Vila Mauricéia",
        "pq municipal" to "Morada do Parque",
        "sto antonio" to "Santo Antônio",
        "sta rita" to "Santa Rita",
        "sta lucia" to "Santa Lúcia",
        "ns das gracas" to "Nossa Senhora das Graças",
        "n s das gracas" to "Nossa Senhora das Graças",
        "cristo rei" to "Conferência Cristo Rei",
        "conferencia cristo rei" to "Conferência Cristo Rei",
        "clarice ataide" to "Clarice Athayde",
        "conjunto clarice athayde" to "Clarice Athayde",
        "village lago" to "Village do Lago",
        "village do lago i" to "Village do Lago",
        "village do lago ii" to "Village do Lago II",
        "village do lago iii" to "Village do Lago III",
        "village do lago 1" to "Village do Lago",
        "village do lago 2" to "Village do Lago II",
        "village do lago 3" to "Village do Lago III",
        "delfino" to "Delfino Magalhães",
        "sao judas" to "São Judas Tadeu",
        "vila joao gordo" to "João Gordo",
        "alto s joao" to "Alto São João",
        "carmelo" to "Monte Carmelo",
        "distrito industrial" to "Cidade Industrial",
        "sao norberto" to "São Norberto",
        "antonio pimenta" to "Antônio Pimenta",
        "canelas i" to "Canelas",
        "canelas ii" to "Canelas II",
        "cecap" to "Parque Cecap",
        "belvedere" to "Belvedere"
    )

    private val landmarkHints: Map<String, String> = mapOf(
        "montes claros shopping" to "Augusta Mota",
        "moc shopping" to "Augusta Mota",
        "ibituruna center" to "Ibituruna",
        "shopping popular" to "Centro",
        "mercado municipal" to "Centro",
        "praca dr carlos" to "Centro",
        "praca doutor carlos" to "Centro",
        "praca da matriz" to "Centro",
        "santa casa" to "Centro",
        "aroldo tourinho" to "Todos os Santos",
        "dilson godinho" to "Cândida Câmara",
        "unimontes" to "Universitário",
        "campus darcy ribeiro" to "Universitário",
        "rodoviaria" to "Canelas",
        "terminal rodoviario" to "Canelas",
        "parque de exposicoes" to "Vila Exposição",
        "parque municipal" to "Morada do Parque",
        "milton prates" to "Morada do Parque",
        "ceanorte" to "Cidade Industrial",
        "comendador antonio loureiro ramos" to "Cidade Industrial",
        "antonio loureiro ramos" to "Cidade Industrial",
        "deputado esteves rodrigues" to "Centro",
        "esteves rodrigues" to "Centro",
        "av sanitaria" to "Centro",
        "avenida sanitaria" to "Centro",
        "ovidio de abreu" to "Centro",
        "cel celestino" to "Centro",
        "coronel celestino" to "Centro",
        "mestra fininha" to "Jardim São Luiz",
        "dulce sarmento" to "Melo",
        "jose correa machado" to "Ibituruna",
        "correa machado" to "Ibituruna",
        "pedro alvares cabral" to "Ibituruna",
        "francisco gaetani" to "Major Prates",
        "sidney chaves" to "Edgar Pereira",
        "geraldo athayde" to "Alto São João",
        "cula mangabeira" to "Cândida Câmara",
        "donato quintino" to "Cidade Nova",
        "magalhaes pinto" to "Alcides Rabelo",
        "joao xxiii" to "Santos Reis",
        "plinio ribeiro" to "Jardim Brasil"
    )

    private val streetPrefixRegex = Regex(
        "^(?:r|rua|av|avenida|tv|travessa|praca|pc|al|alameda|rod|rodovia|estrada|via)\\b.*"
    )
    private val complementPrefixRegex = Regex(
        "^(?:ap|apt|apto|apartamento|bl|bloco|cs|casa|qd|quadra|lt|lote|lj|loja|sl|sala|fundos|km|n|no|num)\\b.*"
    )
    private val cepRegex = Regex("\\b\\d{5}-?\\d{3}\\b")
    private val cityNoiseRegex = Regex("(?i)\\b(montes claros|minas gerais|mg|brasil|brazil)\\b")

    fun defaultRuleEntities(): List<NeighborhoodRuleEntity> = knownNeighborhoods.map { item ->
        NeighborhoodRuleEntity(
            name = item.name,
            normalizedKey = normalize(item.name),
            status = item.defaultStatus.name,
            notes = item.defaultNote,
            isCustom = false
        )
    }

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalizeAddressCacheKey(address: String): String {
        val cleaned = stripNoiseFromAddress(address)
        return normalize(cleaned.ifBlank { address })
    }

    fun extractMobLetsDirectNeighborhood(address: String): String? {
        if (address.isBlank()) return null
        val singleLine = address.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (!singleLine.contains('-')) return null

        val afterLastDash = singleLine.substringAfterLast('-')
            .replace(cepRegex, " ")
            .replace(cityNoiseRegex, " ")
            .trim(' ', ',', '-', '.', ';')
            .replace(Regex("\\s+"), " ")

        if (afterLastDash.length in 3..40 &&
            !afterLastDash.all { it.isDigit() } &&
            !isCityOrNoiseOnly(normalize(afterLastDash)) &&
            !streetPrefixRegex.matches(normalize(afterLastDash))
        ) {
            return afterLastDash.split(" ").joinToString(" ") { word ->
                if (word.length <= 2 && word.lowercase() !in setOf("do", "da", "dos", "das", "e")) word.lowercase()
                else word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale("pt", "BR")) else it.toString() }
            }
        }
        return null
    }

    fun identifyNeighborhood(
        address: String,
        point: GeoPoint?,
        rules: List<NeighborhoodRuleEntity>
    ): NeighborhoodMatchResult {
        val sortedRules = rules
            .filter { it.normalizedKey.isNotBlank() }
            .sortedByDescending { it.normalizedKey.length }

        // 0. Priority direct Mob Lets pattern rule (text after last '-')
        if (address.isNotBlank()) {
            extractMobLetsDirectNeighborhood(address)?.let { directNb ->
                val normDirect = normalize(directNb)
                val matchedRule = rules.firstOrNull { it.normalizedKey == normDirect || containsWordBoundary(normDirect, it.normalizedKey) }
                    ?: knownNeighborhoods.firstOrNull { normalize(it.name) == normDirect }?.let { cat ->
                        findRuleByCanonicalName(cat.name, rules)
                    }
                return NeighborhoodMatchResult(
                    name = matchedRule?.name ?: directNb,
                    confidence = NeighborhoodConfidence.HIGH,
                    rule = matchedRule,
                    sourceDetail = "Padrão Mob Lets (- Bairro)"
                )
            }
        }

        // 1. Explicit normalized segments after " - " or explicit neighborhood markers (HIGH confidence)
        if (address.isNotBlank()) {
            val postDashSegments = extractNormalizedSegmentsAfterDash(address)
            val candidateSegments = (postDashSegments + extractCandidateNeighborhoodSegments(address).map { normalize(it) })
                .filter { it.isNotBlank() }
                .distinct()

            for (normSeg in candidateSegments) {
                findExactOrBoundaryRuleMatch(normSeg, sortedRules)?.let { rule ->
                    return NeighborhoodMatchResult(
                        name = rule.name,
                        confidence = NeighborhoodConfidence.HIGH,
                        rule = rule,
                        sourceDetail = "Segmento do endereço"
                    )
                }
                findAliasRuleMatch(normSeg, rules)?.let { rule ->
                    return NeighborhoodMatchResult(
                        name = rule.name,
                        confidence = NeighborhoodConfidence.HIGH,
                        rule = rule,
                        sourceDetail = "Sigla/variação de bairro"
                    )
                }
                // Fallback directly against catalog if caller passed partial rules
                knownNeighborhoods
                    .sortedByDescending { normalize(it.name).length }
                    .firstOrNull { item ->
                        val normCatalog = normalize(item.name)
                        normSeg == normCatalog || containsWordBoundary(normSeg, normCatalog)
                    }?.let { catalogItem ->
                        val matchedRule = findRuleByCanonicalName(catalogItem.name, rules)
                        return NeighborhoodMatchResult(
                            name = matchedRule?.name ?: catalogItem.name,
                            confidence = NeighborhoodConfidence.HIGH,
                            rule = matchedRule,
                            sourceDetail = "Catálogo de Montes Claros"
                        )
                    }
            }
        }

        // 2. Geocoder confirmed subLocality / metadata (HIGH when matching registered rule, MEDIUM for Geocoder district)
        val meta = point?.geocoderMetadata
        if (meta != null && isWithinMontesClarosBounds(point.latitude, point.longitude)) {
            val subLocality = meta.subLocality?.trim().orEmpty()
            if (subLocality.isNotBlank()) {
                val normSub = normalize(subLocality)
                if (normSub.isNotBlank() && !isCityOrNoiseOnly(normSub)) {
                    findExactOrBoundaryRuleMatch(normSub, sortedRules)?.let { rule ->
                        return NeighborhoodMatchResult(
                            name = rule.name,
                            confidence = NeighborhoodConfidence.HIGH,
                            rule = rule,
                            sourceDetail = "Geocoder (subLocality)"
                        )
                    }
                    findAliasRuleMatch(normSub, rules)?.let { rule ->
                        return NeighborhoodMatchResult(
                            name = rule.name,
                            confidence = NeighborhoodConfidence.HIGH,
                            rule = rule,
                            sourceDetail = "Geocoder (subLocality/alias)"
                        )
                    }
                    if (!streetPrefixRegex.matches(normSub) && !complementPrefixRegex.matches(normSub)) {
                        return NeighborhoodMatchResult(
                            name = subLocality,
                            confidence = NeighborhoodConfidence.MEDIUM,
                            rule = null,
                            sourceDetail = "Geocoder confirmado"
                        )
                    }
                }
            }
        }

        // 3. Cleaned full address excluding street prefix (HIGH confidence)
        if (address.isNotBlank() && sortedRules.isNotEmpty()) {
            val cleanedFull = normalize(stripNoiseFromAddress(address))
            if (cleanedFull.isNotBlank()) {
                sortedRules.firstOrNull { rule ->
                    containsWordBoundaryOutsideStreetPrefix(cleanedFull, rule.normalizedKey)
                }?.let { rule ->
                    return NeighborhoodMatchResult(
                        name = rule.name,
                        confidence = NeighborhoodConfidence.HIGH,
                        rule = rule,
                        sourceDetail = "Endereço confirmado"
                    )
                }
                findAliasRuleMatch(cleanedFull, rules)?.let { rule ->
                    return NeighborhoodMatchResult(
                        name = rule.name,
                        confidence = NeighborhoodConfidence.HIGH,
                        rule = rule,
                        sourceDetail = "Variação no endereço"
                    )
                }
            }
        }

        // 4. Known Montes Claros avenues & landmarks from address or Geocoder metadata (MEDIUM confidence)
        val hintSearchTexts = listOfNotNull(
            address.takeIf { it.isNotBlank() },
            meta?.thoroughfare,
            meta?.featureName,
            meta?.addressLine
        )
        for (text in hintSearchTexts) {
            val normText = normalize(text)
            val landmarkMatch = landmarkHints.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { (hint, _) -> containsWordBoundary(normText, normalize(hint)) }
            if (landmarkMatch != null) {
                val targetName = landmarkMatch.value
                val matchedRule = findRuleByCanonicalName(targetName, rules)
                return NeighborhoodMatchResult(
                    name = matchedRule?.name ?: targetName,
                    confidence = NeighborhoodConfidence.MEDIUM,
                    rule = matchedRule,
                    sourceDetail = "Avenida / ponto de referência"
                )
            }
        }

        // 5. Remaining full text word-boundary rule match (MEDIUM confidence)
        if (address.isNotBlank() && sortedRules.isNotEmpty()) {
            val fallbackFull = normalize(stripNoiseFromAddress(address)).ifBlank { normalize(address) }
            sortedRules.firstOrNull { rule ->
                containsWordBoundary(fallbackFull, rule.normalizedKey)
            }?.let { rule ->
                return NeighborhoodMatchResult(
                    name = rule.name,
                    confidence = NeighborhoodConfidence.MEDIUM,
                    rule = rule,
                    sourceDetail = "Menção no endereço"
                )
            }
        }

        // 6. Confirmed coordinates inside Montes Claros bounds (MEDIUM <= 1.5 km, LOW <= 4.0 km)
        if (point != null && isWithinMontesClarosBounds(point.latitude, point.longitude)) {
            val nearest = findNearestCatalogNeighborhood(point.latitude, point.longitude, maxDistanceKm = 4.0)
            if (nearest != null) {
                val (catalogItem, distanceKm) = nearest
                val matchedRule = findRuleByCanonicalName(catalogItem.name, rules)
                val confidence = if (distanceKm <= 1.5) {
                    NeighborhoodConfidence.MEDIUM
                } else {
                    NeighborhoodConfidence.LOW
                }
                return NeighborhoodMatchResult(
                    name = matchedRule?.name ?: catalogItem.name,
                    confidence = confidence,
                    rule = matchedRule,
                    sourceDetail = "Coordenada próxima (4km)"
                )
            }
        }

        // 7. Approximate region fallback instead of error/unidentified
        val regionFallback = if (point != null && isWithinMontesClarosBounds(point.latitude, point.longitude)) {
            when {
                point.latitude < -16.74 -> "Região Sul"
                point.latitude > -16.71 -> "Região Norte"
                point.longitude < -43.87 -> "Região Oeste"
                point.longitude > -43.85 -> "Região Leste"
                else -> "Região Central"
            }
        } else {
            "Montes Claros"
        }

        return NeighborhoodMatchResult(
            name = regionFallback,
            confidence = NeighborhoodConfidence.MEDIUM,
            rule = null,
            sourceDetail = "Região aproximada Montes Claros"
        )
    }

    fun detectNeighborhoodRule(
        address: String,
        rules: List<NeighborhoodRuleEntity>
    ): NeighborhoodRuleEntity? {
        return identifyNeighborhood(address = address, point = null, rules = rules).rule
    }

    fun detectNeighborhoodByCoordinates(
        latitude: Double,
        longitude: Double,
        maxDistanceKm: Double = 2.5
    ): KnownNeighborhood? {
        return findNearestCatalogNeighborhood(latitude, longitude, maxDistanceKm)?.first
    }

    fun detectNeighborhoodRuleByAddressOrCoordinates(
        address: String,
        point: GeoPoint?,
        rules: List<NeighborhoodRuleEntity>
    ): NeighborhoodRuleEntity? {
        return identifyNeighborhood(address = address, point = point, rules = rules).rule
    }

    fun extractNeighborhoodLabel(
        address: String,
        rules: List<NeighborhoodRuleEntity>,
        point: GeoPoint? = null
    ): String {
        return identifyNeighborhood(address = address, point = point, rules = rules).name
    }

    fun resolveCoordinatesFromCatalog(address: String): GeoPoint? {
        if (address.isBlank()) return null

        val sortedCatalog = knownNeighborhoods.sortedByDescending { normalize(it.name).length }

        // 1. Check explicit segments after " - " and neighborhood segments first
        val candidateNormSegments = (extractNormalizedSegmentsAfterDash(address) +
            extractCandidateNeighborhoodSegments(address).map { normalize(it) })
            .filter { it.isNotBlank() }
            .distinct()
        for (normSeg in candidateNormSegments) {
            sortedCatalog.firstOrNull { item ->
                containsWordBoundary(normSeg, normalize(item.name))
            }?.let { return GeoPoint(it.latitude, it.longitude, it.name) }

            findAliasCatalogMatch(normSeg)?.let {
                return GeoPoint(it.latitude, it.longitude, it.name)
            }
        }

        // 2. Check full cleaned address outside street prefixes
        val cleanedFull = normalize(stripNoiseFromAddress(address))
        if (cleanedFull.isNotBlank()) {
            sortedCatalog.firstOrNull { item ->
                containsWordBoundaryOutsideStreetPrefix(cleanedFull, normalize(item.name))
            }?.let { return GeoPoint(it.latitude, it.longitude, it.name) }

            findAliasCatalogMatch(cleanedFull)?.let {
                return GeoPoint(it.latitude, it.longitude, it.name)
            }
        }

        // 3. Landmark / avenue hints fallback
        val fullNorm = normalize(address)
        val landmark = landmarkHints.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (hint, _) -> containsWordBoundary(fullNorm, normalize(hint)) }
            ?.value
        if (landmark != null) {
            knownNeighborhoods.firstOrNull { it.name == landmark }?.let {
                return GeoPoint(it.latitude, it.longitude, it.name)
            }
        }

        // 4. Final word-boundary check in full normalized text
        sortedCatalog.firstOrNull { item ->
            containsWordBoundary(cleanedFull.ifBlank { fullNorm }, normalize(item.name))
        }?.let { return GeoPoint(it.latitude, it.longitude, it.name) }

        return null
    }

    private fun findNearestCatalogNeighborhood(
        latitude: Double,
        longitude: Double,
        maxDistanceKm: Double
    ): Pair<KnownNeighborhood, Double>? {
        if (!isWithinMontesClarosBounds(latitude, longitude)) return null
        var bestMatch: KnownNeighborhood? = null
        var bestDistanceKm = Double.MAX_VALUE
        for (item in knownNeighborhoods) {
            val dKm = approxHaversineKm(latitude, longitude, item.latitude, item.longitude)
            if (dKm < bestDistanceKm) {
                bestDistanceKm = dKm
                bestMatch = item
            }
        }
        return if (bestMatch != null && bestDistanceKm <= maxDistanceKm) {
            bestMatch to bestDistanceKm
        } else {
            null
        }
    }

    private fun findRuleByCanonicalName(
        canonicalName: String,
        rules: List<NeighborhoodRuleEntity>
    ): NeighborhoodRuleEntity? {
        val targetNorm = normalize(canonicalName)
        return rules.firstOrNull { it.normalizedKey == targetNorm || normalize(it.name) == targetNorm }
    }

    private fun isCityOrNoiseOnly(normalizedText: String): Boolean =
        normalizedText in setOf("montes claros", "minas gerais", "mg", "brasil", "brazil")

    fun approxHaversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val sinLat = Math.sin(dLat / 2.0)
        val sinLon = Math.sin(dLon / 2.0)
        val a = sinLat * sinLat + Math.cos(rLat1) * Math.cos(rLat2) * sinLon * sinLon
        return 2.0 * earthRadiusKm * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
    }

    fun isPureNeighborhoodOrCity(text: String): Boolean {
        val cleaned = normalize(stripNoiseFromAddress(text))
        if (cleaned.isBlank()) return true
        if (isCityOrNoiseOnly(cleaned)) return true
        val bare = cleaned.removePrefix("bairro ").removePrefix("br ").trim()
        if (bare.isBlank()) return true
        if (knownNeighborhoods.any { normalize(it.name) == bare }) return true
        if (neighborhoodAliases.keys.any { it == bare }) return true
        return false
    }

    /**
     * Extracts and normalizes text located after " - " (removing ", Montes Claros",
     * "- MG", CEPs, accents, punctuation, and converting to lowercase).
     */
    fun extractNormalizedSegmentsAfterDash(address: String): List<String> {
        if (address.isBlank() || !address.contains('-')) return emptyList()
        val afterDashChunks = address.split(Regex("""\s+-\s+"""))
        if (afterDashChunks.size < 2) return emptyList()

        return afterDashChunks.drop(1)
            .flatMap { chunk ->
                val cleanedChunk = stripNoiseFromAddress(chunk)
                val subParts = cleanedChunk.split(",", "/", "•")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                listOf(cleanedChunk) + subParts
            }
            .map { normalize(it) }
            .filter { norm ->
                norm.length >= 3 &&
                    !norm.all { it.isDigit() || it == ' ' } &&
                    !isCityOrNoiseOnly(norm) &&
                    !streetPrefixRegex.matches(norm) &&
                    !complementPrefixRegex.matches(norm)
            }
            .distinct()
    }

    private fun extractCandidateNeighborhoodSegments(address: String): List<String> {
        val result = mutableListOf<String>()
        val cleaned = stripNoiseFromAddress(address)

        val bairroRegex = Regex("(?i)(?:\\bbairro\\b|\\bbr\\.?)[:\\s]+([\\p{L}0-9\\s]{3,36})")
        bairroRegex.find(cleaned)?.groupValues?.getOrNull(1)?.let { match ->
            val trimmed = match.split(",", "-", "/").firstOrNull()?.trim().orEmpty()
            if (trimmed.isNotBlank()) result.add(trimmed)
        }

        val parts = cleaned.split("-", ",", "/", "•")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (parts.size >= 2) {
            for (part in parts.asReversed()) {
                val normPart = normalize(part)
                if (normPart.isBlank() || normPart.all { it.isDigit() }) continue
                if (streetPrefixRegex.matches(normPart)) continue
                if (complementPrefixRegex.matches(normPart)) continue
                result.add(part)
            }
        }
        return result.distinct()
    }

    private fun stripNoiseFromAddress(address: String): String {
        return address
            .replace(cepRegex, " ")
            .replace(cityNoiseRegex, " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', ',', '-', '/')
    }

    private fun findExactOrBoundaryRuleMatch(
        normalizedSegment: String,
        sortedRules: List<NeighborhoodRuleEntity>
    ): NeighborhoodRuleEntity? {
        sortedRules.firstOrNull { it.normalizedKey == normalizedSegment }?.let { return it }
        return sortedRules.firstOrNull { containsWordBoundary(normalizedSegment, it.normalizedKey) }
    }

    private fun findAliasRuleMatch(
        normalizedText: String,
        rules: List<NeighborhoodRuleEntity>
    ): NeighborhoodRuleEntity? {
        val canonical = neighborhoodAliases.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) -> containsWordBoundary(normalizedText, alias) }
            ?.value ?: return null
        return findRuleByCanonicalName(canonical, rules)
    }

    private fun findAliasCatalogMatch(normalizedText: String): KnownNeighborhood? {
        val canonical = neighborhoodAliases.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) -> containsWordBoundary(normalizedText, alias) }
            ?.value ?: return null
        return knownNeighborhoods.firstOrNull { it.name == canonical }
    }

    private fun containsWordBoundary(haystack: String, needle: String): Boolean {
        if (haystack.isBlank() || needle.isBlank()) return false
        val regex = Regex("(?:^|\\s)${Regex.escape(needle)}(?:\\s|$)")
        return regex.containsMatchIn(haystack)
    }

    private fun containsWordBoundaryOutsideStreetPrefix(haystack: String, needle: String): Boolean {
        if (haystack.isBlank() || needle.isBlank()) return false
        val regex = Regex("(?:^|\\s)${Regex.escape(needle)}(?:\\s|$)")
        val matches = regex.findAll(haystack)
        for (match in matches) {
            val prefixWindow = haystack.substring(0, match.range.first).trim()
            val lastWordBefore = prefixWindow.substringAfterLast(' ', "")
            val isStreetWord = lastWordBefore in setOf(
                "r", "rua", "av", "avenida", "tv", "travessa", "praca", "pc", "al", "alameda", "rodovia", "estrada"
            )
            if (!isStreetWord) return true
        }
        return false
    }
}
