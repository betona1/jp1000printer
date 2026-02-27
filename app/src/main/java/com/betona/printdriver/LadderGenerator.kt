package com.betona.printdriver

import kotlin.random.Random

/**
 * Ladder game (사다리 게임) logic.
 * Generates random horizontal bridges between vertical rails and traces paths.
 */
class LadderGenerator(
    val playerCount: Int,
    val stepCount: Int
) {
    /**
     * bridges[step][rail] = true means there is a bridge from rail to rail+1 at this step.
     * Array dimensions: [stepCount][playerCount - 1]
     */
    val bridges: Array<BooleanArray> = generateBridges()

    private fun generateBridges(): Array<BooleanArray> {
        val result = Array(stepCount) { BooleanArray(playerCount - 1) }
        for (step in 0 until stepCount) {
            for (rail in 0 until playerCount - 1) {
                // Don't place adjacent bridges (prevents ambiguous crossings)
                if (rail > 0 && result[step][rail - 1]) continue
                result[step][rail] = Random.nextFloat() < 0.35f
            }
            // Ensure at least one bridge per step for visual interest
            val hasBridge = result[step].any { it }
            if (!hasBridge) {
                val rail = Random.nextInt(playerCount - 1)
                result[step][rail] = true
            }
        }
        return result
    }

    /**
     * Trace path from a starting rail index to find the destination.
     * Returns the list of (step, rail) coordinates forming the path.
     * step = -1 means starting position (top), step = stepCount means bottom.
     */
    fun tracePath(startRail: Int): List<Pair<Int, Int>> {
        val path = mutableListOf<Pair<Int, Int>>()
        var rail = startRail
        path.add(-1 to rail) // starting position

        for (step in 0 until stepCount) {
            // Check if there's a bridge to the left
            if (rail > 0 && bridges[step][rail - 1]) {
                path.add(step to rail)
                rail--
                path.add(step to rail)
            }
            // Check if there's a bridge to the right
            else if (rail < playerCount - 1 && bridges[step][rail]) {
                path.add(step to rail)
                rail++
                path.add(step to rail)
            } else {
                path.add(step to rail)
            }
        }

        path.add(stepCount to rail) // ending position
        return path
    }

    /**
     * Get the destination rail index for a given starting rail.
     */
    fun getDestination(startRail: Int): Int {
        val path = tracePath(startRail)
        return path.last().second
    }

    companion object {
        const val LENGTH_SHORT = 10
        const val LENGTH_MEDIUM = 20
        const val LENGTH_LONG = 35
    }
}
