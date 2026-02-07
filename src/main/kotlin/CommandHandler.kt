import java.math.BigDecimal
import java.math.RoundingMode

class CommandHandler(private val database: DatabaseOperations) {

    fun handleAddExpense(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 3) {
            return "❌ Usage: /addexpense [name] [amount]\nExample: /addexpense John 50"
        }

        val name = parts[1]
        val amount = parts[2].toBigDecimalOrNull()

        if (amount == null || amount <= BigDecimal.ZERO) {
            return "❌ Please provide a valid positive amount"
        }

        database.addExpense(chatId, name, amount)

        // Auto-add as participant if not already added
        database.addParticipant(chatId, name)

        return "✅ Added expense: $name spent €${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    fun handleAddParticipant(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 2) {
            return "❌ Usage: /addparticipant [name]\nExample: /addparticipant Alice"
        }

        val name = parts[1]
        val participant = database.addParticipant(chatId, name)

        return if (participant != null) {
            "✅ Added $name to participants list"
        } else {
            "⚠️ $name is already in the participants list"
        }
    }

    fun handleRemoveParticipant(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 2) {
            return "❌ Usage: /removeparticipant [name]\nExample: /removeparticipant Alice"
        }

        val name = parts[1]
        val removed = database.removeParticipant(chatId, name)

        return if (removed) {
            "✅ Removed $name from participants list"
        } else {
            "❌ $name is not in the participants list"
        }
    }

    fun handleListParticipants(chatId: Long): String {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            return "📋 No participants yet.\nUse /addparticipant to add people."
        }

        val list = buildString {
            appendLine("📋 Participants (${participants.size}):")
            appendLine()
            participants.forEach {
                appendLine("👤 ${it.name}")
            }
        }
        return list
    }

    fun handleStatus(chatId: Long): String {
        val expenses = database.getExpenses(chatId)

        if (expenses.isEmpty()) {
            return "📊 No expenses recorded yet.\nUse /addexpense to add expenses."
        }

        val total = expenses.sumOf { it.amount }
        val status = buildString {
            appendLine("📊 Current Status:")
            appendLine()
            expenses.forEach {
                appendLine("💰 ${it.buyerName}: €${it.amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Total spent: €${total.setScale(2, RoundingMode.HALF_UP)}")
        }
        return status
    }

    fun handleCalculate(chatId: Long): String {
        val expenses = database.getExpenses(chatId)
        val participants = database.getParticipants(chatId)

        if (expenses.isEmpty()) {
            return "❌ No expenses to calculate. Add expenses first with /addexpense"
        }

        if (participants.isEmpty()) {
            return "❌ No participants added. Add participants with /addparticipant"
        }

        val total = expenses.sumOf { it.amount }
        val peopleCount = participants.size
        val perPerson = total.divide(BigDecimal(peopleCount), 2, RoundingMode.HALF_UP)

        // Calculate how much each person spent
        val spent = mutableMapOf<String, BigDecimal>()
        participants.forEach { spent[it.name] = BigDecimal.ZERO }
        expenses.forEach { expense ->
            spent[expense.buyerName] = (spent[expense.buyerName] ?: BigDecimal.ZERO) + expense.amount
        }

        // Calculate balances (positive = should receive, negative = should pay)
        val balances = spent.mapValues { (_, spentAmount) -> spentAmount - perPerson }

        val result = buildString {
            appendLine("💵 Payment Calculation:")
            appendLine()
            appendLine("Total: €${total.setScale(2, RoundingMode.HALF_UP)}")
            appendLine("Participants: $peopleCount")
            appendLine("Per person: €${perPerson.setScale(2, RoundingMode.HALF_UP)}")
            appendLine()

            val owes = balances.filter { it.value < BigDecimal.ZERO }
            val receives = balances.filter { it.value > BigDecimal.ZERO }

            if (owes.isEmpty()) {
                appendLine("✅ Everyone is settled up!")
            } else {
                appendLine("💸 Who owes money:")
                owes.forEach { (name, amount) ->
                    appendLine("   ${name}: €${amount.abs().setScale(2, RoundingMode.HALF_UP)}")
                }
                appendLine()
                appendLine("💰 Who should receive:")
                receives.forEach { (name, amount) ->
                    appendLine("   ${name}: €${amount.setScale(2, RoundingMode.HALF_UP)}")
                }
            }
        }

        return result
    }

    fun handleNotify(chatId: Long): String {
        val expenses = database.getExpenses(chatId)
        val participants = database.getParticipants(chatId)

        if (expenses.isEmpty()) {
            return "❌ No expenses to notify about. Add expenses first."
        }

        if (participants.isEmpty()) {
            return "❌ No participants added. Add participants with /addparticipant"
        }

        val total = expenses.sumOf { it.amount }
        val peopleCount = participants.size
        val perPerson = total.divide(BigDecimal(peopleCount), 2, RoundingMode.HALF_UP)

        val spent = mutableMapOf<String, BigDecimal>()
        participants.forEach { spent[it.name] = BigDecimal.ZERO }
        expenses.forEach { expense ->
            spent[expense.buyerName] = (spent[expense.buyerName] ?: BigDecimal.ZERO) + expense.amount
        }

        val balances = spent.mapValues { (_, spentAmount) -> spentAmount - perPerson }
        val owes = balances.filter { it.value < BigDecimal.ZERO }

        if (owes.isEmpty()) {
            return "✅ No one owes money!"
        }

        val notification = buildString {
            appendLine("🔔 Payment Reminder!")
            appendLine()
            owes.forEach { (name, amount) ->
                appendLine("${name} please transfer €${amount.abs().setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Use /calculate to see full breakdown")
        }

        return notification
    }

    fun getHelpMessage(): String {
        return """
            🎉 Birthday Gift Bot

            Participant Commands:
            /addparticipant [name] - Add someone to the group
            /removeparticipant [name] - Remove someone
            /participants - List all participants

            Expense Commands:
            /addexpense [name] [amount] - Record an expense
            /status - View all expenses
            /calculate - Calculate who owes what
            /notify - Send payment reminders

            Other:
            /reset - Clear all data
            /help - Show this message

            Example workflow:
            1. /addparticipant Alice
            2. /addparticipant Bob
            3. /addparticipant Charlie
            4. /addexpense Alice 60
            5. /addexpense Bob 40
            6. /calculate

            💡 This bot works independently in each group!
        """.trimIndent()
    }
}
