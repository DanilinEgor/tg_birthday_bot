import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.math.BigDecimal
import java.math.RoundingMode

data class Expense(val buyerName: String, val amount: BigDecimal)

class BirthdayBot(private val botToken: String, private val botUsername: String) : TelegramLongPollingBot() {

    private val expenses = mutableListOf<Expense>()

    override fun getBotToken(): String = botToken
    override fun getBotUsername(): String = botUsername

    override fun onUpdateReceived(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) return

        val message = update.message
        val chatId = message.chatId.toString()
        val text = message.text

        val response = when {
            text.startsWith("/addexpense") -> handleAddExpense(text)
            text == "/calculate" -> handleCalculate()
            text == "/status" -> handleStatus()
            text == "/notify" -> handleNotify()
            text == "/reset" -> handleReset()
            text == "/help" || text == "/start" -> getHelpMessage()
            else -> null
        }

        response?.let { sendMessage(chatId, it) }
    }

    private fun handleAddExpense(text: String): String {
        val parts = text.split(" ")
        if (parts.size < 3) {
            return "❌ Usage: /addexpense [name] [amount]\nExample: /addexpense John 50"
        }

        val name = parts[1]
        val amount = parts[2].toBigDecimalOrNull()

        if (amount == null || amount <= BigDecimal.ZERO) {
            return "❌ Please provide a valid positive amount"
        }

        expenses.add(Expense(name, amount))
        return "✅ Added expense: $name spent €${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun handleStatus(): String {
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

    private fun handleCalculate(): String {
        if (expenses.isEmpty()) {
            return "❌ No expenses to calculate. Add expenses first with /addexpense"
        }

        val total = expenses.sumOf { it.amount }
        val uniquePeople = expenses.map { it.buyerName }.toSet()
        val peopleCount = uniquePeople.size
        val perPerson = total.divide(BigDecimal(peopleCount), 2, RoundingMode.HALF_UP)

        val balances = mutableMapOf<String, BigDecimal>()
        uniquePeople.forEach { balances[it] = BigDecimal.ZERO }

        expenses.forEach { expense ->
            balances[expense.buyerName] = balances[expense.buyerName]!! + expense.amount
        }

        balances.forEach { (name, spent) ->
            balances[name] = spent - perPerson
        }

        val result = buildString {
            appendLine("💵 Payment Calculation:")
            appendLine()
            appendLine("Total: €${total.setScale(2, RoundingMode.HALF_UP)}")
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

    private fun handleNotify(): String {
        if (expenses.isEmpty()) {
            return "❌ No expenses to notify about. Add expenses first."
        }

        val total = expenses.sumOf { it.amount }
        val uniquePeople = expenses.map { it.buyerName }.toSet()
        val peopleCount = uniquePeople.size
        val perPerson = total.divide(BigDecimal(peopleCount), 2, RoundingMode.HALF_UP)

        val balances = mutableMapOf<String, BigDecimal>()
        uniquePeople.forEach { balances[it] = BigDecimal.ZERO }

        expenses.forEach { expense ->
            balances[expense.buyerName] = balances[expense.buyerName]!! + expense.amount
        }

        balances.forEach { (name, spent) ->
            balances[name] = spent - perPerson
        }

        val owes = balances.filter { it.value < BigDecimal.ZERO }

        if (owes.isEmpty()) {
            return "✅ No one owes money!"
        }

        val notification = buildString {
            appendLine("🔔 Payment Reminder!")
            appendLine()
            owes.forEach { (name, amount) ->
                appendLine("@${name} please transfer €${amount.abs().setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Use /calculate to see full breakdown")
        }

        return notification
    }

    private fun handleReset(): String {
        expenses.clear()
        return "🔄 All expenses cleared! Ready for a new event."
    }

    private fun getHelpMessage(): String {
        return """
            🎉 Birthday Gift Bot
            
            Commands:
            /addexpense [name] [amount] - Record an expense
            /status - View all expenses
            /calculate - Calculate who owes what
            /notify - Send payment reminders
            /reset - Clear all expenses
            /help - Show this message
            
            Example:
            /addexpense Alice 45.50
        """.trimIndent()
    }

    private fun sendMessage(chatId: String, text: String) {
        val message = SendMessage()
        message.chatId = chatId
        message.text = text
        execute(message)
    }
}

fun main() {
    val botToken = System.getenv("BOT_TOKEN")
    val botUsername = System.getenv("BOT_USERNAME")

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(BirthdayBot(botToken, botUsername))

    println("Bot is running...")
}