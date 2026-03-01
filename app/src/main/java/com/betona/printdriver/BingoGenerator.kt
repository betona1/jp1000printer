package com.betona.printdriver

/**
 * Bingo game logic: card generation, number drawing, bingo line counting.
 */
class BingoGenerator(
    val gridSize: Int,
    val numberRange: IntRange,
    val playerCount: Int
) {
    val cards: List<List<Int>>
    val drawnNumbers = mutableListOf<Int>()
    val availableNumbers: MutableList<Int>

    init {
        require(gridSize in 3..5)
        require(playerCount in 1..30)
        require(numberRange.count() >= gridSize * gridSize)

        availableNumbers = numberRange.toMutableList()
        cards = generateCards()
    }

    private fun generateCards(): List<List<Int>> {
        val cellCount = gridSize * gridSize
        val pool = numberRange.toList()
        return List(playerCount) {
            pool.shuffled().take(cellCount)
        }
    }

    /** Draw a random number. Returns null if all numbers exhausted. */
    fun drawNumber(): Int? {
        if (availableNumbers.isEmpty()) return null
        val idx = availableNumbers.indices.random()
        val number = availableNumbers.removeAt(idx)
        drawnNumbers.add(number)
        return number
    }

    /** Manually draw a specific number. Returns false if not available. */
    fun drawSpecificNumber(number: Int): Boolean {
        val removed = availableNumbers.remove(number)
        if (removed) drawnNumbers.add(number)
        return removed
    }

    /** Check if a cell on a card has been drawn. */
    fun isMarked(cardIndex: Int, position: Int): Boolean {
        val number = cards[cardIndex][position]
        return number in drawnNumbers
    }

    /** Count completed bingo lines (rows + columns + diagonals). */
    fun countBingoLines(cardIndex: Int): Int {
        return getCompletedLines(cardIndex).size
    }

    /**
     * Get list of completed lines for a card.
     * Each line is a list of cell indices that form the completed line.
     */
    fun getCompletedLines(cardIndex: Int): List<List<Int>> {
        val card = cards[cardIndex]
        val drawn = drawnNumbers.toSet()
        val lines = mutableListOf<List<Int>>()

        // Rows
        for (row in 0 until gridSize) {
            val cells = (0 until gridSize).map { col -> row * gridSize + col }
            if (cells.all { card[it] in drawn }) lines.add(cells)
        }

        // Columns
        for (col in 0 until gridSize) {
            val cells = (0 until gridSize).map { row -> row * gridSize + col }
            if (cells.all { card[it] in drawn }) lines.add(cells)
        }

        // Diagonal top-left to bottom-right
        val diag1 = (0 until gridSize).map { i -> i * gridSize + i }
        if (diag1.all { card[it] in drawn }) lines.add(diag1)

        // Diagonal top-right to bottom-left
        val diag2 = (0 until gridSize).map { i -> i * gridSize + (gridSize - 1 - i) }
        if (diag2.all { card[it] in drawn }) lines.add(diag2)

        return lines
    }

    /** Get card indices that have achieved the required number of bingo lines. */
    fun getWinners(requiredLines: Int): List<Int> {
        return cards.indices.filter { countBingoLines(it) >= requiredLines }
    }
}
