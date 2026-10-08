import java.sql.*;

public class DBConnection {

    // Returns a connection to the SQLite database file (supports DB_PATH environment variable for cloud deployment)
    public static Connection getConnection() throws SQLException {
        String dbPath = System.getenv("DB_PATH");
        if (dbPath == null || dbPath.trim().isEmpty()) {
            dbPath = "shop.db";
        } else {
            java.io.File file = new java.io.File(dbPath);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }


    // Creates the required tables and seeds default accounts
    public static void createTables() {
        try (Connection con = getConnection(); Statement st = con.createStatement()) {
            // 1. Users table (Admin, Seller, Buyer accounts)
            st.execute("CREATE TABLE IF NOT EXISTS users("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "name TEXT, "
                    + "email TEXT UNIQUE, "
                    + "password TEXT, "
                    + "role TEXT)");

            // 2. Products table
            st.execute("CREATE TABLE IF NOT EXISTS products("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "seller_id INTEGER, "
                    + "name TEXT, "
                    + "price REAL, "
                    + "stock INTEGER)");

            // 3. Orders table
            st.execute("CREATE TABLE IF NOT EXISTS orders("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "buyer_id INTEGER, "
                    + "product_id INTEGER, "
                    + "quantity INTEGER, "
                    + "total REAL, "
                    + "status TEXT)");

            // Seed demo accounts (INSERT OR IGNORE skips if email already exists)
            st.execute("INSERT OR IGNORE INTO users(name,email,password,role) VALUES('Admin','admin@shop.com','admin123','ADMIN')");
            st.execute("INSERT OR IGNORE INTO users(name,email,password,role) VALUES('Seller','seller@shop.com','seller123','SELLER')");
            st.execute("INSERT OR IGNORE INTO users(name,email,password,role) VALUES('Buyer','buyer@shop.com','buyer123','BUYER')");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
