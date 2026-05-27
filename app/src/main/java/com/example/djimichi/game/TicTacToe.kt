package com.example.djimichi.game

enum class Cell { EMPTY, HUMAN, DRONE }
enum class GameStatus { ONGOING, HUMAN_WINS, DRONE_WINS, DRAW }

class TicTacToe {
    private val board = Array(9) { Cell.EMPTY }
    private var currentStatus = GameStatus.ONGOING

    fun state(): Array<Cell> = board.clone()
    fun status(): GameStatus = currentStatus

    fun playHuman(index: Int): Boolean {
        if (index !in 0..8 || board[index] != Cell.EMPTY || currentStatus != GameStatus.ONGOING) return false
        board[index] = Cell.HUMAN
        updateGameStatus()
        return true
    }

    fun playDrone(): Int {
        if (currentStatus != GameStatus.ONGOING) return -1
        val bestMove = minimax(board, Cell.DRONE).index
        if (bestMove != -1) {
            board[bestMove] = Cell.DRONE
            updateGameStatus()
        }
        return bestMove
    }

    private fun updateGameStatus() {
        currentStatus = checkStatus(board)
    }

    private fun checkStatus(b: Array<Cell>): GameStatus {
        val winPositions = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), // Filas
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), // Columnas
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)                      // Diagonales
        )
        for (pos in winPositions) {
            if (b[pos[0]] != Cell.EMPTY && b[pos[0]] == b[pos[1]] && b[pos[0]] == b[pos[2]]) {
                return if (b[pos[0]] == Cell.HUMAN) GameStatus.HUMAN_WINS else GameStatus.DRONE_WINS
            }
        }
        if (b.none { it == Cell.EMPTY }) return GameStatus.DRAW
        return GameStatus.ONGOING
    }

    private data class Move(val index: Int, val score: Int)

    private fun minimax(b: Array<Cell>, player: Cell): Move {
        val status = checkStatus(b)
        if (status == GameStatus.HUMAN_WINS) return Move(-1, -10)
        if (status == GameStatus.DRONE_WINS) return Move(-1, 10)
        if (status == GameStatus.DRAW) return Move(-1, 0)

        val moves = mutableListOf<Move>()
        for (i in 0..8) {
            if (b[i] == Cell.EMPTY) {
                b[i] = player
                val score = if (player == Cell.DRONE) {
                    minimax(b, Cell.HUMAN).score
                } else {
                    minimax(b, Cell.DRONE).score
                }
                b[i] = Cell.EMPTY
                moves.add(Move(i, score))
            }
        }

        return if (player == Cell.DRONE) {
            moves.maxByOrNull { it.score } ?: Move(-1, -100)
        } else {
            moves.minByOrNull { it.score } ?: Move(-1, 100)
        }
    }
}