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

class BirthdayBot(
    private val botToken: String,
    private val botUsername: String,
    private val database: Database,
    private val commandHandler: CommandHandler
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
            text.startsWith("/addexpense") -> commandHandler.handleAddExpense(chatId, text)
            text.startsWith("/addparticipant") -> commandHandler.handleAddParticipant(chatId, text)
            text.startsWith("/removeparticipant") -> commandHandler.handleRemoveParticipant(chatId, text)
            text == "/participants" -> commandHandler.handleListParticipants(chatId)
            text == "/calculate" -> commandHandler.handleCalculate(chatId)
            text == "/status" -> commandHandler.handleStatus(chatId)
            text == "/notify" -> commandHandler.handleNotify(chatId)
            text == "/reset" -> handleResetWithConfirmation(chatId)
            text == "/help" || text == "/start" -> commandHandler.getHelpMessage()
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

    val commandHandler = CommandHandler(db)

    val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
    botsApi.registerBot(BirthdayBot(botToken, botUsername, db, commandHandler))

    println("Bot is running with database support...")
}
