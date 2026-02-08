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
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BirthdayBot(
    private val botToken: String,
    private val botUsername: String,
    private val database: Database,
    private val commandHandler: CommandHandler
) : TelegramLongPollingBot() {

    private val pendingExpense = ConcurrentHashMap<Long, String>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "daily-notify").apply { isDaemon = true }
    }

    init {
        setupCommands()
        scheduleDailyNotifications()
    }

    override fun getBotToken(): String = botToken
    override fun getBotUsername(): String = botUsername

    private fun setupCommands() {
        try {
            val commands = listOf(
                BotCommand("start", "Show welcome message"),
                BotCommand("addexpense", "Add an expense (name amount)"),
                BotCommand("addparticipant", "Add participants (name1 name2 ...)"),
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

    private fun scheduleDailyNotifications() {
        val notifyTime = LocalTime.of(10, 0) // 10:00 AM UTC
        val zone = ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.now(zone)
        var nextRun = now.with(notifyTime)
        if (now >= nextRun) {
            nextRun = nextRun.plusDays(1)
        }
        val initialDelay = Duration.between(now, nextRun).seconds

        scheduler.scheduleAtFixedRate(
            { sendDailyNotifications() },
            initialDelay,
            TimeUnit.DAYS.toSeconds(1),
            TimeUnit.SECONDS
        )
        println("Daily notifications scheduled at 10:00 UTC (first run in ${initialDelay / 3600}h ${(initialDelay % 3600) / 60}m)")
    }

    private fun sendDailyNotifications() {
        try {
            val chatIds = database.getActiveChatIds()
            for (chatId in chatIds) {
                val message = commandHandler.handleNotify(chatId)
                if (!message.startsWith("❌") && !message.contains("No one owes money")) {
                    sendMessage(chatId, message)
                }
            }
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

        // Check for pending expense amount
        val pendingName = pendingExpense[chatId]
        if (pendingName != null && !text.startsWith("/")) {
            val amount = text.trim().toBigDecimalOrNull()
            if (amount != null && amount > BigDecimal.ZERO) {
                database.addExpense(chatId, pendingName, amount)
                database.addParticipant(chatId, pendingName)
                pendingExpense.remove(chatId)
                sendMessage(chatId, "✅ Added expense: $pendingName spent €${amount.setScale(2, RoundingMode.HALF_UP)}")
            } else {
                sendMessage(chatId, "❌ Please send a valid positive number for the amount.")
            }
            return
        }

        val response = when {
            text == "/addexpense" -> {
                sendExpensePicker(chatId)
                null
            }
            text.startsWith("/addexpense ") -> commandHandler.handleAddExpense(chatId, text)
            text.startsWith("/addparticipant") -> commandHandler.handleAddParticipant(chatId, text)
            text.startsWith("/removeparticipant") -> commandHandler.handleRemoveParticipant(chatId, text)
            text == "/participants" -> {
                sendParticipantList(chatId)
                null
            }
            text == "/calculate" -> commandHandler.handleCalculate(chatId)
            text == "/status" -> commandHandler.handleStatus(chatId)
            text == "/notify" -> commandHandler.handleNotify(chatId)
            text == "/reset" -> {
                handleResetWithConfirmation(chatId)
                null
            }
            text == "/help" || text == "/start" -> {
                sendMainMenu(chatId)
                null
            }
            else -> null
        }

        response?.let { sendMessage(chatId, it) }
    }

    private fun handleCallback(update: Update) {
        val callbackQuery = update.callbackQuery
        val chatId = callbackQuery.message.chatId
        val messageId = callbackQuery.message.messageId
        val data = callbackQuery.data

        when {
            data == "reset_confirm" -> {
                database.clearExpenses(chatId)
                database.clearParticipants(chatId)
                editMessage(chatId, messageId, "🔄 All expenses and participants cleared! Ready for a new event.")
            }
            data == "reset_cancel" -> {
                editMessage(chatId, messageId, "❌ Reset cancelled. Your data is safe.")
            }
            // Main menu callbacks
            data == "menu_participants" -> sendParticipantList(chatId)
            data == "menu_addexpense" -> sendExpensePicker(chatId)
            data == "menu_status" -> sendMessage(chatId, commandHandler.handleStatus(chatId))
            data == "menu_calculate" -> sendMessage(chatId, commandHandler.handleCalculate(chatId))
            data == "menu_notify" -> sendMessage(chatId, commandHandler.handleNotify(chatId))
            data == "menu_reset" -> handleResetWithConfirmation(chatId)
            // Participant list callbacks
            data.startsWith("rm_part:") -> {
                val name = data.removePrefix("rm_part:")
                database.removeParticipant(chatId, name)
                editParticipantList(chatId, messageId)
            }
            data == "add_part_hint" -> {
                sendMessage(chatId, "Send participant names:\n/addparticipant Alice Bob Charlie")
            }
            // Expense picker callbacks
            data.startsWith("expense_pick:") -> {
                val name = data.removePrefix("expense_pick:")
                pendingExpense[chatId] = name
                editMessage(chatId, messageId, "💰 Adding expense for *$name*. Send the amount:")
            }
        }
    }

    private fun sendMainMenu(chatId: Long) {
        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = listOf(
            listOf(
                InlineKeyboardButton("👥 Participants").apply { callbackData = "menu_participants" },
                InlineKeyboardButton("💰 Add Expense").apply { callbackData = "menu_addexpense" }
            ),
            listOf(
                InlineKeyboardButton("📊 Status").apply { callbackData = "menu_status" },
                InlineKeyboardButton("💵 Calculate").apply { callbackData = "menu_calculate" }
            ),
            listOf(
                InlineKeyboardButton("🔔 Notify").apply { callbackData = "menu_notify" },
                InlineKeyboardButton("🔄 Reset").apply { callbackData = "menu_reset" }
            )
        )
        sendMessageWithKeyboard(chatId, commandHandler.getHelpMessage(), keyboard)
    }

    private fun sendParticipantList(chatId: Long) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            sendMessage(chatId, "No participants yet. Use /addparticipant Name1 Name2 to add people.")
            return
        }

        val text = buildString {
            appendLine("👥 Participants (${participants.size}):")
            participants.forEach { appendLine("• ${it.name}") }
        }

        val keyboard = InlineKeyboardMarkup()
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        participants.chunked(2).forEach { chunk ->
            rows.add(chunk.map { p ->
                InlineKeyboardButton("❌ ${p.name}").apply { callbackData = "rm_part:${p.name}" }
            })
        }
        rows.add(listOf(
            InlineKeyboardButton("➕ Add Participant").apply { callbackData = "add_part_hint" }
        ))
        keyboard.keyboard = rows

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun editParticipantList(chatId: Long, messageId: Int) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            editMessage(chatId, messageId, "👥 No participants left. Use /addparticipant Name1 Name2 to add people.")
            return
        }

        val text = buildString {
            appendLine("👥 Participants (${participants.size}):")
            participants.forEach { appendLine("• ${it.name}") }
        }

        val keyboard = InlineKeyboardMarkup()
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        participants.chunked(2).forEach { chunk ->
            rows.add(chunk.map { p ->
                InlineKeyboardButton("❌ ${p.name}").apply { callbackData = "rm_part:${p.name}" }
            })
        }
        rows.add(listOf(
            InlineKeyboardButton("➕ Add Participant").apply { callbackData = "add_part_hint" }
        ))
        keyboard.keyboard = rows

        editMessageWithKeyboard(chatId, messageId, text, keyboard)
    }

    private fun sendExpensePicker(chatId: Long) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            sendMessage(chatId, "No participants yet. Add some first with /addparticipant")
            return
        }

        val keyboard = InlineKeyboardMarkup()
        val rows = participants.chunked(2).map { chunk ->
            chunk.map { p ->
                InlineKeyboardButton(p.name).apply { callbackData = "expense_pick:${p.name}" }
            }
        }
        keyboard.keyboard = rows

        sendMessageWithKeyboard(chatId, "Who paid?", keyboard)
    }

    private fun handleResetWithConfirmation(chatId: Long) {
        val keyboard = InlineKeyboardMarkup()
        val row = listOf(
            InlineKeyboardButton("✅ Yes, clear all").apply { callbackData = "reset_confirm" },
            InlineKeyboardButton("❌ No, cancel").apply { callbackData = "reset_cancel" }
        )
        keyboard.keyboard = listOf(row)

        sendMessageWithKeyboard(chatId, "⚠️ Are you sure you want to clear all expenses and participants for this chat?", keyboard)
    }

    private fun sendMessage(chatId: Long, text: String) {
        if (text.isEmpty()) return
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = text
        execute(message)
    }

    private fun sendMessageWithKeyboard(chatId: Long, text: String, keyboard: InlineKeyboardMarkup) {
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = text
        message.replyMarkup = keyboard
        execute(message)
    }

    private fun editMessage(chatId: Long, messageId: Int, text: String) {
        val editMessage = EditMessageText()
        editMessage.chatId = chatId.toString()
        editMessage.messageId = messageId
        editMessage.text = text
        execute(editMessage)
    }

    private fun editMessageWithKeyboard(chatId: Long, messageId: Int, text: String, keyboard: InlineKeyboardMarkup) {
        val editMessage = EditMessageText()
        editMessage.chatId = chatId.toString()
        editMessage.messageId = messageId
        editMessage.text = text
        editMessage.replyMarkup = keyboard
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

    val commandHandler = CommandHandler(db)

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(BirthdayBot(botToken, botUsername, db, commandHandler))

    println("Bot is running with database support...")
}
