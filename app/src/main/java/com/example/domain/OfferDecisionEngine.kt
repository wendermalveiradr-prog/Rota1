package com.example.domain

import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.NeighborhoodStatus
import com.example.data.local.NeighborhoodRuleEntity
import com.example.data.local.OfferCaptureSource
import com.example.data.local.TrafficLightDecision
import com.example.data.preferences.DriverSettings
import java.util.Locale

object OfferDecisionEngine {

    fun evaluateOffer(
        source: OfferCaptureSource,
        rawSnippet: String,
        offerPriceBrl: Double,
        storeName: String = "",
        storeConfidence: FieldConfidence? = null,
        pickupAddress: String,
        pickupAddressConfidence: FieldConfidence? = null,
        pickupNeighborhoodHint: String = "",
        customerAddress: String,
        customerAddressConfidence: FieldConfidence? = null,
        customerNeighborhoodHint: String = "",
        route: RouteCalculationResult,
        usedGpsStart: Boolean,
        neighborhoodRules: List<NeighborhoodRuleEntity>,
        settings: DriverSettings
    ): AnalyzedOfferEntity {
        require(route.totalKm > 0.0 && route.totalKm.isFinite()) {
            "Distância total da rota inválida para avaliação."
        }
        val safeTotalKm = route.totalKm.coerceAtLeast(0.5)
        val earningsPerKm = Math.round((offerPriceBrl / safeTotalKm) * 100.0) / 100.0
        val target = settings.minTargetPerKm

        val cleanStoreName = storeName.trim()
            .takeIf {
                it.isNotBlank() &&
                    !it.equals(OfferParser.UNIDENTIFIED_LABEL, ignoreCase = true) &&
                    !OfferParser.looksLikeAddressLine(it)
            }
            .orEmpty()

        val effectiveStoreConfidence = when {
            cleanStoreName.isBlank() -> FieldConfidence.UNIDENTIFIED
            storeConfidence != null && storeConfidence != FieldConfidence.UNIDENTIFIED -> storeConfidence
            else -> FieldConfidence.HIGH
        }

        val effectivePickupAddrConfidence = when {
            pickupAddress.isBlank() -> FieldConfidence.UNIDENTIFIED
            pickupAddressConfidence != null && pickupAddressConfidence != FieldConfidence.UNIDENTIFIED -> pickupAddressConfidence
            else -> FieldConfidence.HIGH
        }

        val effectiveCustomerAddrConfidence = when {
            customerAddress.isBlank() -> FieldConfidence.UNIDENTIFIED
            customerAddressConfidence != null && customerAddressConfidence != FieldConfidence.UNIDENTIFIED -> customerAddressConfidence
            else -> FieldConfidence.HIGH
        }

        val effectivePickupForNeighborhood = if (
            pickupNeighborhoodHint.isNotBlank() &&
            !pickupAddress.contains(pickupNeighborhoodHint, ignoreCase = true)
        ) {
            "$pickupAddress - Bairro $pickupNeighborhoodHint"
        } else {
            pickupAddress
        }

        val effectiveCustomerForNeighborhood = if (
            customerNeighborhoodHint.isNotBlank() &&
            !customerAddress.contains(customerNeighborhoodHint, ignoreCase = true)
        ) {
            "$customerAddress - Bairro $customerNeighborhoodHint"
        } else {
            customerAddress
        }

        val pickupMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = effectivePickupForNeighborhood,
            point = route.pickupPoint,
            rules = neighborhoodRules
        )
        val customerMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = effectiveCustomerForNeighborhood,
            point = route.customerPoint,
            rules = neighborhoodRules
        )

        val pickupRule = pickupMatch.rule
        val customerRule = customerMatch.rule
        val pickupLabel = pickupMatch.name
        val customerLabel = customerMatch.name

        val pickupAvoided = pickupRule?.status == NeighborhoodStatus.AVOIDED.name
        val customerAvoided = customerRule?.status == NeighborhoodStatus.AVOIDED.name
        val customerPreferred = customerRule?.status == NeighborhoodStatus.PREFERRED.name

        val avoidedDetails = buildList {
            if (customerAvoided) {
                val note = customerRule.notes.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                add("Destino evitado: ${customerRule.name}$note")
            }
            if (pickupAvoided && pickupRule.name != customerRule?.name) {
                val note = pickupRule.notes.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                add("Coleta evitada: ${pickupRule.name}$note")
            }
        }
        val neighborhoodAlert = avoidedDetails.joinToString(" • ").ifBlank { null }

        val unidentifiedSuffix = when {
            MontesClarosGeoCatalog.isUnidentified(customerLabel) &&
                MontesClarosGeoCatalog.isUnidentified(pickupLabel) ->
                " • Bairros de retirada e entrega: ${MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD}"
            MontesClarosGeoCatalog.isUnidentified(customerLabel) ->
                " • Bairro de entrega: ${MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD}"
            MontesClarosGeoCatalog.isUnidentified(pickupLabel) ->
                " • Bairro de retirada: ${MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD}"
            else -> ""
        }

        val routeSourceTag = if (route.routeSource == RouteCalculator.ROUTE_SOURCE_OSRM) {
            ""
        } else {
            " • Distância estimada"
        }

        val comfortMargin = target * 1.18
        val isLongReturn = route.leg3CustomerToCenterKm > settings.cautionReturnThresholdKm

        val netProfit = (offerPriceBrl - (route.totalKm * 0.25)).coerceAtLeast(0.0)
        val isBairroEvitado = customerAvoided || pickupAvoided
        val isKmRuim = earningsPerKm < target
        val formattedRate = formatBrl(earningsPerKm)
        val formattedTarget = formatBrl(target)

        val (decision, reason) = when {
            isBairroEvitado && isKmRuim -> {
                TrafficLightDecision.RED to "NÃO COMPENSA • BAIRRO EVITADO ($customerLabel) + KM abaixo da meta ($formattedRate/km)"
            }
            isBairroEvitado -> {
                TrafficLightDecision.RED to "NÃO COMPENSA • BAIRRO EVITADO ($customerLabel) • $formattedRate/km • Lucro ${formatBrl(netProfit)}"
            }
            isKmRuim -> {
                TrafficLightDecision.RED to "NÃO COMPENSA • $formattedRate/km (Meta $formattedTarget) • Lucro ${formatBrl(netProfit)} • Bairro $customerLabel OK"
            }
            else -> {
                TrafficLightDecision.GREEN to "COMPENSA • $formattedRate/km • Lucro ${formatBrl(netProfit)} • Bairro $customerLabel OK"
            }
        }

        

        return AnalyzedOfferEntity(
            timestamp = System.currentTimeMillis(),
            source = source.name,
            rawSnippet = rawSnippet,
            offerPriceBrl = offerPriceBrl,
            storeName = cleanStoreName,
            storeConfidence = effectiveStoreConfidence.name,
            pickupAddress = pickupAddress,
            pickupAddressConfidence = effectivePickupAddrConfidence.name,
            pickupNeighborhood = pickupLabel,
            pickupNeighborhoodConfidence = FieldConfidence.fromNeighborhood(pickupMatch.confidence).name,
            customerAddress = customerAddress,
            customerAddressConfidence = effectiveCustomerAddrConfidence.name,
            customerNeighborhood = customerLabel,
            customerNeighborhoodConfidence = FieldConfidence.fromNeighborhood(customerMatch.confidence).name,
            leg1CurrentToPickupKm = route.leg1CurrentToPickupKm,
            leg2PickupToCustomerKm = route.leg2PickupToCustomerKm,
            leg3CustomerToCenterKm = route.leg3CustomerToCenterKm,
            totalKm = route.totalKm,
            earningsPerKm = earningsPerKm,
            targetPerKm = target,
            decision = decision.name,
            decisionReason = reason,
            neighborhoodAlert = neighborhoodAlert,
            routeSource = route.routeSource,
            usedGpsStart = usedGpsStart
        )
    }

    private fun formatBrl(value: Double): String =
        String.format(Locale("pt", "BR"), "R$ %.2f", value)
}
