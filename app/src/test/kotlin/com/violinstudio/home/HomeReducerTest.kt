package com.violinstudio.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HomeReducerTest {
    @Test
    fun `Loading pasa a estado Loading desde cualquier estado`() {
        assertEquals(HomeState(HealthStatus.Loading), HomeReducer.reduce(HomeState(), HomeMutation.Loading))
        assertEquals(
            HomeState(HealthStatus.Loading),
            HomeReducer.reduce(HomeState(HealthStatus.Error("x")), HomeMutation.Loading)
        )
    }

    @Test
    fun `Loaded guarda la versión`() {
        assertEquals(
            HomeState(HealthStatus.Ok("0.1.0")),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Loaded("0.1.0"))
        )
    }

    @Test
    fun `Failed guarda el mensaje aunque sea null`() {
        assertEquals(
            HomeState(HealthStatus.Error("sin red")),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Failed("sin red"))
        )
        assertEquals(
            HomeState(HealthStatus.Error(null)),
            HomeReducer.reduce(HomeState(HealthStatus.Loading), HomeMutation.Failed(null))
        )
    }
}
