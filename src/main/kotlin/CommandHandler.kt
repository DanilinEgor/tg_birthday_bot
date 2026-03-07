import java.math.BigDecimal
import java.math.RoundingMode

class CommandHandler(private val database: DatabaseOperations) {

    private fun normalizeUsername(name: String): String =
        if (name.startsWith("@")) name else "@$name"

    fun handleAddExpense(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 3) {
            return "❌ Формат: /addexpense [@юзернейм] [сумма]\nПример: /addexpense @Ivan 50"
        }

        val name = normalizeUsername(parts[1])
        val amount = parts[2].toBigDecimalOrNull()

        if (amount == null || amount <= BigDecimal.ZERO) {
            return "❌ Укажите корректную положительную сумму"
        }

        database.addExpense(chatId, name, amount)

        // Auto-add as participant if not already added
        database.addParticipant(chatId, name)

        return "✅ Расход добавлен: $name потратил(а) €${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    fun handleAddParticipant(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 2) {
            return "❌ Формат: /add @юзер1 @юзер2 ...\nПример: /add @Alice @Bob @Charlie"
        }

        val names = parts.drop(1).map { normalizeUsername(it) }
        if (names.size == 1) {
            val name = names[0]
            val participant = database.addParticipant(chatId, name)
            return if (participant != null) {
                "✅ $name добавлен(а) в список участников"
            } else {
                "⚠️ $name уже в списке участников"
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
            if (added.isNotEmpty()) append("✅ Добавлены: ${added.joinToString(", ")}")
            if (added.isNotEmpty() && existed.isNotEmpty()) append("\n")
            if (existed.isNotEmpty()) append("⚠️ Уже были: ${existed.joinToString(", ")}")
        }
    }

    fun handleRemoveParticipant(chatId: Long, text: String): String {
        val parts = text.split(" ")
        if (parts.size < 2) {
            return "❌ Формат: /removeparticipant [@юзернейм]\nПример: /removeparticipant @Alice"
        }

        val name = normalizeUsername(parts[1])
        val removed = database.removeParticipant(chatId, name)

        return if (removed) {
            "✅ $name удалён из списка участников"
        } else {
            "❌ $name нет в списке участников"
        }
    }

    fun handleListParticipants(chatId: Long): String {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            return "📋 Участников пока нет.\nИспользуй /add для добавления."
        }

        val list = buildString {
            appendLine("📋 Участники (${participants.size}):")
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
            return "📊 Расходов пока нет.\nИспользуй /addexpense для добавления."
        }

        val total = expenses.sumOf { it.amount }
        val status = buildString {
            appendLine("📊 Текущие расходы:")
            appendLine()
            expenses.forEach {
                appendLine("💰 ${it.buyerName}: €${it.amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Итого: €${total.setScale(2, RoundingMode.HALF_UP)}")
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
            return "❌ Нет расходов для расчёта. Сначала добавь через /addexpense"
        }

        if (participants.isEmpty()) {
            return "❌ Нет участников. Добавь через /add"
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
            appendLine("💵 Расчёт:")
            appendLine()
            appendLine("Итого: €${total.setScale(2, RoundingMode.HALF_UP)}")
            appendLine("Участников: $peopleCount")
            appendLine("На каждого: €${perPerson.setScale(2, RoundingMode.HALF_UP)}")
            appendLine()

            // Greedy settlement based on adjusted balances
            val debtors = adjustedBalances.filter { it.value < BigDecimal.ZERO }
                .map { it.key to it.value.abs() }.sortedByDescending { it.second }.toMutableList()
            val creditors = adjustedBalances.filter { it.value > BigDecimal.ZERO }
                .map { it.key to it.value }.sortedByDescending { it.second }.toMutableList()

            // Show paid debts
            val paidNames = rawBalances.filter { it.value < BigDecimal.ZERO }
                .filter { (name, _) -> (adjustedBalances[name] ?: BigDecimal.ZERO) >= BigDecimal.ZERO }
                .map { it.key }

            if (debtors.isEmpty() && paidNames.isEmpty()) {
                appendLine("✅ Все в расчёте!")
            } else if (debtors.isEmpty() && paidNames.isNotEmpty()) {
                paidNames.forEach { name ->
                    val rawDebt = rawBalances[name]!!.abs()
                    appendLine("✅ $name: €${rawDebt.setScale(2, RoundingMode.HALF_UP)} (оплачено)")
                }
            } else {
                appendLine("💸 Кто кому переводит:")
                var di = 0
                var ci = 0
                val debtorAmounts = debtors.map { it.second }.toMutableList()
                val creditorAmounts = creditors.map { it.second }.toMutableList()

                while (di < debtors.size && ci < creditors.size) {
                    val amount = debtorAmounts[di].min(creditorAmounts[ci])
                    appendLine("   ${debtors[di].first} → ${creditors[ci].first}: €${amount.setScale(2, RoundingMode.HALF_UP)}")
                    debtorAmounts[di] = debtorAmounts[di] - amount
                    creditorAmounts[ci] = creditorAmounts[ci] - amount
                    if (debtorAmounts[di].compareTo(BigDecimal.ZERO) == 0) di++
                    if (creditorAmounts[ci].compareTo(BigDecimal.ZERO) == 0) ci++
                }

                if (paidNames.isNotEmpty()) {
                    appendLine()
                    paidNames.forEach { name ->
                        val rawDebt = rawBalances[name]!!.abs()
                        appendLine("✅ $name: €${rawDebt.setScale(2, RoundingMode.HALF_UP)} (оплачено)")
                    }
                }
            }
        }

        return result
    }

    fun handleNotify(chatId: Long): String {
        val expenses = database.getExpenses(chatId)
        val participants = database.getParticipants(chatId)

        if (expenses.isEmpty()) {
            return "❌ Нет расходов. Сначала добавь расходы."
        }

        if (participants.isEmpty()) {
            return "❌ Нет участников. Добавь через /add"
        }

        val unpaidDebts = getUnpaidDebts(chatId)

        if (unpaidDebts.isEmpty()) {
            return "✅ Все долги оплачены!"
        }

        val paymentInfo = database.getPaymentInfo(chatId)

        val notification = buildString {
            appendLine("🔔 Напоминание об оплате!")
            appendLine()
            unpaidDebts.forEach { (name, amount) ->
                appendLine("$name, переведи €${amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            if (paymentInfo != null) {
                appendLine()
                appendLine("💳 $paymentInfo")
            }
            appendLine()
            appendLine("/calculate — подробный расчёт")
        }

        return notification
    }

    fun handleSetPayment(chatId: Long, text: String): String {
        val info = text.substringAfter(" ", "").trim()
        if (info.isEmpty()) {
            return "❌ Формат: /setpayment [номер карты или телефон]\nПример: /setpayment Карта: 1234 5678 9012 3456"
        }
        database.setPaymentInfo(chatId, info)
        return "✅ Реквизиты сохранены: $info"
    }

    fun handleGetPayment(chatId: Long): String {
        val info = database.getPaymentInfo(chatId)
        return if (info != null) {
            "💳 Реквизиты для перевода:\n$info"
        } else {
            "❌ Реквизиты не заданы. Установи через /setpayment"
        }
    }

    fun getHelpMessage(): String {
        return """
            🎉 Бот для совместных расходов

            Как пользоваться:
            1. Добавь участников: /add @user1 @user2 ...
            2. Нажми 💰 Добавить расход и выбери, кто платил
            3. Посмотри расчёт через 💵 Расчёт

            Команды:
            /add — добавить участников
            /addexpense — добавить расход
            /participants — список участников
            /status — все расходы
            /calculate — расчёт долгов
            /notify — напомнить должникам
            /setpayment — установить реквизиты для перевода
            /getpayment — показать реквизиты
            /reset — очистить все данные

            💡 Используй кнопки ниже!
        """.trimIndent()
    }
}
