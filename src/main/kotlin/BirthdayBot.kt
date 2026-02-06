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
data class Participant(val id: Int, val chatId: Long, val name: String)

class Database(private val dbUrl: String, private val user: String, private val password: String) {

    private fun getConnection(): Connection {
        return DriverManager.getConnection(dbUrl, user, password)
    }

    fun initialize() {
        getConnection().use { conn ->
            // Expenses table
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS expenses (
                    id SERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    buyer_name VARCHAR(255) NOT NULL,
                    amount DECIMAL(10, 2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)

            // Participants table
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS participants (
                    id SERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(chat_id, name)
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

    // Participant methods
    fun addParticipant(chatId: Long, name: String): Participant? {
        getConnection().use { conn ->
            try {
                val stmt = conn.prepareStatement(
                    "INSERT INTO participants (chat_id, name) VALUES (?, ?) RETURNING id",
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY
                )
                stmt.setLong(1, chatId)
                stmt.setString(2, name)
                val rs = stmt.executeQuery()
                rs.next()
                val id = rs.getInt("id")
                return Participant(id, chatId, name)
            } catch (e: Exception) {
                // Duplicate entry
                return null
            }
        }
    }

    fun removeParticipant(chatId: Long, name: String): Boolean {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM participants WHERE chat_id = ? AND name = ?")
            stmt.setLong(1, chatId)
            stmt.setString(2, name)
            return stmt.executeUpdate() > 0
        }
    }

    fun getParticipants(chatId: Long): List<Participant> {
        val participants = mutableListOf<Participant>()
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("SELECT * FROM participants WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                participants.add(
                    Participant(
                        rs.getInt("id"),
                        rs.getLong("chat_id"),
                        rs.getString("buyer_name")
                    )
                )
            }
        }
        return participants
    }

    fun clearParticipants(chatId: Long) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM participants WHERE chat_id = ?")
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
        setupCommands()
    }

    override fun getBotToken(): String = botToken
    override fun getBotUsername(): String = botUsername

    private fun setupCommands() {
        try {
            val commands = listOf(
                BotCommand("start", "Show welcome message"),
                BotCommand("addexpense", "Add an expense (name amount)"),
                BotCommand("addparticipant", "Add a participant (name)"),
                BotCommand("removeparticipant", "Remove a participant (name)"),
                BotCommand("participants", "List all participants"),
                BotCommand("status", "View all expenses"),
                BotCommand("calculate", "Calculate who owes what"),
                BotCommand("notify", "Send payment reminders"),
                BotCommand("reset", "Clear all expenses and participants"),
                BotCommand("help", "Show help message")
            )
            execute(SetMyCommands().apply { this.commands = commands })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onUpdateReceived(update: Update) {
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
            text.startsWith("/addparticipant") -> handleAddParticipant(chatId, text)
            text.startsWith("/removeparticipant") -> handleRemoveParticipant(chatId, text)
            text == "/participants" -> handleListParticipants(chatId)
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
                database.clearParticipants(chatId)
                editMessage(chatId, messageId, "🔄 All expenses and participants cleared! Ready for a new event.")
            }
            "reset_cancel" -> {
                editMessage(chatId, messageId, "❌ Reset cancelled. Your data is safe.")
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

        // Auto-add as participant if not already added
        database.addParticipant(chatId, name)

        return "✅ Added expense: $name spent €${amount.setScale(2, RoundingMode.HALF_UP)}"
    }

    private fun handleAddParticipant(chatId: Long, text: String): String {
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

    private fun handleRemoveParticipant(chatId: Long, text: String): String {
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

    private fun handleListParticipants(chatId: Long): String {
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

    private fun handleNotify(chatId: Long): String {
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

    private fun handleResetWithConfirmation(chatId: Long): String {
        val keyboard = InlineKeyboardMarkup()
        val row = listOf(
            InlineKeyboardButton("✅ Yes, clear all").apply { callbackData = "reset_confirm" },
            InlineKeyboardButton("❌ No, cancel").apply { callbackData = "reset_cancel" }
        )
        keyboard.keyboard = listOf(row)

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = "⚠️ Are you sure you want to clear all expenses and participants for this chat?"
        message.replyMarkup = keyboard
        execute(message)

        return ""
    }

    private fun getHelpMessage(): String {
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

    // Parse Railway's DATABASE_URL: postgresql://user:password@host:port/database
    val databaseUrl = System.getenv("DATABASE_URL") ?: throw IllegalArgumentException("DATABASE_URL not set")

    val regex = Regex("postgresql://([^:]+):([^@]+)@([^:]+):(\\d+)/(.+)")
    val matchResult = regex.find(databaseUrl) ?: throw IllegalArgumentException("Invalid DATABASE_URL format")

    val (user, password, host, port, database) = matchResult.destructured
    val jdbcUrl = "jdbc:postgresql://$host:$port/$database?sslmode=require"

    println("Connecting to database at $host:$port...")

    val db = Database(jdbcUrl, user, password)
    db.initialize()

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(BirthdayBot(botToken, botUsername, db))

    println("Bot is running with database support...")
}