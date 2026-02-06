import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

data class Expense(val id: Int, val chatId: Long, val buyerName: String, val amount: BigDecimal)

class Database(private val dbUrl: String) {

    private fun getConnection(): Connection {
        return DriverManager.getConnection(dbUrl)
    }

    fun initialize() {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS expenses (
                    id SERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    buyer_name VARCHAR(255) NOT NULL,
                    amount DECIMAL(10, 2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
        }
    }

    fun addExpense(chatId: Long, buyerName: String, amount: BigDecimal): Expense {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement(
                "INSERT INTO expenses (chat_id, buyer_name, amount) VALUES (?, ?, ?) RETURNING id",
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY
            )
            stmt.setLong(1, chatId)
            stmt.setString(2, buyerName)
            stmt.setBigDecimal(3, amount)
            val rs = stmt.executeQuery()
            rs.next()
            val id = rs.getInt("id")
            return Expense(id, chatId, buyerName, amount)
        }
    }

    fun getExpenses(chatId: Long): List<Expense> {
        val expenses = mutableListOf<Expense>()
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM expenses WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                expenses.add(
                    Expense(
                        rs.getInt("id"),
                        rs.getLong("chat_id"),
                        rs.getString("buyer_name"),
                        rs.getBigDecimal("amount")
                    )
                )
            }
        }
        return expenses
    }

    fun clearExpenses(chatId: Long) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM expenses WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            stmt.executeUpdate()
        }
    }
}

class BirthdayBot(
    private val botToken: String,
    private val botUsername: String,
    private val database: Database
) : TelegramLongPollingBot() {

    init {
        // Register bot commands for nice UI in Telegram
        setupCommands()
    }

    override fun getBotToken(): String = botToken
    override fun getBotUsername(): String = botUsername

    private fun setupCommands() {
        try {
            val commands = listOf(
                BotCommand("start", "Show welcome message"),
                BotCommand("addexpense", "Add an expense (name amount)"),
                BotCommand("status", "View all expenses"),
                BotCommand("calculate", "Calculate who owes what"),
                BotCommand("notify", "Send payment reminders"),
                BotCommand("reset", "Clear all expenses"),
                BotCommand("help", "Show help message")
            )
            execute(SetMyCommands().apply { this.commands = commands })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onUpdateReceived(update: Update) {
        // Handle button callbacks
        if (update.hasCallbackQuery()) {
            handleCallback(update)
            return
        }

        if (!update.hasMessage() || !update.message.hasText()) return

        val message = update.message
        val chatId = message.chatId
        val text = message.text

        val response = when {
            text.startsWith("/addexpense") -> handleAddExpense(chatId, text)
            text == "/calculate" -> handleCalculate(chatId)
            text == "/status" -> handleStatus(chatId)
            text == "/notify" -> handleNotify(chatId)
            text == "/reset" -> handleResetWithConfirmation(chatId)
            text == "/help" || text == "/start" -> getHelpMessage()
            else -> null
        }

        response?.let { sendMessage(chatId, it) }
    }

    private fun handleCallback(update: Update) {
        val callbackQuery = update.callbackQuery
        val chatId = callbackQuery.message.chatId
        val messageId = callbackQuery.message.messageId
        val data = callbackQuery.data

        when (data) {
            "reset_confirm" -> {
                database.clearExpenses(chatId)
                editMessage(chatId, messageId, "🔄 All expenses cleared! Ready for a new event.")
            }
            "reset_cancel" -> {
                editMessage(chatId, messageId, "❌ Reset cancelled. Your expenses are safe.")
            }
        }
    }

    private fun handleAddExpense(chatId: Long, text: String): String {
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
        return "✅ Added expense: $name spent €${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun handleStatus(chatId: Long): String {
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

    private fun handleCalculate(chatId: Long): String {
        val expenses = database.getExpenses(chatId)

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

    private fun handleNotify(chatId: Long): String {
        val expenses = database.getExpenses(chatId)

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
                appendLine("${name} please transfer €${amount.abs().setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Use /calculate to see full breakdown")
        }

        return notification
    }

    private fun handleResetWithConfirmation(chatId: Long): String {
        val keyboard = InlineKeyboardMarkup()
        val row = listOf(
            InlineKeyboardButton("✅ Yes, clear all").apply { callbackData = "reset_confirm" },
            InlineKeyboardButton("❌ No, cancel").apply { callbackData = "reset_cancel" }
        )
        keyboard.keyboard = listOf(row)

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = "⚠️ Are you sure you want to clear all expenses for this chat?"
        message.replyMarkup = keyboard
        execute(message)

        return "" // Don't send additional text message
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
            
            💡 This bot works independently in each group chat!
        """.trimIndent()
    }

    private fun sendMessage(chatId: Long, text: String) {
        if (text.isEmpty()) return
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = text
        execute(message)
    }

    private fun editMessage(chatId: Long, messageId: Int, text: String) {
        val editMessage = EditMessageText()
        editMessage.chatId = chatId.toString()
        editMessage.messageId = messageId
        editMessage.text = text
        execute(editMessage)
    }
}

fun main() {
    val botToken = System.getenv("BOT_TOKEN") ?: throw IllegalArgumentException("BOT_TOKEN not set")
    val botUsername = System.getenv("BOT_USERNAME") ?: throw IllegalArgumentException("BOT_USERNAME not set")

    // Railway provides DATABASE_URL without jdbc: prefix, so we need to add it
    val rawDatabaseUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/birthday_bot?user=postgres&password=postgres"
    val databaseUrl = if (rawDatabaseUrl.startsWith("jdbc:")) {
        rawDatabaseUrl
    } else {
        "jdbc:$rawDatabaseUrl"
    }

    println("Connecting to database: ${databaseUrl.replace(Regex(":[^:@]+@"), ":****@")}")

    // Initialize database
    val database = Database(databaseUrl)
    database.initialize()

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(BirthdayBot(botToken, botUsername, database))

    println("Bot is running with database support...")
}