import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class SellerFrame extends JFrame {
    private User seller;
    private ProductDAO productDAO = new ProductDAO();
    private OrderDAO orderDAO = new OrderDAO();

    // Table models for seller's products and incoming orders
    private DefaultTableModel productModel = new DefaultTableModel(new String[]{"ID", "Name", "Price", "Stock"}, 0);
    private DefaultTableModel orderModel = new DefaultTableModel(new String[]{"Order ID", "Buyer", "Product", "Qty", "Total", "Status"}, 0);

    private JTable productTable = Util.table(productModel);
    private JTable orderTable = Util.table(orderModel);
    private JComboBox<String> statusBox = new JComboBox<>(new String[]{"Pending", "Shipped", "Delivered", "Cancelled"});
    private JLabel lowStockLabel = new JLabel(" ");

    public SellerFrame(User seller) {
        this.seller = seller;
        setTitle("Seller Dashboard - " + seller.getName());
        setSize(800, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.add("My Products", productPanel());
        tabs.add("Orders Received", orderPanel());
        tabs.addChangeListener(e -> Util.run(this, this::loadOrders));
        add(tabs);

        loadProducts();
        loadOrders();
    }

    private JPanel productPanel() {
        // Add new product
        JButton add = Util.button("Add Product", this, () -> {
            String name = JOptionPane.showInputDialog(this, "Product name:");
            if (name == null || name.trim().isEmpty()) return;

            String price = JOptionPane.showInputDialog(this, "Price ($):");
            if (price == null || price.trim().isEmpty()) return;

            String stock = JOptionPane.showInputDialog(this, "Initial Stock quantity:");
            if (stock == null || stock.trim().isEmpty()) return;

            productDAO.add(new Product(0, seller.getId(), name.trim(), Double.parseDouble(price.trim()), Integer.parseInt(stock.trim())));
            loadProducts();
        });

        // Update inventory stock
        JButton updateStock = Util.button("Update Stock", this, () -> {
            int id = Util.selectedId(productTable);
            String stock = JOptionPane.showInputDialog(this, "New stock quantity:");
            if (stock == null || stock.trim().isEmpty()) return;

            productDAO.updateStock(id, Integer.parseInt(stock.trim()));
            loadProducts();
        });

        // Delete product
        JButton del = Util.button("Delete Product", this, () -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete this product?", "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                productDAO.delete(Util.selectedId(productTable));
                loadProducts();
            }
        });

        JPanel p = Util.panel(productTable, add, updateStock, del);

        // Low stock warning banner at the top
        lowStockLabel.setForeground(new Color(200, 30, 30));
        lowStockLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        p.add(lowStockLabel, BorderLayout.NORTH);
        return p;
    }

    private JPanel orderPanel() {
        // Update order status (Pending -> Shipped -> Delivered -> Cancelled)
        JButton update = Util.button("Update Status", this, () -> {
            int orderId = Util.selectedId(orderTable);
            String newStatus = (String) statusBox.getSelectedItem();
            orderDAO.updateStatus(orderId, newStatus);
            JOptionPane.showMessageDialog(this, "Order #" + orderId + " marked as " + newStatus);
            loadOrders();
        });

        JButton refresh = Util.button("Refresh Orders", this, this::loadOrders);

        return Util.panel(orderTable, new JLabel("Change Status:"), statusBox, update, refresh);
    }

    // Loads seller products and checks for low-stock alerts (< 5 units)
    private void loadProducts() {
        Util.run(this, () -> {
            productModel.setRowCount(0);
            StringBuilder lowStockWarning = new StringBuilder();
            for (Product p : productDAO.getBySeller(seller.getId())) {
                productModel.addRow(new Object[]{p.getId(), p.getName(), p.getPrice(), p.getStock()});
                if (p.getStock() < 5) {
                    lowStockWarning.append(p.getName()).append(" (Stock: ").append(p.getStock()).append(")  ");
                }
            }
            if (lowStockWarning.length() > 0) {
                lowStockLabel.setText(" ⚠️ LOW STOCK ALERT: " + lowStockWarning.toString());
            } else {
                lowStockLabel.setText(" ");
            }
        });
    }

    // Loads incoming orders for this seller's products
    private void loadOrders() {
        Util.run(this, () -> {
            orderModel.setRowCount(0);
            for (Object[] row : orderDAO.getBySeller(seller.getId())) {
                orderModel.addRow(row);
            }
        });
    }
}
