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
import java.util.concurrent.ConcurrentHashMap

class BirthdayBot(
    private val botToken: String,
    private val botUsername: String,
    private val database: Database,
    private val commandHandler: CommandHandler
) : TelegramLongPollingBot() {

    private val pendingExpense = ConcurrentHashMap<Long, String>()

    init {
        setupCommands()
    }

    override fun getBotToken(): String = botToken
    override fun getBotUsername(): String = botUsername

    private fun setupCommands() {
        try {
            val commands = listOf(
                BotCommand("start", "Приветствие"),
                BotCommand("addexpense", "Добавить расход (@юзер сумма)"),
                BotCommand("add", "Добавить участников (@юзер1 @юзер2 ...)"),
                BotCommand("removeparticipant", "Удалить участника (@юзер)"),
                BotCommand("setpayment", "Установить реквизиты"),
                BotCommand("getpayment", "Показать реквизиты"),
                BotCommand("participants", "Список участников"),
                BotCommand("status", "Все расходы"),
                BotCommand("calculate", "Расчёт долгов"),
                BotCommand("notify", "Напомнить должникам"),
                BotCommand("reset", "Очистить все данные"),
                BotCommand("help", "Помощь")
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
        val text = message.text.replace("@$botUsername", "")

        // Check for pending expense amount
        val pendingName = pendingExpense[chatId]
        if (pendingName != null && !text.startsWith("/")) {
            val amount = text.trim().toBigDecimalOrNull()
            if (amount != null && amount > BigDecimal.ZERO) {
                database.addExpense(chatId, pendingName, amount)
                database.addParticipant(chatId, pendingName)
                pendingExpense.remove(chatId)
                sendMessage(chatId, "✅ Расход добавлен: $pendingName потратил(а) €${amount.setScale(2, RoundingMode.HALF_UP)}")
            } else {
                sendMessage(chatId, "❌ Отправь корректное положительное число.")
            }
            return
        }

        val response = when {
            text == "/addexpense" -> {
                sendExpensePicker(chatId)
                null
            }
            text.startsWith("/addexpense ") -> commandHandler.handleAddExpense(chatId, text)
            text.startsWith("/removeparticipant") -> commandHandler.handleRemoveParticipant(chatId, text)
            text.startsWith("/setpayment") -> commandHandler.handleSetPayment(chatId, text)
            text == "/getpayment" -> commandHandler.handleGetPayment(chatId)
            text == "/add" || text.startsWith("/add ") -> commandHandler.handleAddParticipant(chatId, text)
            text == "/participants" -> {
                sendParticipantList(chatId)
                null
            }
            text == "/calculate" -> {
                sendCalculateWithButtons(chatId)
                null
            }
            text == "/status" -> {
                sendStatusWithButtons(chatId)
                null
            }
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
                database.clearPaidDebts(chatId)
                editMessage(chatId, messageId, "🔄 Все расходы и участники удалены! Готово к новому событию.")
            }
            data == "reset_cancel" -> {
                editMessage(chatId, messageId, "❌ Сброс отменён. Данные сохранены.")
            }
            // Main menu callbacks
            data == "menu_participants" -> sendParticipantList(chatId)
            data == "menu_addexpense" -> sendExpensePicker(chatId)
            data == "menu_status" -> sendStatusWithButtons(chatId)
            data == "menu_calculate" -> sendCalculateWithButtons(chatId)
            data == "menu_notify" -> sendMessage(chatId, commandHandler.handleNotify(chatId))
            data == "menu_reset" -> handleResetWithConfirmation(chatId)
            // Participant list callbacks
            data.startsWith("rm_part:") -> {
                val name = data.removePrefix("rm_part:")
                database.removeParticipant(chatId, name)
                editParticipantList(chatId, messageId)
            }
            data == "add_part_hint" -> {
                sendMessage(chatId, "Отправь юзернеймы участников:\n/add @Alice @Bob @Charlie")
            }
            // Expense picker callbacks
            data.startsWith("expense_pick:") -> {
                val name = data.removePrefix("expense_pick:")
                pendingExpense[chatId] = name
                editMessage(chatId, messageId, "💰 Добавляю расход для *$name*. Отправь ответом на это сообщение сумму:")
            }
            // Remove expense callback
            data.startsWith("rm_expense:") -> {
                val expenseId = data.removePrefix("rm_expense:").toIntOrNull()
                if (expenseId != null) {
                    database.removeExpense(chatId, expenseId)
                    editStatusWithButtons(chatId, messageId)
                }
            }
            // Mark debt as paid callbacks
            data.startsWith("mark_paid:") -> {
                val parts = data.removePrefix("mark_paid:").split(":")
                val name = parts[0]
                val amount = parts[1].toBigDecimalOrNull() ?: BigDecimal.ZERO
                database.addPaidDebt(chatId, name, amount)
                editCalculateWithButtons(chatId, messageId)
            }
        }
    }

    private fun sendMainMenu(chatId: Long) {
        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = listOf(
            listOf(
                InlineKeyboardButton("👥 Участники").apply { callbackData = "menu_participants" },
                InlineKeyboardButton("💰 Добавить расход").apply { callbackData = "menu_addexpense" }
            ),
            listOf(
                InlineKeyboardButton("📊 Расходы").apply { callbackData = "menu_status" },
                InlineKeyboardButton("💵 Расчёт").apply { callbackData = "menu_calculate" }
            ),
            listOf(
                InlineKeyboardButton("🔔 Напомнить").apply { callbackData = "menu_notify" },
                InlineKeyboardButton("🔄 Сброс").apply { callbackData = "menu_reset" }
            )
        )
        sendMessageWithKeyboard(chatId, commandHandler.getHelpMessage(), keyboard)
    }

    private fun sendParticipantList(chatId: Long) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            sendMessage(chatId, "Участников пока нет. Используй /add @Name1 @Name2 для добавления.")
            return
        }

        val text = buildString {
            appendLine("👥 Участники (${participants.size}):")
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
            InlineKeyboardButton("➕ Добавить участника").apply { callbackData = "add_part_hint" }
        ))
        keyboard.keyboard = rows

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun editParticipantList(chatId: Long, messageId: Int) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            editMessage(chatId, messageId, "👥 Участников не осталось. Используй /add @Name1 @Name2 для добавления.")
            return
        }

        val text = buildString {
            appendLine("👥 Участники (${participants.size}):")
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
            InlineKeyboardButton("➕ Добавить участника").apply { callbackData = "add_part_hint" }
        ))
        keyboard.keyboard = rows

        editMessageWithKeyboard(chatId, messageId, text, keyboard)
    }

    private fun sendExpensePicker(chatId: Long) {
        val participants = database.getParticipants(chatId)

        if (participants.isEmpty()) {
            sendMessage(chatId, "Участников пока нет. Сначала добавь через /add")
            return
        }

        val keyboard = InlineKeyboardMarkup()
        val rows = participants.chunked(2).map { chunk ->
            chunk.map { p ->
                InlineKeyboardButton(p.name).apply { callbackData = "expense_pick:${p.name}" }
            }
        }
        keyboard.keyboard = rows

        sendMessageWithKeyboard(chatId, "Кто платил?", keyboard)
    }

    private fun sendStatusWithButtons(chatId: Long) {
        val expenses = database.getExpenses(chatId)

        if (expenses.isEmpty()) {
            sendMessage(chatId, "📊 Расходов пока нет.\nИспользуй /addexpense для добавления.")
            return
        }

        val total = expenses.sumOf { it.amount }
        val text = buildString {
            appendLine("📊 Текущие расходы:")
            appendLine()
            expenses.forEach {
                appendLine("💰 ${it.buyerName}: €${it.amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Итого: €${total.setScale(2, RoundingMode.HALF_UP)}")
        }

        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = expenses.map { expense ->
            listOf(InlineKeyboardButton("❌ ${expense.buyerName}: €${expense.amount.setScale(2, RoundingMode.HALF_UP)}").apply {
                callbackData = "rm_expense:${expense.id}"
            })
        }

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun editStatusWithButtons(chatId: Long, messageId: Int) {
        val expenses = database.getExpenses(chatId)

        if (expenses.isEmpty()) {
            editMessage(chatId, messageId, "📊 Все расходы удалены.")
            return
        }

        val total = expenses.sumOf { it.amount }
        val text = buildString {
            appendLine("📊 Текущие расходы:")
            appendLine()
            expenses.forEach {
                appendLine("💰 ${it.buyerName}: €${it.amount.setScale(2, RoundingMode.HALF_UP)}")
            }
            appendLine()
            appendLine("Итого: €${total.setScale(2, RoundingMode.HALF_UP)}")
        }

        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = expenses.map { expense ->
            listOf(InlineKeyboardButton("❌ ${expense.buyerName}: €${expense.amount.setScale(2, RoundingMode.HALF_UP)}").apply {
                callbackData = "rm_expense:${expense.id}"
            })
        }

        editMessageWithKeyboard(chatId, messageId, text, keyboard)
    }

    private fun sendCalculateWithButtons(chatId: Long) {
        val text = commandHandler.handleCalculate(chatId)
        val unpaidDebts = commandHandler.getUnpaidDebts(chatId)

        if (unpaidDebts.isEmpty()) {
            sendMessage(chatId, text)
            return
        }

        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = unpaidDebts.map { (name, amount) ->
            val formatted = amount.setScale(2, RoundingMode.HALF_UP)
            listOf(InlineKeyboardButton("✅ Отметить $name (€$formatted) оплаченным").apply {
                callbackData = "mark_paid:$name:$formatted"
            })
        }

        sendMessageWithKeyboard(chatId, text, keyboard)
    }

    private fun editCalculateWithButtons(chatId: Long, messageId: Int) {
        val text = commandHandler.handleCalculate(chatId)
        val unpaidDebts = commandHandler.getUnpaidDebts(chatId)

        if (unpaidDebts.isEmpty()) {
            editMessage(chatId, messageId, text)
            return
        }

        val keyboard = InlineKeyboardMarkup()
        keyboard.keyboard = unpaidDebts.map { (name, amount) ->
            val formatted = amount.setScale(2, RoundingMode.HALF_UP)
            listOf(InlineKeyboardButton("✅ Отметить $name (€$formatted) оплаченным").apply {
                callbackData = "mark_paid:$name:$formatted"
            })
        }

        editMessageWithKeyboard(chatId, messageId, text, keyboard)
    }

    private fun handleResetWithConfirmation(chatId: Long) {
        val keyboard = InlineKeyboardMarkup()
        val row = listOf(
            InlineKeyboardButton("✅ Да, очистить всё").apply { callbackData = "reset_confirm" },
            InlineKeyboardButton("❌ Нет, отмена").apply { callbackData = "reset_cancel" }
        )
        keyboard.keyboard = listOf(row)

        sendMessageWithKeyboard(chatId, "⚠️ Уверен, что хочешь удалить все расходы и участников в этом чате?", keyboard)
    }

    private fun sendMessage(chatId: Long, text: String) {
        if (text.isEmpty()) return
        try {
            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = text
            execute(message)
        } catch (e: Exception) {
            System.err.println("Failed to send message to $chatId: ${e.message}")
        }
    }

    private fun sendMessageWithKeyboard(chatId: Long, text: String, keyboard: InlineKeyboardMarkup) {
        try {
            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = text
            message.replyMarkup = keyboard
            execute(message)
        } catch (e: Exception) {
            System.err.println("Failed to send message with keyboard to $chatId: ${e.message}")
        }
    }

    private fun editMessage(chatId: Long, messageId: Int, text: String) {
        try {
            val editMessage = EditMessageText()
            editMessage.chatId = chatId.toString()
            editMessage.messageId = messageId
            editMessage.text = text
            execute(editMessage)
        } catch (e: Exception) {
            System.err.println("Failed to edit message $messageId in $chatId: ${e.message}")
        }
    }

    private fun editMessageWithKeyboard(chatId: Long, messageId: Int, text: String, keyboard: InlineKeyboardMarkup) {
        try {
            val editMessage = EditMessageText()
            editMessage.chatId = chatId.toString()
            editMessage.messageId = messageId
            editMessage.text = text
            editMessage.replyMarkup = keyboard
            execute(editMessage)
        } catch (e: Exception) {
            System.err.println("Failed to edit message with keyboard $messageId in $chatId: ${e.message}")
        }
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
