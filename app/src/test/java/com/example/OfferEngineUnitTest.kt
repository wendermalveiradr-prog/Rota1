package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.InMemoryGeoCacheStore
import com.example.data.local.AnalyzedOfferEntity
import com.example.data.local.OfferCaptureSource
import com.example.data.local.TrafficLightDecision
import com.example.data.preferences.DriverSettings
import com.example.domain.FieldConfidence
import com.example.domain.GeoPoint
import com.example.domain.GeocoderPlaceMetadata
import com.example.domain.MontesClarosGeoCatalog
import com.example.domain.NeighborhoodConfidence
import com.example.domain.OcrFallbackProcessor
import com.example.domain.OcrSpatialBlock
import com.example.domain.OfferDecisionEngine
import com.example.domain.OfferParser
import com.example.domain.OsrmLeg
import com.example.domain.OsrmRoute
import com.example.domain.OsrmRouteResponse
import com.example.domain.OsrmRoutingApi
import com.example.domain.RecentOfferPriceCache
import com.example.domain.RouteCalculationResult
import com.example.domain.RouteCalculator
import com.example.domain.RouteResolutionResult
import com.example.service.ServiceRuntimeBus
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfferEngineUnitTest {

    @Test
    fun `parser recognizes unseen and never registered stores dynamically without fixed catalog`() {
        val unseenWithMarker = """
            Mob Lets Entregador
            Nova Oferta Disponível
            R$ 18,50
            Loja: Espetinho & Cia Norte Mineiro 777
            Retirada: Av. Deputado Esteves Rodrigues, 450 - Centro
            Entrega: Av. José Corrêa Machado, 920 - Ibituruna
            Aceitar corrida
        """.trimIndent()

        val parsedMarker = OfferParser.parse(unseenWithMarker, requireDeliveryKeywords = true)
        assertNotNull(parsedMarker)
        assertEquals("Espetinho & Cia Norte Mineiro 777", parsedMarker!!.storeName)
        assertEquals(FieldConfidence.HIGH, parsedMarker.storeConfidence)
        assertTrue(parsedMarker.storeNameIdentified)

        // Contextual store line in card header without "Loja:" label
        val unseenContextual = """
            Nova Oferta
            R$ 21,00
            Ateliê Artesanal Flor do Pequi
            Retirada: Av. Dulce Sarmento, 210 - Melo
            Entrega: Rua Santa Lúcia, 340 - Maracanã
            Aceitar
        """.trimIndent()

        val parsedCtx = OfferParser.parse(unseenContextual, requireDeliveryKeywords = true)
        assertNotNull(parsedCtx)
        assertEquals("Ateliê Artesanal Flor do Pequi", parsedCtx!!.storeName)
        assertEquals(FieldConfidence.MEDIUM, parsedCtx.storeConfidence)
        assertTrue(parsedCtx.storeNameIdentified)
    }

    @Test
    fun `parser extracts multiline pickup and customer addresses with explicit neighborhoods`() {
        val multiLineOffer = """
            Nova Oferta
            R$ 22,00
            Retirada
            Confeitaria Estrela Real
            Rua Tupinambás, 210
            Bairro: Melo
            Montes Claros - MG
            Entrega
            Rua Santa Lúcia, 340
            Maracanã
            Aceitar
        """.trimIndent()

        val parsed = OfferParser.parse(multiLineOffer, requireDeliveryKeywords = true)
        assertNotNull(parsed)
        assertEquals("Confeitaria Estrela Real", parsed!!.storeName)
        assertTrue(parsed.pickupAddress.contains("Rua Tupinambás, 210"))
        assertTrue(parsed.pickupAddress.contains("Melo"))
        assertEquals("Melo", parsed.pickupNeighborhoodHint)
        assertEquals(FieldConfidence.HIGH, parsed.pickupAddressConfidence)
        assertEquals(FieldConfidence.HIGH, parsed.pickupNeighborhoodConfidence)

        assertTrue(parsed.customerAddress.contains("Rua Santa Lúcia, 340"))
        assertTrue(parsed.customerAddress.contains("Maracanã"))
        assertEquals(FieldConfidence.HIGH, parsed.customerAddressConfidence)
        assertEquals(FieldConfidence.HIGH, parsed.customerNeighborhoodConfidence)
    }

    @Test
    fun `parser and decision engine mark missing neighborhoods and missing store as Nao identificado`() {
        val addressOnlyOffer = """
            Mob Lets Entregador
            R$ 16,00
            Retirada: Rua Desconhecida Sem Bairro, 120
            Entrega: Travessa Sem Registro, 85
            Aceitar
        """.trimIndent()

        val parsed = OfferParser.parse(addressOnlyOffer, requireDeliveryKeywords = true)
        assertNotNull(parsed)
        assertEquals("", parsed!!.storeName)
        assertFalse(parsed.storeNameIdentified)
        assertEquals(FieldConfidence.UNIDENTIFIED, parsed.storeConfidence)
        assertEquals(FieldConfidence.UNIDENTIFIED, parsed.pickupNeighborhoodConfidence)
        assertEquals(FieldConfidence.UNIDENTIFIED, parsed.customerNeighborhoodConfidence)

        val evaluated = OfferDecisionEngine.evaluateOffer(
            source = OfferCaptureSource.OCR_SCREENSHOT,
            rawSnippet = parsed.rawSnippet,
            offerPriceBrl = parsed.offerPriceBrl,
            storeName = parsed.storeName,
            storeConfidence = parsed.storeConfidence,
            pickupAddress = parsed.pickupAddress,
            pickupAddressConfidence = parsed.pickupAddressConfidence,
            pickupNeighborhoodHint = parsed.pickupNeighborhoodHint,
            customerAddress = parsed.customerAddress,
            customerAddressConfidence = parsed.customerAddressConfidence,
            customerNeighborhoodHint = parsed.customerNeighborhoodHint,
            route = RouteCalculationResult(1.0, 2.5, 2.5, 6.0, RouteCalculator.ROUTE_SOURCE_OSRM),
            usedGpsStart = true,
            neighborhoodRules = MontesClarosGeoCatalog.defaultRuleEntities(),
            settings = DriverSettings(minTargetPerKm = 1.90)
        )

        assertEquals("", evaluated.storeName)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, evaluated.storeConfidence)
        assertEquals(MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD, evaluated.pickupNeighborhood)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, evaluated.pickupNeighborhoodConfidence)
        assertEquals(MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD, evaluated.customerNeighborhood)
        assertEquals(FieldConfidence.UNIDENTIFIED.name, evaluated.customerNeighborhoodConfidence)
        assertTrue(evaluated.decisionReason.contains(MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD))
    }

    @Test
    fun `parser strictly separates store name pickup and delivery blocks without cross contamination`() {
        // 1. "Loja:" accidentally contains a street address -> route to pickupAddress, NOT storeName
        val streetInStoreLabel = """
            Mob Lets Entregador
            R$ 19,00
            Loja: Rua Doutor Veloso, 150 - Centro
            Entrega: Rua Santa Lúcia, 340 - Maracanã
        """.trimIndent()
        val parsedStreetInStore = OfferParser.parse(streetInStoreLabel, requireDeliveryKeywords = true)
        assertNotNull(parsedStreetInStore)
        assertEquals("", parsedStreetInStore!!.storeName)
        assertEquals(FieldConfidence.UNIDENTIFIED, parsedStreetInStore.storeConfidence)
        assertTrue(parsedStreetInStore.pickupAddress.contains("Rua Doutor Veloso"))
        assertFalse(parsedStreetInStore.pickupAddress.contains("Maracanã"))
        assertTrue(parsedStreetInStore.customerAddress.contains("Rua Santa Lúcia"))
        assertFalse(parsedStreetInStore.customerAddress.contains("Veloso"))

        // 2. Pickup has only store name + missing pickup address -> never reuse customer address as pickup
        val storeWithoutPickupAddr = """
            Mob Lets Entregador
            R$ 15,00
            Loja: Mercado Horizonte Novo
            Entrega: Av. José Corrêa Machado, 920 - Ibituruna
        """.trimIndent()
        val parsedStoreOnly = OfferParser.parse(storeWithoutPickupAddr, requireDeliveryKeywords = true)
        assertNotNull(parsedStoreOnly)
        assertEquals("Mercado Horizonte Novo", parsedStoreOnly!!.storeName)
        assertEquals("", parsedStoreOnly.pickupAddress)
        assertEquals(FieldConfidence.UNIDENTIFIED, parsedStoreOnly.pickupAddressConfidence)
        assertTrue(parsedStoreOnly.customerAddress.contains("Ibituruna"))
        assertFalse(parsedStoreOnly.isHighConfidence)
    }

    @Test
    fun `ocr spatial block ordering arranges bounding boxes top to bottom and left to right`() {
        val outOfOrderBlocks = listOf(
            OcrSpatialBlock(lines = listOf("Entrega: Av. José Corrêa Machado, 920 - Ibituruna"), top = 420, left = 40, bottom = 470, right = 900),
            OcrSpatialBlock(lines = listOf("R$ 24,50"), top = 80, left = 650, bottom = 120, right = 920),
            OcrSpatialBlock(lines = listOf("Nova Oferta"), top = 82, left = 40, bottom = 120, right = 350),
            OcrSpatialBlock(lines = listOf("Loja: Padaria Real do Norte"), top = 190, left = 40, bottom = 230, right = 700),
            OcrSpatialBlock(lines = listOf("Retirada: Rua Tupinambás, 210 - Melo"), top = 290, left = 40, bottom = 340, right = 880)
        )

        val orderedText = OcrFallbackProcessor.orderBlocksInReadingOrder(outOfOrderBlocks)
        val parsed = OfferParser.parse(orderedText, requireDeliveryKeywords = true)

        assertNotNull(parsed)
        assertEquals(24.50, parsed!!.offerPriceBrl, 0.01)
        assertEquals("Padaria Real do Norte", parsed.storeName)
        assertEquals(FieldConfidence.HIGH, parsed.storeConfidence)
        assertTrue(parsed.pickupAddress.contains("Melo"))
        assertTrue(parsed.customerAddress.contains("Ibituruna"))
    }

    @Test
    fun `decision engine returns GREEN for high rate in preferred neighborhood`() {
        val result = OfferDecisionEngine.evaluateOffer(
            source = OfferCaptureSource.ACCESSIBILITY,
            rawSnippet = "test",
            offerPriceBrl = 19.00,
            storeName = "Restaurante Bom Prato",
            pickupAddress = "Av. Deputado Esteves Rodrigues, 450 - Centro",
            customerAddress = "Av. José Corrêa Machado, 920 - Ibituruna",
            route = RouteCalculationResult(1.2, 3.1, 3.2, 7.5, RouteCalculator.ROUTE_SOURCE_OSRM),
            usedGpsStart = true,
            neighborhoodRules = MontesClarosGeoCatalog.defaultRuleEntities(),
            settings = DriverSettings(minTargetPerKm = 1.90)
        )

        assertEquals(TrafficLightDecision.GREEN.name, result.decision)
        assertEquals(FieldConfidence.HIGH.name, result.pickupNeighborhoodConfidence)
        assertEquals(FieldConfidence.HIGH.name, result.customerNeighborhoodConfidence)
        assertTrue(result.earningsPerKm >= 2.50)
    }

    @Test
    fun `decision engine returns RED when rate is below R$1_90 per km`() {
        val result = OfferDecisionEngine.evaluateOffer(
            source = OfferCaptureSource.MANUAL,
            rawSnippet = "test",
            offerPriceBrl = 12.00,
            pickupAddress = "Centro, Montes Claros",
            customerAddress = "Maracanã, Montes Claros",
            route = RouteCalculationResult(1.5, 4.0, 4.5, 10.0, RouteCalculator.ROUTE_SOURCE_URBAN_FALLBACK),
            usedGpsStart = false,
            neighborhoodRules = MontesClarosGeoCatalog.defaultRuleEntities(),
            settings = DriverSettings(minTargetPerKm = 1.90)
        )

        assertEquals(TrafficLightDecision.RED.name, result.decision)
        assertEquals(1.20, result.earningsPerKm, 0.01)
    }

    @Test
    fun `decision engine returns RED when destination is an avoided neighborhood`() {
        val result = OfferDecisionEngine.evaluateOffer(
            source = OfferCaptureSource.OCR_SCREENSHOT,
            rawSnippet = "test",
            offerPriceBrl = 35.00,
            pickupAddress = "Centro, Montes Claros",
            customerAddress = "Rua Principal, 150 - Village do Lago",
            route = RouteCalculationResult(1.0, 4.5, 4.5, 10.0, RouteCalculator.ROUTE_SOURCE_OSRM),
            usedGpsStart = true,
            neighborhoodRules = MontesClarosGeoCatalog.defaultRuleEntities(),
            settings = DriverSettings(minTargetPerKm = 1.90)
        )

        assertEquals(TrafficLightDecision.RED.name, result.decision)
        assertNotNull(result.neighborhoodAlert)
    }

    @Test
    fun `neighborhood detector supports Geocoder metadata, avenues, confidence levels, and never invents neighborhoods`() {
        val rules = MontesClarosGeoCatalog.defaultRuleEntities()

        val explicitMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = "Rua Ibituruna, 120 - Centro, Montes Claros - MG, 39400-000",
            point = null,
            rules = rules
        )
        assertEquals("Centro", explicitMatch.name)
        assertEquals(NeighborhoodConfidence.HIGH, explicitMatch.confidence)

        val geocoderMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = "Rua A, 100",
            point = GeoPoint(
                latitude = -16.7385,
                longitude = -43.8832,
                geocoderMetadata = GeocoderPlaceMetadata(subLocality = "Ibituruna")
            ),
            rules = rules
        )
        assertEquals("Ibituruna", geocoderMatch.name)
        assertEquals(NeighborhoodConfidence.HIGH, geocoderMatch.confidence)

        val landmarkMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = "Portaria Principal - Unimontes, Montes Claros",
            point = null,
            rules = rules
        )
        assertEquals("Universitário", landmarkMatch.name)
        assertEquals(NeighborhoodConfidence.MEDIUM, landmarkMatch.confidence)

        val coordMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = "Rua Sem Bairro No Texto, 500",
            point = GeoPoint(-16.6887, -43.8414),
            rules = rules
        )
        assertEquals("Village do Lago", coordMatch.name)
        assertEquals(NeighborhoodConfidence.MEDIUM, coordMatch.confidence)

        val unknownMatch = MontesClarosGeoCatalog.identifyNeighborhood(
            address = "Rua Desconhecida, 99 - Loja XYZ",
            point = null,
            rules = rules
        )
        assertEquals(MontesClarosGeoCatalog.UNIDENTIFIED_NEIGHBORHOOD, unknownMatch.name)
        assertEquals(NeighborhoodConfidence.UNIDENTIFIED, unknownMatch.confidence)
        assertNull(unknownMatch.rule)
    }

    @Test
    fun `route calculator caches OSRM results, isolates caller cancellation, and validates all 4 route points`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val osrmCalls = AtomicInteger(0)
        val fakeOsrm = object : OsrmRoutingApi {
            override suspend fun getDrivingRoute(
                coordinates: String,
                overview: String,
                steps: String,
                alternatives: String,
                annotations: String
            ): OsrmRouteResponse {
                osrmCalls.incrementAndGet()
                delay(80)
                return OsrmRouteResponse(
                    code = "Ok",
                    routes = listOf(
                        OsrmRoute(
                            distance = 6200.0,
                            duration = 700.0,
                            legs = listOf(
                                OsrmLeg(1200.0, 140.0),
                                OsrmLeg(2600.0, 300.0),
                                OsrmLeg(2400.0, 260.0)
                            )
                        )
                    )
                )
            }
        }

        val calculator = RouteCalculator(
            context = context,
            cacheStore = InMemoryGeoCacheStore(),
            osrmApi = fakeOsrm,
            geocoderOverride = { null }
        )

        val start = GeoPoint(-16.7282, -43.8617, "Centro")

        val callerA = launch {
            calculator.calculateCompleteRoute(start, "Centro", "Ibituruna", true)
        }
        val callerB = async {
            calculator.calculateCompleteRoute(start, "Centro", "Ibituruna", true)
        }
        delay(20)
        callerA.cancelAndJoin()
        val resB = callerB.await()

        assertEquals(1, osrmCalls.get())
        assertTrue(resB is RouteResolutionResult.Resolved)
        assertEquals(RouteCalculator.ROUTE_SOURCE_OSRM, (resB as RouteResolutionResult.Resolved).route.routeSource)

        val cachedRes = calculator.calculateCompleteRoute(start, "Centro", "Ibituruna", true)
        assertEquals(1, osrmCalls.get())
        assertTrue(cachedRes is RouteResolutionResult.Resolved)
        val resolvedCached = (cachedRes as RouteResolutionResult.Resolved).route
        assertTrue(resolvedCached.fromCache)
        assertEquals(6.2, resolvedCached.totalKm, 0.01)

        val unknownRes = calculator.calculateCompleteRoute(
            start,
            "Centro",
            "Rua Totalmente Desconhecida XYZ 99999",
            true
        )
        assertTrue(unknownRes is RouteResolutionResult.UnresolvedAddress)
        assertFalse((unknownRes as RouteResolutionResult.UnresolvedAddress).missingPickup)
        assertTrue(unknownRes.missingCustomer)

        val invalidGpsRes = calculator.calculateCompleteRoute(
            GeoPoint(0.0, 0.0, "Invalid"),
            "Centro",
            "Ibituruna",
            true
        )
        assertTrue(invalidGpsRes is RouteResolutionResult.UnresolvedAddress)
    }

    @Test
    fun `service runtime bus isolates runs and blocks stale run overwrites`() {
        ServiceRuntimeBus.clearLatestOffer()
        val run1 = ServiceRuntimeBus.nextRunId()
        val run2 = ServiceRuntimeBus.nextRunId()

        ServiceRuntimeBus.beginRun(run2, "Analisando oferta mais recente")
        assertTrue(ServiceRuntimeBus.state.value.isAnalyzing)
        assertNull(ServiceRuntimeBus.state.value.latestOffer)

        val staleOffer = AnalyzedOfferEntity(
            id = 99,
            source = "ACCESSIBILITY",
            rawSnippet = "stale",
            offerPriceBrl = 10.0,
            pickupAddress = "Centro",
            pickupNeighborhood = "Centro",
            customerAddress = "Melo",
            customerNeighborhood = "Melo",
            leg1CurrentToPickupKm = 1.0,
            leg2PickupToCustomerKm = 2.0,
            leg3CustomerToCenterKm = 2.0,
            totalKm = 5.0,
            earningsPerKm = 2.0,
            targetPerKm = 1.9,
            decision = "GREEN",
            decisionReason = "stale",
            routeSource = "OSRM_REAL_ROADS",
            usedGpsStart = false
        )

        ServiceRuntimeBus.completeRun(run1, staleOffer, "com.moblets")
        assertNull(ServiceRuntimeBus.state.value.latestOffer)
        assertEquals(run2, ServiceRuntimeBus.state.value.activeRunId)
        assertFalse(ServiceRuntimeBus.isRunCurrent(run1))
        assertTrue(ServiceRuntimeBus.isRunCurrent(run2))
    }

    @Test
    fun `recent offer cache combines Offer screen price and total km with Details screen within 2 minutes and expires afterwards`() {
        RecentOfferPriceCache.clear()
        val offerScreenTime = 1_000_000L
        val offerScreenText = """
            Mob Lets Entregador
            Nova Oferta Disponível
            R$ 16,50
            4,20 km
            Retirada: Av. Deputado Esteves Rodrigues, 450 - Centro
            Entrega: Av. José Corrêa Machado, 920 - Ibituruna
        """.trimIndent()

        val parsedOfferScreen = OfferParser.parse(
            rawText = offerScreenText,
            requireDeliveryKeywords = true,
            nowMs = offerScreenTime
        )
        assertNotNull(parsedOfferScreen)
        assertEquals(16.50, parsedOfferScreen!!.offerPriceBrl, 0.01)
        assertEquals(4.20, parsedOfferScreen.screenTotalKm!!, 0.01)
        assertFalse(parsedOfferScreen.usedCachedPrice)

        // Details screen loses R$ and total km, keeping store, "(1,24 km)", pickup, and "1º parada"
        val detailsScreenText = """
            Detalhes da entrega
            Hamburgueria Brasa Norte
            (1,24 km)
            Av. Deputado Esteves Rodrigues, 450 - Centro, Montes Claros - MG
            1º parada
            Av. José Corrêa Machado, 920 - Ibituruna, Montes Claros - MG
        """.trimIndent()

        val parsedDetails = OfferParser.parse(
            rawText = detailsScreenText,
            requireDeliveryKeywords = true,
            nowMs = offerScreenTime + 45_000L
        )
        assertNotNull(parsedDetails)
        assertTrue(parsedDetails!!.isDetailsScreen)
        assertTrue(parsedDetails.usedCachedPrice)
        assertEquals(16.50, parsedDetails.offerPriceBrl, 0.01)
        assertEquals(1.24, parsedDetails.pickupDistanceKm!!, 0.01)
        assertEquals(4.20, parsedDetails.screenTotalKm!!, 0.01)
        assertEquals("Hamburgueria Brasa Norte", parsedDetails.storeName)
        assertTrue(parsedDetails.pickupAddress.contains("Esteves Rodrigues"))
        assertTrue(parsedDetails.customerAddress.contains("Ibituruna"))

        // After 2 minutes (120,000 ms), cached price/km must expire cleanly
        val expiredDetails = OfferParser.parse(
            rawText = detailsScreenText,
            requireDeliveryKeywords = true,
            nowMs = offerScreenTime + 125_000L
        )
        assertNull(expiredDetails)
        RecentOfferPriceCache.clear()
    }

    @Test
    fun `post dash neighborhood normalization matches uppercase accents and city suffix for all required MOC neighborhoods`() {
        val rules = MontesClarosGeoCatalog.defaultRuleEntities()
        val expectedPairs = listOf(
            "Rua Projetada, 120 - JOÃO GORDO, Montes Claros - MG" to "João Gordo",
            "Av. Governador Magalhães Pinto, 500 - ALTO SÃO JOÃO, Montes Claros" to "Alto São João",
            "Rua Santa Maria, 80 - Edgar Pereira, 39400-000" to "Edgar Pereira",
            "Av. Francisco Gaetani, 300 - Funcionários - MG" to "Funcionários",
            "Av. João XXIII, 420 - TODOS OS SANTOS, Montes Claros" to "Todos os Santos",
            "Av. José Corrêa Machado, 920 - Ibituruna, Montes Claros - MG" to "Ibituruna",
            "Av. Deputado Plínio Ribeiro, 710 - Major Prates" to "Major Prates",
            "Rua Santa Lúcia, 340 - MARACANÃ, Montes Claros" to "Maracanã",
            "Rua Principal, 200 - Village do Lago II, Montes Claros - MG" to "Village do Lago",
            "Rua A, 45 - Alterosa, Montes Claros" to "Alterosa",
            "Av. Neco Delfino, 190 - Cidade Industrial - Montes Claros" to "Cidade Industrial",
            "Rua 5, 110 - Clarice Athayde, Montes Claros - MG" to "Clarice Athayde",
            "Rua B, 77 - Conferência Cristo Rei, Montes Claros" to "Conferência Cristo Rei"
        )

        expectedPairs.forEach { (address, expectedNeighborhood) ->
            val match = MontesClarosGeoCatalog.identifyNeighborhood(
                address = address,
                point = null,
                rules = rules
            )
            assertEquals("Falha ao identificar bairro em: $address", expectedNeighborhood, match.name)
            assertEquals("Confiança deve ser ALTA em: $address", NeighborhoodConfidence.HIGH, match.confidence)
            assertNotNull("Coordenadas offline ausentes para: $expectedNeighborhood", MontesClarosGeoCatalog.resolveCoordinatesFromCatalog(address))
        }
    }

    @Test
    fun `instant offline route calculates sub 300ms decision without network and refines safely via OSRM`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calculator = RouteCalculator(
            context = context,
            cacheStore = InMemoryGeoCacheStore(),
            geocoderOverride = { null }
        )

        val startNanos = System.nanoTime()
        val instantRoute = calculator.calculateInstantOfflineRoute(
            currentLocation = MontesClarosGeoCatalog.DOWNTOWN_MONTES_CLAROS,
            pickupAddress = "Av. Deputado Esteves Rodrigues, 450 - Centro",
            customerAddress = "Av. José Corrêa Machado, 920 - Ibituruna",
            pickupDistanceHintKm = 1.24,
            screenTotalKmHint = 4.20
        )
        val evaluated = OfferDecisionEngine.evaluateOffer(
            source = OfferCaptureSource.ACCESSIBILITY,
            rawSnippet = "instant test",
            offerPriceBrl = 18.00,
            storeName = "Lanchonete Instantânea",
            pickupAddress = "Av. Deputado Esteves Rodrigues, 450 - Centro",
            customerAddress = "Av. José Corrêa Machado, 920 - Ibituruna",
            route = instantRoute!!,
            usedGpsStart = false,
            neighborhoodRules = MontesClarosGeoCatalog.defaultRuleEntities(),
            settings = DriverSettings(minTargetPerKm = 1.90)
        )
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        assertEquals(RouteCalculator.ROUTE_SOURCE_INSTANT_OFFLINE, instantRoute.routeSource)
        assertEquals(1.24, instantRoute.leg1CurrentToPickupKm, 0.01)
        assertTrue("Cálculo instantâneo demorou ${elapsedMs}ms (>= 300ms)", elapsedMs < 300L)

        // Ensure completeRun + refineRun preserves instant speed and allows async OSRM update for active run only
        ServiceRuntimeBus.clearLatestOffer()
        val activeRun = ServiceRuntimeBus.nextRunId()
        ServiceRuntimeBus.beginRun(activeRun, "Instantâneo")
        ServiceRuntimeBus.completeRun(activeRun, evaluated, "com.moblets.driver")
        assertEquals(RouteCalculator.ROUTE_SOURCE_INSTANT_OFFLINE, ServiceRuntimeBus.state.value.latestOffer?.routeSource)

        val refined = evaluated.copy(totalKm = 7.4, routeSource = RouteCalculator.ROUTE_SOURCE_OSRM)
        ServiceRuntimeBus.refineRun(activeRun, refined, "com.moblets.driver")
        assertEquals(RouteCalculator.ROUTE_SOURCE_OSRM, ServiceRuntimeBus.state.value.latestOffer?.routeSource)
        assertEquals(7.4, ServiceRuntimeBus.state.value.latestOffer!!.totalKm, 0.01)

        // Stale refinement must be rejected when a newer run started
        val newerRun = ServiceRuntimeBus.nextRunId()
        ServiceRuntimeBus.beginRun(newerRun, "Nova oferta")
        ServiceRuntimeBus.refineRun(activeRun, refined, "com.moblets.driver")
        assertNull(ServiceRuntimeBus.state.value.latestOffer)
    }

    @Test
    fun `service runtime bus auto minimizes overlay outside Mob Lets and expands on Mob Lets foreground`() {
        ServiceRuntimeBus.onForegroundAppChanged("com.moblets.driver", isMobLets = true)
        val initialMinimize = ServiceRuntimeBus.state.value.minimizeNonce
        val initialExpand = ServiceRuntimeBus.state.value.expandNonce
        assertTrue(ServiceRuntimeBus.state.value.isForegroundMobLets)

        // Opening Rota Inteligente IA (or any other non-Mob Lets app) triggers auto-minimize
        ServiceRuntimeBus.onForegroundAppChanged("com.example", isMobLets = false)
        assertFalse(ServiceRuntimeBus.state.value.isForegroundMobLets)
        assertEquals(initialMinimize + 1L, ServiceRuntimeBus.state.value.minimizeNonce)
        assertEquals(initialExpand, ServiceRuntimeBus.state.value.expandNonce)

        // Returning to Mob Lets triggers auto-expand
        ServiceRuntimeBus.onForegroundAppChanged("com.moblets.driver", isMobLets = true)
        assertTrue(ServiceRuntimeBus.state.value.isForegroundMobLets)
        assertEquals(initialExpand + 1L, ServiceRuntimeBus.state.value.expandNonce)
    }
}
