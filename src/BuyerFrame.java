import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class BuyerFrame extends JFrame {
    private User buyer;
    private ProductDAO productDAO = new ProductDAO();
    private OrderDAO orderDAO = new OrderDAO();

    // Table models for products and buyer orders
    private DefaultTableModel productModel = new DefaultTableModel(new String[]{"ID", "Name", "Price", "Stock"}, 0);
    private DefaultTableModel orderModel = new DefaultTableModel(new String[]{"Order ID", "Buyer", "Product", "Qty", "Total", "Status"}, 0);

    private JTable productTable = Util.table(productModel);
    private JTable orderTable = Util.table(orderModel);
    private JTextField searchField = new JTextField(15);

    public BuyerFrame(User buyer) {
        this.buyer = buyer;
        setTitle("Buyer Dashboard - " + buyer.getName());
        setSize(800, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.add("Browse Products", browsePanel());
        tabs.add("My Orders", Util.panel(orderTable));
        
        // Refresh orders whenever user clicks the "My Orders" tab
        tabs.addChangeListener(e -> Util.run(this, this::loadOrders));
        add(tabs);

        // Initial product catalog load
        Util.run(this, this::loadProducts);
    }

    private JPanel browsePanel() {
        // Buy action button
        JButton buy = Util.button("Buy Selected", this, () -> {
            int id = Util.selectedId(productTable);
            String qty = JOptionPane.showInputDialog(this, "Enter Quantity:", "1");
            if (qty == null || qty.trim().isEmpty()) {
                return;
            }
            orderDAO.placeOrder(buyer.getId(), id, Integer.parseInt(qty.trim()));
            JOptionPane.showMessageDialog(this, "Order placed successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            loadProducts();
        });

        // Search bar on top
        JPanel top = new JPanel();
        top.add(new JLabel("Search Product:"));
        top.add(searchField);
        top.add(Util.button("Search", this, this::loadProducts));
        top.add(Util.button("Clear / Show All", this, () -> {
            searchField.setText("");
            loadProducts();
        }));

        JPanel p = Util.panel(productTable, buy);
        p.add(top, BorderLayout.NORTH);
        return p;
    }

    // Loads catalog items matching optional keyword search
    private void loadProducts() throws Exception {
        String word = searchField.getText().trim();
        productModel.setRowCount(0);
        // Collections & Generics: List<Product>
        java.util.List<Product> list = word.isEmpty() ? productDAO.getAll() : productDAO.search(word);
        for (Product p : list) {
            productModel.addRow(new Object[]{p.getId(), p.getName(), p.getPrice(), p.getStock()});
        }
    }

    // Loads order history for the logged-in buyer
    private void loadOrders() throws Exception {
        orderModel.setRowCount(0);
        for (Object[] row : orderDAO.getByBuyer(buyer.getId())) {
            orderModel.addRow(row);
        }
    }
}
