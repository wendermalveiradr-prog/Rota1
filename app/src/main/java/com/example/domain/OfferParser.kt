package com.example.domain

data class CachedOfferScreenMetrics(
    val priceBrl: Double,
    val screenTotalKm: Double? = null,
    val storeNameHint: String = "",
    val capturedAtMs: Long
)

object RecentOfferPriceCache {
    const val CACHE_TTL_MS = 120_000L // 2 minutes

    @Volatile
    private var lastCaptured: CachedOfferScreenMetrics? = null

    private val lock = Any()

    fun remember(
        priceBrl: Double,
        screenTotalKm: Double? = null,
        storeNameHint: String = "",
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!priceBrl.isFinite() || priceBrl <= 0.5 || priceBrl > 1500.0) return
        val cleanStore = storeNameHint.trim()
            .takeIf { OfferParser.isPlausibleStoreName(it) }
            .orEmpty()
        synchronized(lock) {
            val prev = lastCaptured
            val sameRecentPrice = prev != null &&
                (nowMs - prev.capturedAtMs) in 0..CACHE_TTL_MS &&
                kotlin.math.abs(prev.priceBrl - priceBrl) < 0.01
            lastCaptured = CachedOfferScreenMetrics(
                priceBrl = priceBrl,
                screenTotalKm = screenTotalKm ?: if (sameRecentPrice) prev.screenTotalKm else null,
                storeNameHint = cleanStore.ifBlank { if (sameRecentPrice) prev.storeNameHint else "" },
                capturedAtMs = nowMs
            )
        }
    }

    fun getValid(nowMs: Long = System.currentTimeMillis()): CachedOfferScreenMetrics? {
        synchronized(lock) {
            val current = lastCaptured ?: return null
            val ageMs = nowMs - current.capturedAtMs
            return if (ageMs in 0..CACHE_TTL_MS) current else null
        }
    }

    fun clear() {
        synchronized(lock) {
            lastCaptured = null
        }
    }
}

data class ParsedOfferDraft(
    val offerPriceBrl: Double,
    val storeName: String = "",
    val storeConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
    val storeNameIdentified: Boolean = false,
    val pickupAddress: String,
    val pickupAddressConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
    val pickupNeighborhoodHint: String = "",
    val pickupNeighborhoodConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
    val customerAddress: String,
    val customerAddressConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
    val customerNeighborhoodHint: String = "",
    val customerNeighborhoodConfidence: FieldConfidence = FieldConfidence.UNIDENTIFIED,
    val rawSnippet: String,
    val isHighConfidence: Boolean,
    val pickupDistanceKm: Double? = null,
    val screenTotalKm: Double? = null,
    val isDetailsScreen: Boolean = false,
    val usedCachedPrice: Boolean = false
)

object OfferParser {

    const val UNIDENTIFIED_LABEL = "Não identificado"
    const val UNIDENTIFIED_STORE_NAME = UNIDENTIFIED_LABEL

    private val priceRegex = Regex(
        """(?:R\$\s*|Valor[:\s]*R?\$?\s*)(\d{1,3}(?:\.\d{3})*[.,]\d{2}|\d+[.,]\d{2})""",
        RegexOption.IGNORE_CASE
    )
    private val standaloneCurrencyRegex = Regex("""R\$\s*(\d+[.,]\d{2})""", RegexOption.IGNORE_CASE)
    private val parenthesizedKmRegex = Regex("""\((\d+(?:[.,]\d+)?)\s*(km|m)\)""", RegexOption.IGNORE_CASE)
    private val anyKmRegex = Regex("""(\d+(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)

    // Generic structural markers for store/establishment (never hardcoded store brand lists)
    private val storeMarkers = listOf(
        "nome da loja", "estabelecimento", "restaurante", "loja", "parceiro",
        "remetente", "comércio", "comercio", "local de retirada"
    )

    // Labels that designate pickup location/address
    private val pickupMarkers = listOf(
        "endereço de retirada", "endereco de retirada", "retirada", "coleta",
        "origem", "buscar em", "pegar em", "retirar em", "de:"
    )

    // Labels that designate customer delivery location/address (including Mob Lets "1º parada")
    private val customerMarkers = listOf(
        "endereço de entrega", "endereco de entrega", "entregar em", "levar para",
        "1º parada", "1ª parada", "1a parada", "primeira parada", "parada 1",
        "2º parada", "2ª parada", "entrega", "destino", "destinatário", "destinatario",
        "cliente", "para:"
    )

    private val detailsScreenMarkers = listOf(
        "detalhes da entrega", "1º parada", "1ª parada", "1a parada", "primeira parada", "parada 1"
    )

    private val storeMarkerRegexes = storeMarkers.map { marker ->
        Regex("""(?i)^\s*${Regex.escape(marker)}(?:\b|\s*:|\s*[-•])""")
    }

    private val inlinePickupMarkerRegexes = pickupMarkers.map { marker ->
        Regex("""(?i)(?:^|\b)${Regex.escape(marker.trimEnd(':'))}(?:\b|:)""")
    }

    private val inlineCustomerMarkerRegexes = customerMarkers.map { marker ->
        Regex("""(?i)(?:^|\b)${Regex.escape(marker.trimEnd(':'))}(?:\b|:)""")
    }

    private val streetPrefixRegex = Regex(
        """(?i)\b(rua|r\.|av\.?|avenida|travessa|tv\.?|praça|praca|alameda|rodovia|estrada|largo|via)\b"""
    )

    private val fullStreetWithContentRegex = Regex(
        """(?i)\b(rua|r\.|av\.?|avenida|travessa|tv\.?|praça|praca|alameda|rodovia|estrada|largo|via)\b.+"""
    )

    private val addressNumberOrCepRegex = Regex("""(?i)\b\d{5}-?\d{3}\b|,\s*\d{1,5}\b|\bn[º°o.]?\s*\d{1,5}\b""")
    private val distanceOrEtaNoiseRegex = Regex(
        """(?i)^\s*(?:\(?\s*~?\d+(?:[.,]\d+)?\s*(?:km|m|min|seg|s)\s*\)?|até\s+(?:você|loja|o\s+cliente)|\d{1,2}:\d{2})\s*$"""
    )
    private val phoneNoiseRegex = Regex("""^\s*(?:\(?\d{2}\)?\s*)?\d{4,5}-?\d{4}\s*$""")

    private val uiNoiseExactOrContained = setOf(
        "mob lets", "moblets", "mob lets entregador", "nova oferta", "nova oferta disponivel",
        "oferta disponivel", "detalhes da entrega", "aceitar", "aceitar corrida", "aceitar entrega",
        "recusar", "rejeitar", "ignorar", "fechar", "detalhes", "ver rota", "ver mapa",
        "entregador", "corrida", "pagamento", "dinheiro", "pix", "cartao", "maquininha",
        "troco", "online", "offline", "disponivel", "aguardando", "calcular", "confirmar",
        "retirada", "coleta", "entrega", "cliente", "destino", "origem", "loja",
        "estabelecimento", "restaurante", "1o parada", "1a parada", "primeira parada", "parada 1",
        "nao identificado", "nao identificada", "nao informado", "nao informada",
        "endereco nao informado", "sem endereco"
    )

    private enum class BlockSection {
        HEADER, STORE, PICKUP, CUSTOMER, RETURN
    }

    fun isDetailsScreenText(rawText: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(rawText)
        return detailsScreenMarkers.any { marker ->
            norm.contains(MontesClarosGeoCatalog.normalize(marker))
        } || parenthesizedKmRegex.containsMatchIn(rawText)
    }

    /**
     * Captures price (R$), total distance (km) and plausible store name from any Mob Lets
     * Offer screen so they can be merged seamlessly with the "Detalhes da entrega" screen
     * for up to 2 minutes via [RecentOfferPriceCache].
     */
    fun extractOfferScreenMetrics(rawText: String): CachedOfferScreenMetrics? {
        val cleanLines = rawText.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
        if (cleanLines.isEmpty()) return null
        val joined = cleanLines.joinToString("\n")
        if (joined.contains("Nenhuma entrega no momento", ignoreCase = true)) return null
        val price = extractPrice(joined) ?: return null
        if (price <= 0.5 || price > 1500.0) return null

        val screenTotalKm = extractScreenTotalKm(joined)
        val storeHint = cleanLines.firstOrNull { line ->
            !isAnySectionMarker(line) &&
                !looksLikePrice(line) &&
                !isDistanceOrMetaNoise(line) &&
                !looksLikeAddressLine(line) &&
                !MontesClarosGeoCatalog.isPureNeighborhoodOrCity(line) &&
                isPlausibleStoreName(stripParenthesizedDistance(line))
        }?.let { stripParenthesizedDistance(it) }.orEmpty()

        return CachedOfferScreenMetrics(
            priceBrl = price,
            screenTotalKm = screenTotalKm,
            storeNameHint = storeHint,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun parse(
        rawText: String,
        requireDeliveryKeywords: Boolean = true,
        fallbackPriceBrl: Double? = null,
        fallbackTotalKm: Double? = null,
        fallbackStoreName: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): ParsedOfferDraft? {
        val cleanLines = rawText
            .lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && it.length >= 2 }

        if (cleanLines.isEmpty()) return null
        val joined = cleanLines.joinToString("\n")
        if (joined.contains("Nenhuma entrega no momento", ignoreCase = true)) return null
        val isDetailsScreen = isDetailsScreenText(joined)

        val directPrice = extractPrice(joined)
        val cachedMetrics = if (directPrice == null && isDetailsScreen) {
            RecentOfferPriceCache.getValid(nowMs)
        } else {
            null
        }

        val resolvedPrice = directPrice
            ?: fallbackPriceBrl?.takeIf { isDetailsScreen }
            ?: cachedMetrics?.priceBrl
            ?: return null

        if (resolvedPrice <= 0.5 || resolvedPrice > 1500.0) return null
        val usedCachedPrice = directPrice == null

        val lowerJoined = joined.lowercase()
        val hasDeliveryContext = storeMarkerRegexes.any { it.containsMatchIn(lowerJoined) } ||
            inlinePickupMarkerRegexes.any { it.containsMatchIn(lowerJoined) } ||
            inlineCustomerMarkerRegexes.any { it.containsMatchIn(lowerJoined) } ||
            isDetailsScreen ||
            lowerJoined.contains("mob lets") ||
            lowerJoined.contains("moblets") ||
            lowerJoined.contains("aceitar") ||
            lowerJoined.contains("nova oferta") ||
            lowerJoined.contains("corrida")

        if (requireDeliveryKeywords && !hasDeliveryContext) {
            return null
        }

        val pickupDistanceKm = extractParenthesizedPickupKm(joined)
        val screenTotalKm = extractScreenTotalKm(joined)
            ?: fallbackTotalKm
            ?: cachedMetrics?.screenTotalKm

        var detectedStoreName = ""
        var storeFromExplicitMarker = false
        var pickupFromExplicitSection = false
        var customerFromExplicitSection = false

        val pickupParts = mutableListOf<String>()
        val customerParts = mutableListOf<String>()
        var pickupNeighborhoodHint = ""
        var customerNeighborhoodHint = ""

        val paymentNoiseTerms = setOf(
            "carteira de creditos", "carteira de créditos", "faturado", "dinheiro", "pix",
            "cartao", "cartão", "credito", "crédito", "debito", "débito", "pagamento"
        )

        val deliveryStopMarkers = listOf("1º parada", "1ª parada", "1a parada", "primeira parada", "parada 1")
        val deliveryStopIdx = cleanLines.indexOfFirst { line ->
            val l = line.lowercase()
            deliveryStopMarkers.any { l.contains(it) }
        }

        val returnMarkers = listOf("retorno", "ponto de retorno")
        val returnIdx = cleanLines.indexOfFirst { line ->
            val l = line.lowercase()
            returnMarkers.any { l.contains(it) }
        }

        val pickupLines = if (deliveryStopIdx >= 0) {
            cleanLines.take(deliveryStopIdx)
        } else {
            cleanLines.take(kotlin.math.min(cleanLines.size, 4))
        }

        val customerLines = if (deliveryStopIdx >= 0) {
            val endIdx = if (returnIdx > deliveryStopIdx) returnIdx else cleanLines.size
            cleanLines.subList(deliveryStopIdx + 1, endIdx)
        } else {
            cleanLines.drop(kotlin.math.min(cleanLines.size, 4))
        }

        // Process pickup & store lines (above 1º parada)
        for (rawLine in pickupLines) {
            val line = stripParenthesizedDistance(rawLine)
            if (line.isBlank()) continue
            val lowerLine = line.lowercase()

            if (paymentNoiseTerms.any { lowerLine.contains(it) } || lowerLine.startsWith("os ") || lowerLine.startsWith("pedido")) {
                val parenMatch = Regex("""\(([^)]+)\)""").find(line)
                if (parenMatch != null) {
                    val inner = parenMatch.groupValues[1].trim()
                    if (isPlausibleStoreName(inner) && detectedStoreName.isBlank()) {
                        detectedStoreName = inner
                        storeFromExplicitMarker = true
                    }
                }
                continue
            }

            if (isUiScreenHeaderLine(line) || isUiFooterCtaLine(line) || isDistanceOrMetaNoise(line) || looksLikePrice(line)) {
                continue
            }

            val storeMatch = storeMarkerRegexes.firstNotNullOfOrNull { reg -> reg.find(line)?.range?.last }
            if (storeMatch != null) {
                val storeVal = line.substring(storeMatch + 1).trim(' ', ':', '-', '•')
                if (storeVal.isNotBlank() && isPlausibleStoreName(storeVal) && detectedStoreName.isBlank()) {
                    detectedStoreName = storeVal
                    storeFromExplicitMarker = true
                }
            }

            val (storePart, addressPart) = splitStoreAndAddress(line)
            if (storePart.isNotBlank() && detectedStoreName.isBlank() && isPlausibleStoreName(storePart)) {
                detectedStoreName = storePart
                storeFromExplicitMarker = true
            }

            val targetAddr = addressPart.ifBlank { line }.replace(Regex("(?i)^coleta\\s*[:\\-]?\\s*"), "").trim()
            if (looksLikeAddressLine(targetAddr) && !detectedStoreName.equals(targetAddr, ignoreCase = true)) {
                pickupParts.add(targetAddr)
                pickupFromExplicitSection = true
                extractExplicitNeighborhood(targetAddr)?.let { hint ->
                    if (pickupNeighborhoodHint.isBlank()) pickupNeighborhoodHint = hint
                }
            } else if (isPlausibleStoreName(line) && detectedStoreName.isBlank()) {
                detectedStoreName = line
                storeFromExplicitMarker = true
            }
        }

        // Process customer delivery lines (below 1º parada)
        for (rawLine in customerLines) {
            val line = stripParenthesizedDistance(rawLine)
            if (line.isBlank()) continue
            val lowerLine = line.lowercase()

            if (paymentNoiseTerms.any { lowerLine.contains(it) } || lowerLine.startsWith("os ") || isUiFooterCtaLine(line) || isDistanceOrMetaNoise(line) || looksLikePrice(line)) {
                continue
            }

            if (looksLikeAddressLine(line) || hasMeaningfulAddressTokens(line)) {
                customerParts.add(line)
                customerFromExplicitSection = true
                extractExplicitNeighborhood(line)?.let { hint ->
                    if (customerNeighborhoodHint.isBlank()) customerNeighborhoodHint = hint
                }
            }
        }

        var pickupAddress = joinAddressParts(pickupParts)
        var customerAddress = joinAddressParts(customerParts)
        val pickupFromContextFallback = false
        val customerFromContextFallback = false

        if (detectedStoreName.isBlank()) {
            detectedStoreName = fallbackStoreName?.takeIf { isPlausibleStoreName(it) }
                ?: cachedMetrics?.storeNameHint?.takeIf { isPlausibleStoreName(it) }
                ?: ""
        }

        if (pickupAddress.isBlank() && customerAddress.isBlank()) {
            return null
        }

        // Strict safety guard: never let an address or neighborhood leak into storeName
        val sanitizedStoreName = detectedStoreName.trim().take(80)
        val isStoreValid = sanitizedStoreName.isNotBlank() &&
            isPlausibleStoreName(sanitizedStoreName) &&
            !looksLikeAddressLine(sanitizedStoreName) &&
            !MontesClarosGeoCatalog.isPureNeighborhoodOrCity(sanitizedStoreName)

        val finalStoreName = if (isStoreValid) sanitizedStoreName else ""
        val storeConfidence = when {
            !isStoreValid -> FieldConfidence.UNIDENTIFIED
            storeFromExplicitMarker -> FieldConfidence.HIGH
            else -> FieldConfidence.MEDIUM
        }

        val finalPickup = pickupAddress.take(180)
        val finalCustomer = customerAddress.take(180)

        val finalPickupNeighborhoodHint = pickupNeighborhoodHint.ifBlank {
            extractExplicitNeighborhood(finalPickup).orEmpty()
        }
        val finalCustomerNeighborhoodHint = customerNeighborhoodHint.ifBlank {
            extractExplicitNeighborhood(finalCustomer).orEmpty()
        }

        val pickupAddressConfidence = computeAddressConfidence(
            address = finalPickup,
            explicitSection = pickupFromExplicitSection,
            contextFallback = pickupFromContextFallback
        )
        val customerAddressConfidence = computeAddressConfidence(
            address = finalCustomer,
            explicitSection = customerFromExplicitSection,
            contextFallback = customerFromContextFallback
        )

        val pickupNeighborhoodConfidence = computeDraftNeighborhoodConfidence(
            address = finalPickup,
            explicitHint = finalPickupNeighborhoodHint
        )
        val customerNeighborhoodConfidence = computeDraftNeighborhoodConfidence(
            address = finalCustomer,
            explicitHint = finalCustomerNeighborhoodHint
        )

        // Automatically keep recent offer price + screen km warm whenever direct price was seen
        if (directPrice != null) {
            RecentOfferPriceCache.remember(
                priceBrl = directPrice,
                screenTotalKm = screenTotalKm,
                storeNameHint = finalStoreName,
                nowMs = nowMs
            )
        }

        return ParsedOfferDraft(
            offerPriceBrl = resolvedPrice,
            storeName = finalStoreName,
            storeConfidence = storeConfidence,
            storeNameIdentified = isStoreValid,
            pickupAddress = finalPickup,
            pickupAddressConfidence = pickupAddressConfidence,
            pickupNeighborhoodHint = finalPickupNeighborhoodHint,
            pickupNeighborhoodConfidence = pickupNeighborhoodConfidence,
            customerAddress = finalCustomer,
            customerAddressConfidence = customerAddressConfidence,
            customerNeighborhoodHint = finalCustomerNeighborhoodHint,
            customerNeighborhoodConfidence = customerNeighborhoodConfidence,
            rawSnippet = joined.take(500),
            isHighConfidence = pickupAddressConfidence == FieldConfidence.HIGH &&
                customerAddressConfidence == FieldConfidence.HIGH,
            pickupDistanceKm = pickupDistanceKm,
            screenTotalKm = screenTotalKm,
            isDetailsScreen = isDetailsScreen,
            usedCachedPrice = usedCachedPrice
        )
    }

    private fun extractParenthesizedPickupKm(text: String): Double? {
        val match = parenthesizedKmRegex.find(text) ?: return null
        val numStr = match.groupValues.getOrNull(1)?.replace(",", ".") ?: return null
        val unit = match.groupValues.getOrNull(2)?.lowercase() ?: "km"
        val value = numStr.toDoubleOrNull() ?: return null
        val km = if (unit == "m") value / 1000.0 else value
        return km.takeIf { it > 0.0 && it < 100.0 }
    }

    private fun extractScreenTotalKm(text: String): Double? {
        val withoutParenthesized = parenthesizedKmRegex.replace(text, " ")
        val obsKmMatch = Regex("(?i)Obs:\\s*\\((\\d+(?:[.,]\\d+)?)\\s*km").find(text)
        if (obsKmMatch != null) {
            val v = obsKmMatch.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            if (v != null && v in 0.2..120.0) return v
        }
        val candidates = anyKmRegex.findAll(withoutParenthesized)
            .mapNotNull { m ->
                m.groupValues.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull()
            }
            .filter { it in 0.2..120.0 }
            .toList()
        return candidates.firstOrNull()
    }

    private fun stripParenthesizedDistance(line: String): String {
        return parenthesizedKmRegex.replace(line, " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun computeAddressConfidence(
        address: String,
        explicitSection: Boolean,
        contextFallback: Boolean
    ): FieldConfidence {
        if (address.isBlank() || isUninformedPlaceholder(address)) return FieldConfidence.UNIDENTIFIED
        val hasStreetOrNumber = fullStreetWithContentRegex.containsMatchIn(address) ||
            addressNumberOrCepRegex.containsMatchIn(address)
        return when {
            explicitSection && hasStreetOrNumber -> FieldConfidence.HIGH
            explicitSection || (contextFallback && hasStreetOrNumber) -> FieldConfidence.MEDIUM
            else -> FieldConfidence.LOW
        }
    }

    private fun computeDraftNeighborhoodConfidence(
        address: String,
        explicitHint: String
    ): FieldConfidence {
        if (address.isBlank() && explicitHint.isBlank()) return FieldConfidence.UNIDENTIFIED
        if (explicitHint.isNotBlank()) return FieldConfidence.HIGH
        val match = MontesClarosGeoCatalog.identifyNeighborhood(
            address = address,
            point = null,
            rules = MontesClarosGeoCatalog.defaultRuleEntities()
        )
        return FieldConfidence.fromNeighborhood(match.confidence)
    }

    fun extractPrice(text: String): Double? {
        val match = priceRegex.find(text) ?: standaloneCurrencyRegex.find(text) ?: return null
        val rawNumber = match.groupValues.getOrNull(1) ?: return null
        val normalized = if (rawNumber.contains(",") && rawNumber.contains(".")) {
            rawNumber.replace(".", "").replace(",", ".")
        } else {
            rawNumber.replace(",", ".")
        }
        return normalized.toDoubleOrNull()
    }

    fun isPlausibleStoreName(candidate: String): Boolean {
        val trimmed = candidate.trim(' ', ':', '-', '•', '|', ',')
        if (trimmed.length !in 3..65) return false

        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount < 2) return false

        val norm = MontesClarosGeoCatalog.normalize(trimmed)
        if (norm.isBlank() || norm in uiNoiseExactOrContained) return false
        if (uiNoiseExactOrContained.any { norm.startsWith("$it ") && norm.length <= it.length + 6 }) return false
        if (isUninformedPlaceholder(trimmed)) return false
        if (isDistanceOrMetaNoise(trimmed) || looksLikePrice(trimmed) || phoneNoiseRegex.matches(trimmed)) return false
        if (looksLikeAddressLine(trimmed)) return false
        if (MontesClarosGeoCatalog.isPureNeighborhoodOrCity(trimmed)) return false

        val lower = trimmed.lowercase()
        if (Regex("""^(?:ap|apt|apto|apartamento|bl|bloco|cs|casa|qd|quadra|lt|lote|sl|sala|fundos|cep|bairro)\b""").containsMatchIn(lower)) {
            return false
        }
        if (norm.all { it.isDigit() || it == ' ' }) return false
        return true
    }

    fun looksLikeAddressLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase()
        if (fullStreetWithContentRegex.containsMatchIn(lower)) return true
        if (addressNumberOrCepRegex.containsMatchIn(lower) && streetPrefixRegex.containsMatchIn(lower)) return true
        if (Regex("""(?i)\b(?:cep|montes claros|minas gerais|\bmg\b)\b""").containsMatchIn(lower)) return true
        if (Regex("""(?i)^(?:bairro|br\.?)[:\s]+.+""").containsMatchIn(lower)) return true
        if (Regex("""(?i)^.+\s*[-,]\s*\d{1,5}(?:\s*[-,].*)?$""").matches(lower) &&
            !lower.contains("24h", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun splitStoreAndAddress(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "" to ""

        val streetMatch = streetPrefixRegex.find(trimmed) ?: return "" to ""
        if (streetMatch.range.first <= 3) {
            return "" to trimmed
        }

        val leftRaw = trimmed.substring(0, streetMatch.range.first)
            .trimEnd(' ', '-', '•', '|', ',', '/', ':')
            .trim()
        val rightRaw = trimmed.substring(streetMatch.range.first).trim()

        val hadSeparator = trimmed.substring(0, streetMatch.range.first)
            .trimEnd()
            .let { it.endsWith("-") || it.endsWith("•") || it.endsWith("|") || it.endsWith(",") || it.endsWith("/") }

        return if (hadSeparator && isPlausibleStoreName(leftRaw) && rightRaw.length >= 6) {
            leftRaw to rightRaw
        } else {
            "" to trimmed
        }
    }

    private fun extractExplicitNeighborhood(text: String): String? {
        val match = Regex("""(?i)(?:\bbairro\b|\bbr\.?)[:\s]+([\p{L}0-9\s]{3,36})""").find(text)
            ?: return null
        val candidate = match.groupValues.getOrNull(1)
            ?.split(",", "-", "/", "•")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf {
                it.length >= 3 &&
                    !it.all { ch -> ch.isDigit() } &&
                    !isUninformedPlaceholder(it)
            }
        return candidate
    }

    private fun joinAddressParts(parts: List<String>): String {
        val cleaned = parts
            .map { it.trim(' ', '-', '•', '|') }
            .filter { it.isNotBlank() && !isUninformedPlaceholder(it) }
            .distinct()
        return cleaned.joinToString(" - ")
    }

    private fun looksLikeAddressContinuation(line: String, alreadyHasAddressLine: Boolean): Boolean {
        if (isDistanceOrMetaNoise(line) || looksLikePrice(line) || isUiFooterCtaLine(line) || isUninformedPlaceholder(line)) {
            return false
        }
        if (looksLikeAddressLine(line) || MontesClarosGeoCatalog.isPureNeighborhoodOrCity(line)) return true
        if (alreadyHasAddressLine) {
            val norm = MontesClarosGeoCatalog.normalize(line)
            if (norm.length in 3..55 && !norm.contains("aceitar") && !norm.contains("recusar")) {
                return true
            }
        }
        return false
    }

    private fun hasMeaningfulAddressTokens(text: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(text)
        return norm.length >= 4 && norm !in uiNoiseExactOrContained && !isUninformedPlaceholder(text)
    }

    private fun isUninformedPlaceholder(text: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(text)
        return norm in setOf(
            "nao identificado", "nao identificada", "nao informado", "nao informada",
            "endereco nao informado", "bairro nao informado", "bairro nao identificado",
            "loja nao identificada", "loja nao informada", "sem bairro", "sem endereco"
        )
    }

    private fun extractValueAfterMarker(line: String, markerRegexes: List<Regex>): String? {
        val colonIndex = line.indexOf(':')
        if (colonIndex in 2 until line.length - 2) {
            val afterColon = line.substring(colonIndex + 1).trim()
            if (afterColon.length >= 3 && !looksLikePrice(afterColon) && !isDistanceOrMetaNoise(afterColon)) {
                return afterColon
            }
        }
        for (regex in markerRegexes) {
            val match = regex.find(line)
            if (match != null) {
                val remainder = line.substring(match.range.last + 1)
                    .trimStart(' ', ':', '-', '•', '>', '|')
                    .trim()
                if (remainder.length >= 3 && !looksLikePrice(remainder) && !isDistanceOrMetaNoise(remainder)) {
                    return remainder
                }
            }
        }
        return null
    }

    private fun isStoreMarkerLine(line: String): Boolean =
        !isUiScreenHeaderLine(line) && storeMarkerRegexes.any { it.containsMatchIn(line) }

    private fun isPickupMarkerLine(line: String): Boolean =
        !isUiScreenHeaderLine(line) &&
            !isStoreMarkerLine(line) &&
            inlinePickupMarkerRegexes.any { it.containsMatchIn(line) }

    private fun isCustomerMarkerLine(line: String): Boolean {
        if (isUiScreenHeaderLine(line)) return false
        val norm = MontesClarosGeoCatalog.normalize(line)
        if (norm.startsWith("taxa de entrega") ||
            norm.startsWith("tempo de entrega") ||
            norm.startsWith("previsao de entrega") ||
            norm.startsWith("valor da entrega")
        ) {
            return false
        }
        return inlineCustomerMarkerRegexes.any { it.containsMatchIn(line) }
    }

    private fun isAnySectionMarker(line: String): Boolean =
        isStoreMarkerLine(line) || isPickupMarkerLine(line) || isCustomerMarkerLine(line)

    private fun isUiScreenHeaderLine(line: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(line)
        return norm in setOf(
            "detalhes",
            "detalhes da entrega",
            "detalhes da corrida",
            "detalhes do pedido",
            "nova oferta",
            "nova oferta disponivel",
            "oferta disponivel",
            "mob lets",
            "mob lets entregador",
            "moblets"
        )
    }

    private fun isUiFooterCtaLine(line: String): Boolean {
        val norm = MontesClarosGeoCatalog.normalize(line)
        return norm in setOf(
            "aceitar", "aceitar corrida", "aceitar entrega", "recusar", "rejeitar",
            "ignorar", "fechar", "ver rota", "ver mapa"
        )
    }

    private fun isDistanceOrMetaNoise(line: String): Boolean {
        if (distanceOrEtaNoiseRegex.matches(line)) return true
        val norm = MontesClarosGeoCatalog.normalize(line)
        if (norm in uiNoiseExactOrContained) return true
        if (norm.startsWith("pedido ") || norm.startsWith("cod ") || norm.startsWith("codigo ")) return true
        if (norm.startsWith("pagamento") || norm.startsWith("forma de pagamento")) return true
        if (Regex("""^\d+(?:[.,]\d+)?\s*km\b""").containsMatchIn(norm)) return true
        return false
    }

    private fun looksLikePrice(line: String): Boolean =
        standaloneCurrencyRegex.containsMatchIn(line)
}
