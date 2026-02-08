import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

interface DatabaseOperations {
    fun addExpense(chatId: Long, buyerName: String, amount: BigDecimal): Expense
    fun getExpenses(chatId: Long): List<Expense>
    fun clearExpenses(chatId: Long)
    fun addParticipant(chatId: Long, name: String): Participant?
    fun removeParticipant(chatId: Long, name: String): Boolean
    fun getParticipants(chatId: Long): List<Participant>
    fun clearParticipants(chatId: Long)
    fun getActiveChatIds(): List<Long>
    fun addPaidDebt(chatId: Long, payerName: String, amount: BigDecimal)
    fun getPaidDebts(chatId: Long): Map<String, BigDecimal>
    fun clearPaidDebts(chatId: Long)
    fun setPaymentInfo(chatId: Long, info: String)
    fun getPaymentInfo(chatId: Long): String?
}

class Database(private val dbUrl: String, private val user: String, private val password: String) : DatabaseOperations {

    private fun getConnection(): Connection {
        return DriverManager.getConnection(dbUrl, user, password)
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

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS participants (
                    id SERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(chat_id, name)
                )
            """)

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS paid_debts (
                    id SERIAL PRIMARY KEY,
                    chat_id BIGINT NOT NULL,
                    payer_name VARCHAR(255) NOT NULL,
                    amount DECIMAL(10, 2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS chat_settings (
                    chat_id BIGINT PRIMARY KEY,
                    payment_info TEXT
                )
            """)
        }
    }

    override fun addExpense(chatId: Long, buyerName: String, amount: BigDecimal): Expense {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement(
                "INSERT INTO expenses (chat_id, buyer_name, amount) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            )
            stmt.setLong(1, chatId)
            stmt.setString(2, buyerName)
            stmt.setBigDecimal(3, amount)
            stmt.executeUpdate()
            val rs = stmt.generatedKeys
            rs.next()
            val id = rs.getInt(1)
            return Expense(id, chatId, buyerName, amount)
        }
    }

    override fun getExpenses(chatId: Long): List<Expense> {
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

    override fun clearExpenses(chatId: Long) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM expenses WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            stmt.executeUpdate()
        }
    }

    override fun addParticipant(chatId: Long, name: String): Participant? {
        getConnection().use { conn ->
            try {
                val stmt = conn.prepareStatement(
                    "INSERT INTO participants (chat_id, name) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
                )
                stmt.setLong(1, chatId)
                stmt.setString(2, name)
                stmt.executeUpdate()
                val rs = stmt.generatedKeys
                rs.next()
                val id = rs.getInt(1)
                return Participant(id, chatId, name)
            } catch (e: Exception) {
                // Duplicate entry
                return null
            }
        }
    }

    override fun removeParticipant(chatId: Long, name: String): Boolean {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM participants WHERE chat_id = ? AND name = ?")
            stmt.setLong(1, chatId)
            stmt.setString(2, name)
            return stmt.executeUpdate() > 0
        }
    }

    override fun getParticipants(chatId: Long): List<Participant> {
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
                        rs.getString("name")
                    )
                )
            }
        }
        return participants
    }

    override fun clearParticipants(chatId: Long) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM participants WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            stmt.executeUpdate()
        }
    }

    override fun addPaidDebt(chatId: Long, payerName: String, amount: BigDecimal) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement(
                "INSERT INTO paid_debts (chat_id, payer_name, amount) VALUES (?, ?, ?)"
            )
            stmt.setLong(1, chatId)
            stmt.setString(2, payerName)
            stmt.setBigDecimal(3, amount)
            stmt.executeUpdate()
        }
    }

    override fun getPaidDebts(chatId: Long): Map<String, BigDecimal> {
        val paid = mutableMapOf<String, BigDecimal>()
        getConnection().use { conn ->
            val stmt = conn.prepareStatement(
                "SELECT payer_name, SUM(amount) as total FROM paid_debts WHERE chat_id = ? GROUP BY payer_name"
            )
            stmt.setLong(1, chatId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                paid[rs.getString("payer_name")] = rs.getBigDecimal("total")
            }
        }
        return paid
    }

    override fun clearPaidDebts(chatId: Long) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("DELETE FROM paid_debts WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            stmt.executeUpdate()
        }
    }

    override fun setPaymentInfo(chatId: Long, info: String) {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement(
                "MERGE INTO chat_settings (chat_id, payment_info) VALUES (?, ?)"
            )
            stmt.setLong(1, chatId)
            stmt.setString(2, info)
            stmt.executeUpdate()
        }
    }

    override fun getPaymentInfo(chatId: Long): String? {
        getConnection().use { conn ->
            val stmt = conn.prepareStatement("SELECT payment_info FROM chat_settings WHERE chat_id = ?")
            stmt.setLong(1, chatId)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("payment_info") else null
        }
    }

    override fun getActiveChatIds(): List<Long> {
        val chatIds = mutableSetOf<Long>()
        getConnection().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT DISTINCT chat_id FROM expenses")
            while (rs.next()) {
                chatIds.add(rs.getLong("chat_id"))
            }
        }
        return chatIds.toList()
    }
}
