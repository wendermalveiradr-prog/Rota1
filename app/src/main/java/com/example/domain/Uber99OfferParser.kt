package com.example.domain

object Uber99OfferParser {

    private val priceRegex = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*[.,]\d{2}|\d+[.,]\d{2})""", RegexOption.IGNORE_CASE)
    private val etaAndKmRegex = Regex("""(\d{1,3})\s*min\s*\((\d+(?:[.,]\d+)?)\s*(km|m)\)""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String, pkg: String): ParsedOfferDraft? {
        val cleanLines = rawText.lines()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && it.length >= 2 }

        if (cleanLines.isEmpty()) return null
        val joined = cleanLines.joinToString("\n")

        val priceMatch = priceRegex.find(joined) ?: return null
        val rawNum = priceMatch.groupValues.getOrNull(1) ?: return null
        val normalizedPrice = if (rawNum.contains(",") && rawNum.contains(".")) {
            rawNum.replace(".", "").replace(",", ".")
        } else {
            rawNum.replace(",", ".")
        }
        val price = normalizedPrice.toDoubleOrNull() ?: return null
        if (price <= 1.0 || price > 1500.0) return null

        // Na Uber e na 99, cada etapa vem como "4 min (1,2 km) • Endereço" ou seguida pelo endereço na linha abaixo
        val legIndices = cleanLines.mapIndexedNotNull { idx, line ->
            if (etaAndKmRegex.containsMatchIn(line)) idx else null
        }

        if (legIndices.size < 2) return null

        val pickupIdx = legIndices[0]
        val dropoffIdx = legIndices[1]

        val pickupKm = extractKmFromLine(cleanLines[pickupIdx])
        val tripKm = extractKmFromLine(cleanLines[dropoffIdx])

        val pickupAddr = extractAddressFromLeg(cleanLines, pickupIdx, dropoffIdx)
            .ifBlank { "Embarque em Montes Claros - MG" }

        val dropoffAddr = extractAddressFromLeg(cleanLines, dropoffIdx, cleanLines.size)
            .ifBlank { "Destino em Montes Claros - MG" }

        val appLabel = if (pkg.contains("uber", ignoreCase = true)) "Uber" else "99"

        return ParsedOfferDraft(
            offerPriceBrl = price,
            storeName = appLabel,
            storeConfidence = FieldConfidence.HIGH,
            storeNameIdentified = true,
            pickupAddress = pickupAddr.take(180),
            pickupAddressConfidence = FieldConfidence.HIGH,
            pickupNeighborhoodHint = "",
            pickupNeighborhoodConfidence = FieldConfidence.MEDIUM,
            customerAddress = dropoffAddr.take(180),
            customerAddressConfidence = FieldConfidence.HIGH,
            customerNeighborhoodHint = "",
            customerNeighborhoodConfidence = FieldConfidence.MEDIUM,
            rawSnippet = joined.take(500),
            isHighConfidence = true,
            pickupDistanceKm = pickupKm,
            screenTotalKm = tripKm,
            isDetailsScreen = false,
            usedCachedPrice = false
        )
    }

    private fun extractKmFromLine(line: String): Double? {
        val m = etaAndKmRegex.find(line) ?: return null
        val num = m.groupValues.getOrNull(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
        val unit = m.groupValues.getOrNull(3)?.lowercase() ?: "km"
        val km = if (unit == "m") num / 1000.0 else num
        return km.takeIf { it > 0.0 && it < 150.0 }
    }

    private fun extractAddressFromLeg(lines: List<String>, startIdx: Int, limitIdx: Int): String {
        val currentLine = lines[startIdx]
        val cleanedCurrent = etaAndKmRegex.replace(currentLine, "")
            .replace(Regex("(?i)^\\s*(?:de\\s+(?:distância|distancia|viagem)|até\\s+o\\s+local)\\s*"), "")
            .trim(' ', '-', '•', ':', '|', ',')

        if (cleanedCurrent.length >= 6 && !cleanedCurrent.equals("de distância", ignoreCase = true)) {
            return cleanedCurrent
        }

        // Se o endereço estiver na linha logo abaixo de "4 min (1,2 km)"
        for (i in (startIdx + 1) until minOf(startIdx + 3, limitIdx)) {
            val next = lines[i].trim()
            if (next.length >= 6 && !etaAndKmRegex.containsMatchIn(next) && !next.contains("R$")) {
                return next
            }
        }
        return ""
    }
}
