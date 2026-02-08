import java.math.BigDecimal
import java.math.RoundingMode

class CommandHandler(private val database: DatabaseOperations) {

    private fun normalizeUsername(name: String): String =
        if (name.startsWith("@")) name else "@$name"

    fun handleAddExpense(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 3) {
            return "❌ Usage: /addexpense [@username] [amount]\nExample: /addexpense @John 50"
        }

        val name = normalizeUsername(parts[1])
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
            return "❌ Usage: /add [@username1] [@username2] ...\nExample: /add @Alice @Bob @Charlie"
        }

        val names = parts.drop(1).map { normalizeUsername(it) }
        if (names.size == 1) {
            val name = names[0]
            val participant = database.addParticipant(chatId, name)
            return if (participant != null) {
                "✅ Added $name to participants list"
            } else {
                "⚠️ $name is already in the participants list"
            }
        }

        val added = mutableListOf<String>()
        val existed = mutableListOf<String>()
        for (name in names) {
            if (database.addParticipant(chatId, name) != null) {
                added.add(name)
            } else {
                existed.add(name)
            }
        }

        return buildString {
            if (added.isNotEmpty()) append("✅ Added: ${added.joinToString(", ")}")
            if (added.isNotEmpty() && existed.isNotEmpty()) append("\n")
            if (existed.isNotEmpty()) append("⚠️ Already existed: ${existed.joinToString(", ")}")
        }
    }

    fun handleRemoveParticipant(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 2) {
            return "❌ Usage: /removeparticipant [@username]\nExample: /removeparticipant @Alice"
        }

        val name = normalizeUsername(parts[1])
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
            return "📋 No participants yet.\nUse /add to add people."
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

    fun getUnpaidDebts(chatId: Long): Map<String, BigDecimal> {
        val expenses = database.getExpenses(chatId)
        val participants = database.getParticipants(chatId)
        if (expenses.isEmpty() || participants.isEmpty()) return emptyMap()

        val total = expenses.sumOf { it.amount }
        val perPerson = total.divide(BigDecimal(participants.size), 2, RoundingMode.HALF_UP)

        val spent = mutableMapOf<String, BigDecimal>()
        participants.forEach { spent[it.name] = BigDecimal.ZERO }
        expenses.forEach { expense ->
            spent[expense.buyerName] = (spent[expense.buyerName] ?: BigDecimal.ZERO) + expense.amount
        }

        val balances = spent.mapValues { (_, spentAmount) -> spentAmount - perPerson }
        val paidAmounts = database.getPaidDebts(chatId)

        return balances
            .mapValues { (name, balance) -> balance + (paidAmounts[name] ?: BigDecimal.ZERO) }
            .filter { it.value < BigDecimal.ZERO }
            .mapValues { it.value.abs() }
    }

    fun handleCalculate(chatId: Long): String {
        val expenses = database.getExpenses(chatId)
        val participants = database.getParticipants(chatId)

        if (expenses.isEmpty()) {
            return "❌ No expenses to calculate. Add expenses first with /addexpense"
        }

        if (participants.isEmpty()) {
            return "❌ No participants added. Add participants with /add"
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
        val rawBalances = spent.mapValues { (_, spentAmount) -> spentAmount - perPerson }
        val paidAmounts = database.getPaidDebts(chatId)
        val adjustedBalances = rawBalances.mapValues { (name, balance) ->
            balance + (paidAmounts[name] ?: BigDecimal.ZERO)
        }

        val result = buildString {
            appendLine("💵 Payment Calculation:")
            appendLine()
            appendLine("Total: €${total.setScale(2, RoundingMode.HALF_UP)}")
            appendLine("Participants: $peopleCount")
            appendLine("Per person: €${perPerson.setScale(2, RoundingMode.HALF_UP)}")
            appendLine()

            val rawOwes = rawBalances.filter { it.value < BigDecimal.ZERO }
            val receives = rawBalances.filter { it.value > BigDecimal.ZERO }

            if (rawOwes.isEmpty()) {
                appendLine("✅ Everyone is settled up!")
            } else {
                appendLine("💸 Who owes money:")
                rawOwes.forEach { (name, amount) ->
                    val adjusted = adjustedBalances[name] ?: amount
                    if (adjusted >= BigDecimal.ZERO) {
                        appendLine("   ✅ ${name}: €${amount.abs().setScale(2, RoundingMode.HALF_UP)} (paid)")
                    } else {
                        appendLine("   ${name}: €${adjusted.abs().setScale(2, RoundingMode.HALF_UP)}")
                    }
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
            return "❌ No participants added. Add participants with /add"
        }

        val unpaidDebts = getUnpaidDebts(chatId)

        if (unpaidDebts.isEmpty()) {
            return "✅ No one owes money!"
        }

        val paymentInfo = database.getPaymentInfo(chatId)

        val notification = buildString {
            appendLine("🔔 Payment Reminder!")
            appendLine()
            unpaidDebts.forEach { (name, amount) ->
                appendLine("${name} please transfer €${amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            if (paymentInfo != null) {
                appendLine()
                appendLine("💳 $paymentInfo")
            }
            appendLine()
            appendLine("Use /calculate to see full breakdown")
        }

        return notification
    }

    fun handleSetPayment(chatId: Long, text: String): String {
        val info = text.substringAfter(" ", "").trim()
        if (info.isEmpty()) {
            return "❌ Usage: /setpayment [card number or phone]\nExample: /setpayment Card: 1234 5678 9012 3456"
        }
        database.setPaymentInfo(chatId, info)
        return "✅ Payment info saved: $info"
    }

    fun getHelpMessage(): String {
        return """
            🎉 Birthday Gift Bot

            /add [@usernames] - Add people (e.g. @Alice @Bob)
            /addexpense [@username] [amount] - Record an expense
            /participants - List all participants
            /status - View all expenses
            /calculate - Calculate who owes what
            /notify - Send payment reminders
            /setpayment [info] - Set payment details for reminders
            /reset - Clear all data

            💡 Use the buttons below or type commands!
        """.trimIndent()
    }
}
