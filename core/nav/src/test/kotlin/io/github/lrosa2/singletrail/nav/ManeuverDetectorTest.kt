package io.github.lrosa2.singletrail.nav

import io.github.lrosa2.singletrail.nav.ManeuverKind.SHARP
import io.github.lrosa2.singletrail.nav.ManeuverKind.SLIGHT
import io.github.lrosa2.singletrail.nav.ManeuverKind.SWITCHBACKS
import io.github.lrosa2.singletrail.nav.ManeuverKind.TURN
import io.github.lrosa2.singletrail.nav.ManeuverKind.U_TURN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManeuverDetectorTest {

    private fun detect(points: List<RoutePoint>) = ManeuverDetector.detect(PreparedRoute.prepare(points))

    @Test
    fun `a straight line has no maneuvers`() {
        assertEquals(emptyList<Maneuver>(), detect(polyline(listOf(0.0 to 0.0, 0.0 to 500.0))))
    }

    @Test
    fun `a gentle curve is not a turn`() {
        // A quarter circle of radius 150 m: 236 m of steady turning.
        val points = polyline(listOf(0.0 to -100.0, 0.0 to 0.0)) + arc(150.0, 0.0, 150.0, 180.0, 90.0).drop(1)
        assertEquals(emptyList<Maneuver>(), detect(points))
    }

    @Test
    fun `a right-angle corner is one turn at the corner`() {
        val m = detect(corner(90.0)).single()
        assertEquals(TURN, m.kind)
        assertEquals(90.0, m.angleDeg, 3.0)
        assertEquals(100.0, m.s, 5.0)
        assertEquals(1, m.count)
        assertTrue(Geo.distanceMeters(m.lat, m.lon, at(0, 100).lat, at(0, 100).lon) < 5.0)
    }

    @Test
    fun `a left corner has a negative angle`() {
        val m = detect(corner(-90.0)).single()
        assertEquals(TURN, m.kind)
        assertEquals(-90.0, m.angleDeg, 3.0)
    }

    @Test
    fun `the class follows the size of the turn`() {
        assertEquals(SLIGHT, detect(corner(50.0)).single().kind)
        assertEquals(TURN, detect(corner(90.0)).single().kind)
        assertEquals(SHARP, detect(corner(140.0)).single().kind)
        assertEquals(U_TURN, detect(corner(175.0)).single().kind)
    }

    @Test
    fun `bends under 40 degrees are ignored`() {
        assertEquals(emptyList<Maneuver>(), detect(corner(30.0)))
    }

    @Test
    fun `a square hairpin is one U-turn`() {
        val m = detect(polyline(listOf(0.0 to 0.0, 0.0 to 80.0, 8.0 to 80.0, 8.0 to 0.0), step = 5.0)).single()
        assertEquals(U_TURN, m.kind)
        assertEquals(180.0, m.angleDeg, 3.0)
        assertEquals(84.0, m.s, 6.0)
    }

    @Test
    fun `a tight round bend is one maneuver, not one every few metres`() {
        // Radius 20 m through 180 degrees: the turn is spread over 63 m.
        val points = polyline(listOf(0.0 to -80.0, 0.0 to 0.0)) +
            arc(20.0, 0.0, 20.0, 180.0, 0.0).drop(1) +
            polyline(listOf(40.0 to 0.0, 40.0 to -80.0)).drop(1)
        val m = detect(points).single()
        assertEquals(U_TURN, m.kind)
        assertEquals(180.0, m.angleDeg, 5.0)
    }

    @Test
    fun `an S-bend gives two turns in opposite directions`() {
        val ms = detect(polyline(listOf(0.0 to 0.0, 0.0 to 100.0, 50.0 to 100.0, 50.0 to 200.0)))
        assertEquals(listOf(TURN, TURN), ms.map { it.kind })
        assertEquals(90.0, ms[0].angleDeg, 3.0)
        assertEquals(-90.0, ms[1].angleDeg, 3.0)
        assertEquals(100.0, ms[0].s, 5.0)
        assertEquals(150.0, ms[1].s, 5.0)
    }

    @Test
    fun `two bends close together count as one turn`() {
        // 45 degrees, then another 45 degrees 15 m later.
        val d = 15 / kotlin.math.sqrt(2.0)
        val m = detect(polyline(listOf(0.0 to 0.0, 0.0 to 100.0, d to 100 + d, d + 100 to 100 + d))).single()
        assertEquals(TURN, m.kind)
        assertEquals(90.0, m.angleDeg, 3.0)
    }

    @Test
    fun `alternating hairpins become one switchbacks maneuver`() {
        val ms = detect(zigzag(hairpins = 5))
        val group = ms.single()
        assertEquals(SWITCHBACKS, group.kind)
        assertEquals(5, group.count)
        assertEquals(listOf(true, false, true, false, true), group.parts.map { it.isRight })
        assertEquals(group.parts.first().s, group.s)
        assertEquals(group.parts.last().s, group.endS)
        assertEquals(104.0, group.s, 6.0)
        assertTrue(group.endS - group.s in 180.0..200.0, "span ${group.endS - group.s}")
    }

    @Test
    fun `two hairpins are not switchbacks`() {
        assertEquals(listOf(U_TURN, U_TURN), detect(zigzag(hairpins = 2)).map { it.kind })
    }

    @Test
    fun `hairpins far apart are not grouped`() {
        // 158 m between hairpins: three of them span more than 250 m.
        assertEquals(listOf(U_TURN, U_TURN, U_TURN), detect(zigzag(hairpins = 3, leg = 150.0)).map { it.kind })
    }

    @Test
    fun `a long stack splits into groups spanning at most 250 m`() {
        // 48 m between hairpins: six fit in 250 m, the remaining four form a second group.
        val ms = detect(zigzag(hairpins = 10))
        assertEquals(listOf(SWITCHBACKS, SWITCHBACKS), ms.map { it.kind })
        assertEquals(listOf(6, 4), ms.map { it.count })
        assertTrue(ms.all { it.endS - it.s <= 250.0 })
    }

    @Test
    fun `the reversed route mirrors the maneuvers`() {
        val route = PreparedRoute.prepare(corner(90.0))
        val back = ManeuverDetector.detect(route.reversed()).single()
        assertEquals(TURN, back.kind)
        assertEquals(-90.0, back.angleDeg, 3.0)
        assertEquals(route.lengthMeters - 100.0, back.s, 5.0)
    }
}
