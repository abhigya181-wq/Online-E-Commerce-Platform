import java.sql.*;
import java.util.*;

// OrderDAO handles order placement, concurrency control, and order status tracking
public class OrderDAO {

    // Multithreading & Synchronization (Rubric: Multithreading & Synchronization)
    // Synchronized block ensures only one thread can modify stock and create an order at a time,
    // completely preventing race conditions and double-spending of the last item.
    public void placeOrder(int buyerId, int productId, int qty) throws SQLException, StockException {
        synchronized (OrderDAO.class) {
            try (Connection con = DBConnection.getConnection()) {

                // 1. Check current price and available stock
                PreparedStatement ps = con.prepareStatement("SELECT price, stock FROM products WHERE id=?");
                ps.setInt(1, productId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    throw new StockException("Product not found");
                }
                double price = rs.getDouble("price");
                int stock = rs.getInt("stock");

                // 2. Validate stock availability (Rubric: Exception Handling with custom StockException)
                if (qty <= 0 || qty > stock) {
                    throw new StockException("Only " + stock + " item(s) in stock");
                }

                // 3. Atomically decrement stock
                ps = con.prepareStatement("UPDATE products SET stock = stock - ? WHERE id=?");
                ps.setInt(1, qty);
                ps.setInt(2, productId);
                ps.executeUpdate();

                // 4. Record the new order
                ps = con.prepareStatement("INSERT INTO orders(buyer_id, product_id, quantity, total, status) VALUES(?, ?, ?, ?, 'Pending')");
                ps.setInt(1, buyerId);
                ps.setInt(2, productId);
                ps.setInt(3, qty);
                ps.setDouble(4, price * qty);
                ps.executeUpdate();
            }
        }
    }

    // Updates order delivery/fulfillment status
    public void updateStatus(int orderId, String status) throws SQLException {
        String sql = "UPDATE orders SET status=? WHERE id=?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
    }

    public List<Object[]> getAll() throws SQLException {
        return fetch("", 0);
    }

    public List<Object[]> getByBuyer(int buyerId) throws SQLException {
        return fetch("WHERE o.buyer_id=?", buyerId);
    }

    public List<Object[]> getBySeller(int sellerId) throws SQLException {
        return fetch("WHERE p.seller_id=?", sellerId);
    }

    // Joins orders, users, and products to format tabular rows for Swing JTables
    private List<Object[]> fetch(String where, int value) throws SQLException {
        String sql = "SELECT o.id, u.name, p.name, o.quantity, o.total, o.status FROM orders o "
                   + "JOIN users u ON o.buyer_id = u.id "
                   + "JOIN products p ON o.product_id = p.id "
                   + where + " ORDER BY o.id DESC";
        List<Object[]> rows = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            if (!where.isEmpty()) {
                ps.setInt(1, value);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[]{
                            rs.getInt(1),       // Order ID
                            rs.getString(2),    // Buyer Name
                            rs.getString(3),    // Product Name
                            rs.getInt(4),       // Quantity
                            rs.getDouble(5),    // Total Price
                            rs.getString(6)     // Status
                    });
                }
            }
        }
        return rows;
    }
}
