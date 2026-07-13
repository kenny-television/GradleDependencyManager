package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.model.UpdateType
import eu.kennytv.dependencymanager.resolve.Versions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VersionsTest {

    @Test
    fun `orders versions with maven semantics`() {
        assertTrue(Versions.isNewer("2.14.1", "2.14.0"))
        assertTrue(Versions.isNewer("4.0.21.Final", "4.0.20.Final"))
        assertTrue(Versions.isNewer("v7.1.0", "v7.0.0"))
        assertFalse(Versions.isNewer("6.1.0", "6.1.0"))
        assertFalse(Versions.isNewer("6.1.0-RC1", "6.1.0"))
        assertEquals("8.5.15", Versions.max(listOf("8.5.9", "8.5.15", "8.5.11")))
    }

    @Test
    fun `stability gate`() {
        assertTrue(Versions.isStable("2.14.0"))
        assertTrue(Versions.isStable("4.0.20.Final"))
        assertTrue(Versions.isStable("33.4.8-jre"))
        assertFalse(Versions.isStable("5-3.3.1-SNAPSHOT"))
        assertFalse(Versions.isStable("6.2.0-RC1"))
        assertFalse(Versions.isStable("9.0.0-M2"))
        assertFalse(Versions.isStable("1.20.4-R0.1-SNAPSHOT"))

        // stable current only accepts stable candidates
        assertFalse(Versions.isAcceptableStability("7.0.0-beta1", "6.0.0"))
        // unstable current accepts anything
        assertTrue(Versions.isAcceptableStability("1.21.0-R0.1-SNAPSHOT", "1.20.4-R0.1-SNAPSHOT"))
    }

    @Test
    fun `update types`() {
        assertEquals(UpdateType.MAJOR, Versions.updateType("v7.0.0", "v8.1.0"))
        assertEquals(UpdateType.MINOR, Versions.updateType("2.14.0", "2.15.2"))
        assertEquals(UpdateType.PATCH, Versions.updateType("9.6.0", "9.6.1"))
        assertEquals(UpdateType.MAJOR, Versions.updateType("v5", "v6"))
        assertEquals(5, Versions.majorOf("v5"))
        assertEquals(4, Versions.majorOf("4.0.20.Final"))
    }

    @Test
    fun `dynamic versions are detected`() {
        assertTrue(Versions.isDynamic("[3.0.0,4.0.0)"))
        assertTrue(Versions.isDynamic("1.+"))
        assertTrue(Versions.isDynamic("latest.release"))
        assertFalse(Versions.isDynamic("1.2.3"))
    }
}
