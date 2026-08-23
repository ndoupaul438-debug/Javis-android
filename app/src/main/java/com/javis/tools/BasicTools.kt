package com.javis.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fully offline. */
class GetTimeTool : JavisTool {
    override val name = "get_time"
    override val description = "Reports the current device date and time."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val fmt = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return ToolResult.Success("It's ${fmt.format(Date())}.")
    }
}

/**
 * A deliberately tiny, safe expression evaluator — no external library,
 * no eval-of-arbitrary-code. Supports + - * / and parentheses on numbers only.
 */
class CalculatorTool : JavisTool {
    override val name = "calculator"
    override val description = "Evaluates a basic arithmetic expression."
    override val requiresConfirmation = false
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val raw = arguments["target"]?.trim().orEmpty()
        val expr = extractExpression(raw)
        if (expr.isBlank()) return ToolResult.Failure("I couldn't find a math expression in that.")

        return try {
            val result = SafeExpressionEvaluator.evaluate(expr)
            ToolResult.Success("$expr = $result")
        } catch (e: Exception) {
            ToolResult.Failure("I couldn't calculate that: ${e.message}")
        }
    }

    private fun extractExpression(text: String): String {
        // Keep only digits, operators, parentheses, decimal points, spaces
        val cleaned = text.replace(Regex("(?i)calculate|what'?s|equals|=")," ")
        val match = Regex("[0-9.+\\-*/()\\s]+").findAll(cleaned).maxByOrNull { it.value.length }
        return match?.value?.trim().orEmpty()
    }
}

/** Minimal recursive-descent evaluator for + - * / ( ) on doubles. No eval(), no reflection. */
private object SafeExpressionEvaluator {
    fun evaluate(expression: String): Double {
        val tokens = tokenize(expression)
        val parser = Parser(tokens)
        val result = parser.parseExpression()
        if (parser.hasMore()) throw IllegalArgumentException("Unexpected trailing input")
        return result
    }

    private fun tokenize(expr: String): List<String> {
        val regex = Regex("(\\d+\\.?\\d*|[+\\-*/()])")
        return regex.findAll(expr).map { it.value }.toList()
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0
        fun hasMore() = pos < tokens.size
        private fun peek() = tokens.getOrNull(pos)

        fun parseExpression(): Double {
            var value = parseTerm()
            while (peek() == "+" || peek() == "-") {
                val op = tokens[pos++]
                val rhs = parseTerm()
                value = if (op == "+") value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (peek() == "*" || peek() == "/") {
                val op = tokens[pos++]
                val rhs = parseFactor()
                value = if (op == "*") value * rhs else {
                    if (rhs == 0.0) throw ArithmeticException("division by zero")
                    value / rhs
                }
            }
            return value
        }

        private fun parseFactor(): Double {
            val token = peek() ?: throw IllegalArgumentException("Unexpected end of expression")
            if (token == "(") {
                pos++
                val value = parseExpression()
                if (peek() != ")") throw IllegalArgumentException("Missing closing parenthesis")
                pos++
                return value
            }
            if (token == "-") {
                pos++
                return -parseFactor()
            }
            val number = token.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number: $token")
            pos++
            return number
        }
    }
}
