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
}
